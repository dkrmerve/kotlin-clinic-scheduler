package com.dkrmerve.clinic.api

import com.dkrmerve.clinic.Repositories
import com.dkrmerve.clinic.application.WaitlistRepository
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.WaitlistEntry
import com.dkrmerve.clinic.domain.WaitlistEntryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Rule 9: joining the waitlist and automatic FIFO promotion when a slot frees up. */
class WaitlistApiTest :
    FunSpec({
        val tuesday = LocalDate.of(2027, 1, 12)
        val ten = LocalTime.of(10, 0)

        suspend fun ClinicTestContext.join(
            practitionerId: String,
            patientId: String,
            date: LocalDate = tuesday,
            type: String = "Consultation",
        ) = post("/waitlist", staff, JoinWaitlistRequest(practitionerId, patientId, date.toString(), type))

        suspend fun ClinicTestContext.joinOk(
            practitionerId: String,
            patientId: String,
            date: LocalDate = tuesday,
            type: String = "Consultation",
        ): WaitlistEntryResponse {
            val response = join(practitionerId, patientId, date, type)
            response.status shouldBe HttpStatusCode.Created
            return response.body()
        }

        suspend fun ClinicTestContext.entry(id: String): WaitlistEntryResponse = get("/waitlist/$id", staff).body()

        context("POST /waitlist and GET /waitlist") {
            test("joins -> 201 with Location, GET /waitlist/{id} and the FIFO listing show it as Waiting") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val response = join(practitioner.id, patient.id, type = "FollowUp")
                    response.status shouldBe HttpStatusCode.Created
                    val created = response.body<WaitlistEntryResponse>()
                    response.headers[HttpHeaders.Location] shouldBe "/waitlist/${created.id}"
                    created.status shouldBe "Waiting"
                    created.type shouldBe "FollowUp"
                    created.fulfilledBy.shouldBeNull()
                    entry(created.id) shouldBe created
                    val listing =
                        get(
                            "/waitlist?practitionerId=${practitioner.id}&date=$tuesday",
                            staff,
                        ).body<PageResponse<WaitlistEntryResponse>>()
                    listing.items shouldBe listOf(created)
                    listing.total shouldBe 1
                }
            }

            test("joining twice for the same practitioner and date -> 409 waitlist_duplicate") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    joinOk(practitioner.id, patient.id)
                    join(practitioner.id, patient.id, type = "FollowUp").shouldBeProblem(HttpStatusCode.Conflict, "waitlist_duplicate")
                    joinOk(practitioner.id, patient.id, date = tuesday.plusDays(1))
                }
            }

            test("past date, date beyond the horizon and a day off -> 422; missing/invalid fields -> 400") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    join(
                        practitioner.id,
                        patient.id,
                        date = tuesday.minusDays(7),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_booking_horizon")
                    join(
                        practitioner.id,
                        patient.id,
                        date = tuesday.plusDays(90),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_booking_horizon")
                    join(
                        practitioner.id,
                        patient.id,
                        date = LocalDate.of(2027, 1, 16),
                    ).shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_working_hours")
                    val problem =
                        post(
                            "/waitlist",
                            staff,
                            JoinWaitlistRequest("x", "", "tomorrow", "Spa"),
                        ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    problem.errors!!.keys shouldBe setOf("practitionerId", "patientId", "date", "type")
                    join(
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        patient.id,
                    ).shouldBeProblem(HttpStatusCode.NotFound, "practitioner_not_found")
                    join(
                        practitioner.id,
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    ).shouldBeProblem(HttpStatusCode.NotFound, "patient_not_found")
                }
            }

            test("listing requires practitionerId and date, supports paging, and 404s for unknown practitioner or entry") {
                clinicApp {
                    val practitioner = createPractitioner()
                    repeat(3) { joinOk(practitioner.id, createPatient().id) }
                    get("/waitlist", staff).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed").errors!!.keys shouldBe
                        setOf("practitionerId", "date")
                    val page =
                        get(
                            "/waitlist?practitionerId=${practitioner.id}&date=$tuesday&page=2&pageSize=2",
                            staff,
                        ).body<PageResponse<WaitlistEntryResponse>>()
                    page.items shouldHaveSize 1
                    page.total shouldBe 3
                    get(
                        "/waitlist?practitionerId=${java.util.UUID.randomUUID()}&date=$tuesday",
                        staff,
                    ).shouldBeProblem(HttpStatusCode.NotFound, "practitioner_not_found")
                    get(
                        "/waitlist/${java.util.UUID.randomUUID()}",
                        staff,
                    ).shouldBeProblem(HttpStatusCode.NotFound, "waitlist_entry_not_found")
                }
            }

            test("entries whose date has passed are reported as Expired on read") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val created = joinOk(practitioner.id, createPatient().id)
                    clock.advance(Duration.ofDays(2))
                    entry(created.id).status shouldBe "Expired"
                    get(
                        "/waitlist?practitionerId=${practitioner.id}&date=$tuesday",
                        staff,
                    ).body<PageResponse<WaitlistEntryResponse>>().items.single().status shouldBe
                        "Expired"
                }
            }
        }

        context("automatic promotion on cancellation") {
            test(
                "FIFO: with three waiting entries the oldest gets the freed slot; the promoted appointment is flagged and the entry Fulfilled",
            ) {
                clinicApp {
                    val practitioner = createPractitioner()
                    val holder = createPatient("Holder")
                    val booked = bookOk(practitioner.id, holder.id, at(tuesday, ten))
                    val first = joinOk(practitioner.id, createPatient("First").id)
                    clock.advance(Duration.ofSeconds(1))
                    val second = joinOk(practitioner.id, createPatient("Second").id)
                    clock.advance(Duration.ofSeconds(1))
                    val third = joinOk(practitioner.id, createPatient("Third").id)

                    val result = post("/appointments/${booked.id}/cancel", staff).body<CancellationResponse>()
                    result.appointment.status shouldBe "Cancelled"
                    val promoted = result.waitlistPromotion.shouldNotBeNull()
                    promoted.patientId shouldBe first.patientId
                    promoted.start shouldBe booked.start
                    promoted.promotedFromWaitlist shouldBe true
                    promoted.history.single().actor shouldBe "clinic"
                    entry(first.id).status shouldBe "Fulfilled"
                    entry(first.id).fulfilledBy shouldBe promoted.id
                    entry(second.id).status shouldBe "Waiting"
                    entry(third.id).status shouldBe "Waiting"
                    appointment(promoted.id).status shouldBe "Booked"
                }
            }

            test("a blocked first entry and a first entry whose patient already has an appointment that day are skipped") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val booked = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val blockedPatient = createPatient("Blocked")
                    val blockedEntry = joinOk(practitioner.id, blockedPatient.id)
                    clock.advance(Duration.ofSeconds(1))
                    val busyPatient = createPatient("Busy")
                    bookOk(practitioner.id, busyPatient.id, at(tuesday, LocalTime.of(14, 0)))
                    val busyEntry = joinOk(practitioner.id, busyPatient.id)
                    clock.advance(Duration.ofSeconds(1))
                    val luckyEntry = joinOk(practitioner.id, createPatient("Lucky").id)

                    // Block the first patient via three no-shows on an older practitioner day.
                    val past = createPractitioner()
                    val missed = listOf(LocalDate.of(2027, 1, 5), LocalDate.of(2027, 1, 6), LocalDate.of(2027, 1, 7))
                    clock.set(at(LocalDate.of(2027, 1, 4), LocalTime.of(9, 0)))
                    val missedAppointments = missed.map { bookOk(past.id, blockedPatient.id, at(it, ten)) }
                    clock.set(at(LocalDate.of(2027, 1, 11), LocalTime.of(9, 5)))
                    missedAppointments.forEach { post("/appointments/${it.id}/no-show", staff).status shouldBe HttpStatusCode.OK }
                    get("/patients/${blockedPatient.id}", staff).body<PatientResponse>().blocked shouldBe true

                    val promoted =
                        post(
                            "/appointments/${booked.id}/cancel",
                            staff,
                        ).body<CancellationResponse>().waitlistPromotion.shouldNotBeNull()
                    promoted.patientId shouldBe luckyEntry.patientId
                    entry(blockedEntry.id).status shouldBe "Waiting"
                    entry(busyEntry.id).status shouldBe "Waiting"
                    entry(luckyEntry.id).status shouldBe "Fulfilled"
                }
            }

            test("a freed follow-up slot is too short for a waitlisted procedure: it is skipped and the next fitting entry is promoted") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val freed = bookOk(practitioner.id, createPatient().id, at(tuesday, ten), type = "FollowUp") // 10:00-10:15
                    // 10:15-10:30 is taken, so nothing longer than a follow-up fits into the freed 10:00 slot
                    bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(10, 15)), type = "FollowUp")
                    val procedure = joinOk(practitioner.id, createPatient("Procedure").id, type = "Procedure")
                    clock.advance(Duration.ofSeconds(1))
                    val followUp = joinOk(practitioner.id, createPatient("FollowUp").id, type = "FollowUp")

                    val promoted =
                        post(
                            "/appointments/${freed.id}/cancel",
                            staff,
                        ).body<CancellationResponse>().waitlistPromotion.shouldNotBeNull()
                    promoted.patientId shouldBe followUp.patientId
                    promoted.type shouldBe "FollowUp"
                    entry(procedure.id).status shouldBe "Waiting"
                    entry(followUp.id).status shouldBe "Fulfilled"
                }
            }

            test("expired entries are never promoted (clinic cancels a past appointment)") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val booked = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val stale = joinOk(practitioner.id, createPatient().id)
                    clock.set(at(tuesday.plusDays(3), ten))
                    val result = post("/appointments/${booked.id}/cancel", staff).body<CancellationResponse>()
                    result.waitlistPromotion.shouldBeNull()
                    entry(stale.id).status shouldBe "Expired"
                }
            }

            test("nothing waiting -> cancellation without promotion; a reschedule also promotes into the freed slot") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val a = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    post("/appointments/${a.id}/cancel", staff).body<CancellationResponse>().waitlistPromotion.shouldBeNull()

                    val b = bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(11, 0)))
                    val waiting = joinOk(practitioner.id, createPatient().id)
                    val moved =
                        post(
                            "/appointments/${b.id}/reschedule",
                            staff,
                            RescheduleRequest(iso(at(tuesday, LocalTime.of(15, 0)))),
                        ).body<RescheduleResponse>()
                    moved.waitlistPromotion.shouldNotBeNull().start shouldBe b.start
                    entry(waiting.id).status shouldBe "Fulfilled"
                }
            }

            test("promotion runs in the cancel transaction: both the cancellation and the promoted booking are visible together") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val booked = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val waiting = joinOk(practitioner.id, createPatient().id)
                    val result = post("/appointments/${booked.id}/cancel", staff).body<CancellationResponse>()
                    val promoted = result.waitlistPromotion.shouldNotBeNull()
                    get(
                        "/patients/${waiting.patientId}/appointments",
                        staff,
                    ).body<PageResponse<AppointmentResponse>>().items.single().id shouldBe
                        promoted.id
                    appointment(booked.id).status shouldBe "Cancelled"
                }
            }

            test("if promotion itself throws, the cancellation is still committed (documented decision)") {
                val broken =
                    object : WaitlistRepository {
                        override fun save(entry: WaitlistEntry) = error("waitlist storage unavailable")

                        override fun findById(id: WaitlistEntryId): WaitlistEntry? = null

                        override fun forPractitionerAndDate(
                            practitionerId: PractitionerId,
                            date: LocalDate,
                        ): List<WaitlistEntry> = error("waitlist storage unavailable")
                    }
                clinicApp(repositories = Repositories(waitlist = broken)) {
                    val practitioner = createPractitioner()
                    val booked = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                    val result = post("/appointments/${booked.id}/cancel", staff)
                    result.status shouldBe HttpStatusCode.OK
                    result.body<CancellationResponse>().waitlistPromotion.shouldBeNull()
                    appointment(booked.id).status shouldBe "Cancelled"
                }
            }
        }
    })
