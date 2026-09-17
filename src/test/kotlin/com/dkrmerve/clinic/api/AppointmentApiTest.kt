package com.dkrmerve.clinic.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** The appointment lifecycle over HTTP: booking rules, transitions, cancellation policy, no-shows, reschedule. */
class AppointmentApiTest :
    FunSpec({
        val tuesday = LocalDate.of(2027, 1, 12)
        val ten = LocalTime.of(10, 0)

        context("POST /appointments") {
            test("books -> 201 with Location, status Booked, one history entry, version 0; GET includes the history") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val start = at(tuesday, ten)
                    val response = book(practitioner.id, patient.id, start, token = patientToken(patient.id))
                    response.status shouldBe HttpStatusCode.Created
                    val created = response.body<AppointmentResponse>()
                    response.headers[HttpHeaders.Location] shouldBe "/appointments/${created.id}"
                    created.status shouldBe "Booked"
                    created.start shouldBe "2027-01-12T10:00:00+01:00"
                    created.end shouldBe "2027-01-12T10:30:00+01:00"
                    created.version shouldBe 0
                    created.promotedFromWaitlist shouldBe false
                    created.history shouldHaveSize 1
                    created.history.single().actor shouldBe "patient"
                    created.history
                        .single()
                        .from
                        .shouldBeNull()
                    created.history.single().to shouldBe "Booked"
                    appointment(created.id) shouldBe created
                }
            }

            test("rule violations surface with the documented status and code") {
                clinicApp {
                    val practitioner = createPractitioner(maxAppointmentsPerDay = 2)
                    val patient = createPatient()
                    bookOk(practitioner.id, patient.id, at(tuesday, ten))
                    book(practitioner.id, createPatient().id, at(tuesday, ten)).shouldBeProblem(HttpStatusCode.Conflict, "slot_taken")
                    book(
                        practitioner.id,
                        patient.id,
                        at(tuesday, LocalTime.of(14, 0)),
                    ).shouldBeProblem(HttpStatusCode.Conflict, "patient_conflict")
                    book(
                        createPractitioner().id,
                        patient.id,
                        at(tuesday, LocalTime.of(10, 15)),
                    ).shouldBeProblem(HttpStatusCode.Conflict, "patient_conflict")
                    bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(12, 0)))
                    book(
                        practitioner.id,
                        createPatient().id,
                        at(tuesday, LocalTime.of(15, 0)),
                    ).shouldBeProblem(HttpStatusCode.Conflict, "daily_capacity_reached")
                    book(
                        practitioner.id,
                        createPatient().id,
                        at(tuesday, LocalTime.of(8, 0)),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_working_hours")
                    book(
                        practitioner.id,
                        createPatient().id,
                        at(tuesday, LocalTime.of(9, 5)),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "slot_misaligned")
                    book(
                        practitioner.id,
                        createPatient().id,
                        at(tuesday.plusDays(90), ten),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_booking_horizon")
                    book(
                        practitioner.id,
                        createPatient().id,
                        at(LocalDate.of(2027, 1, 8), ten),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_booking_horizon")
                    post(
                        "/practitioners/${practitioner.id}/time-off",
                        staff,
                        TimeOffRequest(iso(at(tuesday.plusDays(1), ten)), iso(at(tuesday.plusDays(1), LocalTime.of(11, 0))), "Training"),
                    )
                    book(
                        practitioner.id,
                        createPatient().id,
                        at(tuesday.plusDays(1), ten),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "practitioner_unavailable")
                }
            }

            test("unknown practitioner or patient -> 404 with the entity in the code") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    book(
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        patient.id,
                        at(tuesday, ten),
                    ).shouldBeProblem(HttpStatusCode.NotFound, "practitioner_not_found")
                    book(
                        practitioner.id,
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        at(tuesday, ten),
                    ).shouldBeProblem(HttpStatusCode.NotFound, "patient_not_found")
                }
            }
        }

        context("check-in and complete (rule 10, rule 11)") {
            test("Booked -> CheckedIn -> Completed with a history entry per step") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val booked = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val checkedIn = post("/appointments/${booked.id}/check-in", staff).body<AppointmentResponse>()
                    checkedIn.status shouldBe "CheckedIn"
                    checkedIn.version shouldBe 1
                    val completed = post("/appointments/${booked.id}/complete", staff).body<AppointmentResponse>()
                    completed.status shouldBe "Completed"
                    completed.version shouldBe 2
                    completed.history.map { it.to } shouldBe listOf("Booked", "CheckedIn", "Completed")
                    completed.history.last().from shouldBe "CheckedIn"
                    completed.history.last().actor shouldBe "clinic"
                }
            }

            test("complete from Booked, check-in twice, cancel after Completed -> 409 invalid_transition") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val booked = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    post("/appointments/${booked.id}/complete", staff).shouldBeProblem(HttpStatusCode.Conflict, "invalid_transition")
                    post("/appointments/${booked.id}/check-in", staff).status shouldBe HttpStatusCode.OK
                    post("/appointments/${booked.id}/check-in", staff).shouldBeProblem(HttpStatusCode.Conflict, "invalid_transition")
                    post("/appointments/${booked.id}/complete", staff).status shouldBe HttpStatusCode.OK
                    post("/appointments/${booked.id}/cancel", staff).shouldBeProblem(HttpStatusCode.Conflict, "invalid_transition")
                    appointment(booked.id).history shouldHaveSize 3
                }
            }

            test("a checked-in appointment can be cancelled by the clinic but not by the patient") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val start = at(tuesday, ten)
                    val a = bookOk(practitioner.id, patient.id, start)
                    val b = bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(11, 0)))
                    post("/appointments/${a.id}/check-in", staff)
                    post("/appointments/${b.id}/check-in", staff)
                    clock.set(start.minus(Duration.ofDays(2)))
                    post(
                        "/appointments/${a.id}/cancel",
                        patientToken(patient.id),
                    ).shouldBeProblem(HttpStatusCode.Conflict, "invalid_transition")
                    post("/appointments/${b.id}/cancel", staff).status shouldBe HttpStatusCode.OK
                    appointment(b.id).cancelledBy shouldBe "clinic"
                }
            }

            test("unknown appointment -> 404 appointment_not_found on every action") {
                clinicApp {
                    val id = java.util.UUID.randomUUID()
                    get("/appointments/$id", staff).shouldBeProblem(HttpStatusCode.NotFound, "appointment_not_found")
                    post("/appointments/$id/check-in", staff).shouldBeProblem(HttpStatusCode.NotFound, "appointment_not_found")
                    post("/appointments/$id/cancel", staff).shouldBeProblem(HttpStatusCode.NotFound, "appointment_not_found")
                    post("/appointments/nope/complete", staff).shouldBeProblem(HttpStatusCode.NotFound, "appointment_not_found")
                }
            }
        }

        context("cancellation policy over HTTP (rule 7)") {
            suspend fun ClinicTestContext.bookedAt(start: java.time.Instant): Pair<AppointmentResponse, String> {
                val practitioner = createPractitioner()
                val patient = createPatient()
                return bookOk(practitioner.id, patient.id, start) to patientToken(patient.id)
            }

            test("48h before -> Cancelled by patient, not late, counter untouched") {
                clinicApp {
                    val start = at(tuesday.plusDays(7), ten)
                    val (a, token) = bookedAt(start)
                    clock.set(start.minus(Duration.ofHours(48)))
                    val result = post("/appointments/${a.id}/cancel", token, CancelRequest("Feeling better")).body<CancellationResponse>()
                    result.appointment.status shouldBe "Cancelled"
                    result.appointment.cancelledBy shouldBe "patient"
                    result.appointment.lateCancellation shouldBe false
                    result.appointment.history
                        .last()
                        .note shouldBe "Feeling better"
                    result.waitlistPromotion.shouldBeNull()
                    get("/patients/${a.patientId}", staff).body<PatientResponse>().lateCancellations shouldBe 0
                }
            }

            test("between 24h and 2h before -> late cancellation and the patient's counter increments") {
                clinicApp {
                    val start = at(tuesday.plusDays(7), ten)
                    val (a, token) = bookedAt(start)
                    clock.set(start.minus(Duration.ofHours(5)))
                    val result = post("/appointments/${a.id}/cancel", token).body<CancellationResponse>()
                    result.appointment.lateCancellation shouldBe true
                    get("/patients/${a.patientId}", staff).body<PatientResponse>().lateCancellations shouldBe 1
                }
            }

            test("less than 2h before -> 422 cancellation_window_closed and the appointment stays Booked") {
                clinicApp {
                    val start = at(tuesday.plusDays(7), ten)
                    val (a, token) = bookedAt(start)
                    clock.set(start.minus(Duration.ofMinutes(90)))
                    post(
                        "/appointments/${a.id}/cancel",
                        token,
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "cancellation_window_closed")
                    appointment(a.id).status shouldBe "Booked"
                    clock.set(start.minus(Duration.ofMinutes(1)))
                    post("/appointments/${a.id}/cancel", staff).status shouldBe HttpStatusCode.OK
                }
            }

            test("cancelling an already cancelled appointment -> 409 invalid_transition") {
                clinicApp {
                    val (a, token) = bookedAt(at(tuesday.plusDays(7), ten))
                    post("/appointments/${a.id}/cancel", token).status shouldBe HttpStatusCode.OK
                    post("/appointments/${a.id}/cancel", token).shouldBeProblem(HttpStatusCode.Conflict, "invalid_transition")
                }
            }

            test("a note longer than 500 characters -> 400 validation_failed") {
                clinicApp {
                    val (a, token) = bookedAt(at(tuesday.plusDays(7), ten))
                    post(
                        "/appointments/${a.id}/cancel",
                        token,
                        CancelRequest("x".repeat(501)),
                    ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                }
            }
        }

        context("no-show and blocking (rule 8)") {
            test("before the start -> 409 no_show_before_start; after the start -> NoShow recorded on the patient") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val start = at(tuesday, ten)
                    val a = bookOk(practitioner.id, patient.id, start)
                    post("/appointments/${a.id}/no-show", staff).shouldBeProblem(HttpStatusCode.Conflict, "no_show_before_start")
                    clock.set(start.plus(Duration.ofMinutes(20)))
                    post("/appointments/${a.id}/no-show", staff).body<AppointmentResponse>().status shouldBe "NoShow"
                    val updated = get("/patients/${patient.id}", staff).body<PatientResponse>()
                    updated.noShows shouldBe listOf(iso(start))
                    updated.blocked shouldBe false
                }
            }

            test("no-show on a cancelled or completed appointment -> 409 invalid_transition") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val start = at(tuesday, ten)
                    val cancelled = bookOk(practitioner.id, createPatient().id, start)
                    val completed = bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(11, 0)))
                    post("/appointments/${cancelled.id}/cancel", staff)
                    post("/appointments/${completed.id}/check-in", staff)
                    post("/appointments/${completed.id}/complete", staff)
                    clock.set(start.plus(Duration.ofHours(3)))
                    post("/appointments/${cancelled.id}/no-show", staff).shouldBeProblem(HttpStatusCode.Conflict, "invalid_transition")
                    post("/appointments/${completed.id}/no-show", staff).shouldBeProblem(HttpStatusCode.Conflict, "invalid_transition")
                }
            }

            test(
                "the third no-show within 90 days blocks the patient: booking and waitlist -> 403 patient_blocked with blockedUntil; the block lifts at blockedUntil",
            ) {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val token = patientToken(patient.id)
                    val days = listOf(tuesday, tuesday.plusDays(7), tuesday.plusDays(14))
                    val appointments = days.map { bookOk(practitioner.id, patient.id, at(it, ten)) }
                    val existing = bookOk(practitioner.id, patient.id, at(tuesday.plusDays(21), ten))
                    clock.set(at(days.last(), LocalTime.of(11, 0)))
                    appointments.forEach { post("/appointments/${it.id}/no-show", staff).status shouldBe HttpStatusCode.OK }

                    val blocked = get("/patients/${patient.id}", staff).body<PatientResponse>()
                    blocked.blocked shouldBe true
                    val blockedUntil = clock.instant().plus(Duration.ofDays(30))
                    blocked.blockedUntil shouldBe iso(blockedUntil)

                    val problem =
                        book(practitioner.id, patient.id, at(tuesday.plusDays(28), ten), token = token)
                            .shouldBeProblem(HttpStatusCode.Forbidden, "patient_blocked")
                    problem.blockedUntil shouldBe iso(blockedUntil)
                    post("/waitlist", token, JoinWaitlistRequest(practitioner.id, patient.id, tuesday.plusDays(28).toString(), "FollowUp"))
                        .shouldBeProblem(HttpStatusCode.Forbidden, "patient_blocked")

                    // Existing appointments stay valid and can still be checked in.
                    clock.set(at(tuesday.plusDays(21), LocalTime.of(9, 55)))
                    post("/appointments/${existing.id}/check-in", staff).body<AppointmentResponse>().status shouldBe "CheckedIn"

                    clock.set(blockedUntil)
                    get("/patients/${patient.id}", staff).body<PatientResponse>().blocked shouldBe false
                    book(practitioner.id, patient.id, at(tuesday.plusDays(49), ten), token = token).status shouldBe HttpStatusCode.Created
                }
            }
        }

        context("reschedule (rule 10, atomic)") {
            test("moves the appointment: old Cancelled with a note, new Booked, both linked in history notes") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val a = bookOk(practitioner.id, patient.id, at(tuesday, ten))
                    val newStart = at(tuesday.plusDays(1), LocalTime.of(14, 0))
                    val result =
                        post(
                            "/appointments/${a.id}/reschedule",
                            patientToken(patient.id),
                            RescheduleRequest(iso(newStart)),
                        ).body<RescheduleResponse>()
                    result.noOp shouldBe false
                    result.previous.status shouldBe "Cancelled"
                    result.previous.history
                        .last()
                        .note shouldContain "Rescheduled to"
                    result.replacement.status shouldBe "Booked"
                    result.replacement.start shouldBe iso(newStart)
                    result.replacement.history
                        .single()
                        .note shouldContain a.id
                    result.waitlistPromotion.shouldBeNull()
                    appointment(a.id).status shouldBe "Cancelled"
                }
            }

            test("when the new slot is invalid nothing changes: old stays Booked, no history entry, version unchanged") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val a = bookOk(practitioner.id, patient.id, at(tuesday, ten))
                    bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(11, 0)))
                    post("/appointments/${a.id}/reschedule", staff, RescheduleRequest(iso(at(tuesday, LocalTime.of(11, 0)))))
                        .shouldBeProblem(HttpStatusCode.Conflict, "slot_taken")
                    post("/appointments/${a.id}/reschedule", staff, RescheduleRequest(iso(at(tuesday, LocalTime.of(20, 0)))))
                        .shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_working_hours")
                    val untouched = appointment(a.id)
                    untouched.status shouldBe "Booked"
                    untouched.history shouldHaveSize 1
                    untouched.version shouldBe 0
                    get("/patients/${patient.id}/appointments", staff).body<PageResponse<AppointmentResponse>>().total shouldBe 1
                }
            }

            test(
                "a patient rescheduling inside the late window pays the late-cancellation penalty; inside the closed window it is refused",
            ) {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val start = at(tuesday.plusDays(7), ten)
                    val a = bookOk(practitioner.id, patient.id, start)
                    clock.set(start.minus(Duration.ofHours(3)))
                    val moved =
                        post(
                            "/appointments/${a.id}/reschedule",
                            patientToken(patient.id),
                            RescheduleRequest(iso(at(tuesday.plusDays(8), ten))),
                        ).body<RescheduleResponse>()
                    moved.previous.lateCancellation shouldBe true
                    get("/patients/${patient.id}", staff).body<PatientResponse>().lateCancellations shouldBe 1
                    val nextStart = at(tuesday.plusDays(8), ten)
                    clock.set(nextStart.minus(Duration.ofMinutes(30)))
                    post(
                        "/appointments/${moved.replacement.id}/reschedule",
                        patientToken(patient.id),
                        RescheduleRequest(iso(at(tuesday.plusDays(9), ten))),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "cancellation_window_closed")
                    appointment(moved.replacement.id).status shouldBe "Booked"
                }
            }

            test("rescheduling to the identical slot is a no-op that returns 200 with the same appointment") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val a = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val result =
                        post(
                            "/appointments/${a.id}/reschedule",
                            staff,
                            RescheduleRequest(a.start, practitionerId = practitioner.id, type = "Consultation"),
                        ).body<RescheduleResponse>()
                    result.noOp shouldBe true
                    result.previous shouldBe a
                    result.replacement shouldBe a
                    appointment(a.id).status shouldBe "Booked"
                }
            }

            test("can move to another practitioner and change the type") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val other = createPractitioner()
                    val a = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val result =
                        post(
                            "/appointments/${a.id}/reschedule",
                            staff,
                            RescheduleRequest(iso(at(tuesday, ten)), practitionerId = other.id, type = "Procedure"),
                        ).body<RescheduleResponse>()
                    result.replacement.practitionerId shouldBe other.id
                    result.replacement.type shouldBe "Procedure"
                    result.replacement.end shouldBe iso(at(tuesday, LocalTime.of(11, 0)))
                }
            }

            test("validation errors for newStart without offset, bad practitioner id and unknown type are collected") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val a = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val problem =
                        post(
                            "/appointments/${a.id}/reschedule",
                            staff,
                            RescheduleRequest("2027-01-13T10:00", practitionerId = "x", type = "Spa"),
                        ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    problem.errors!!.keys shouldBe setOf("newStart", "practitionerId", "type")
                    problem.errors.shouldNotBeNull()
                }
            }
        }
    })
