package com.dkrmerve.clinic.api

import com.dkrmerve.clinic.TestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.LocalTime

/**
 * Real parallelism against real PostgreSQL (Testcontainers): N coroutines on Dispatchers.IO fire HTTP
 * requests through testApplication at the same moment (a shared gate releases them together).
 * H2 has no partial unique index and different lock semantics, so this suite only runs under `integrationTest`.
 */
class ConcurrencyTest :
    FunSpec({
        val tuesday = LocalDate.of(2027, 1, 12)
        val ten = LocalTime.of(10, 0)
        val postgresOnly = TestDatabases.isPostgres

        suspend fun fireTogether(
            count: Int,
            request: suspend (Int) -> HttpResponse,
        ): List<HttpResponse> =
            coroutineScope {
                val gate = CompletableDeferred<Unit>()
                val shots =
                    (0 until count).map { i ->
                        async(Dispatchers.IO) {
                            gate.await()
                            request(i)
                        }
                    }
                gate.complete(Unit)
                shots.awaitAll()
            }

        test(
            "10 parallel bookings for the same slot -> exactly one 201, the rest 409 slot_taken (practitioner row lock + partial unique index)",
        ).config(enabled = postgresOnly) {
            clinicApp {
                val practitioner = createPractitioner()
                val patients = (1..10).map { createPatient() }
                val responses = fireTogether(patients.size) { i -> book(practitioner.id, patients[i].id, at(tuesday, ten)) }
                responses.count { it.status == HttpStatusCode.Created } shouldBe 1
                responses.filter { it.status != HttpStatusCode.Created }.forEach {
                    it.shouldBeProblem(
                        HttpStatusCode.Conflict,
                        "slot_taken",
                    )
                }
                val slots =
                    get(
                        "/practitioners/${practitioner.id}/availability?date=$tuesday&type=Consultation",
                        staff,
                    ).body<AvailabilityResponse>().slots
                slots.count { it == iso(at(tuesday, ten)) } shouldBe 0
            }
        }

        test(
            "parallel bookings on a practitioner with maxAppointmentsPerDay = 1 -> exactly one succeeds (capacity is checked under the row lock)",
        ).config(enabled = postgresOnly) {
            clinicApp {
                val practitioner = createPractitioner(maxAppointmentsPerDay = 1)
                val patients = (1..6).map { createPatient() }
                val responses =
                    fireTogether(patients.size) { i -> book(practitioner.id, patients[i].id, at(tuesday, LocalTime.of(9 + i, 0))) }
                responses.count { it.status == HttpStatusCode.Created } shouldBe 1
                responses.filter { it.status != HttpStatusCode.Created }.forEach {
                    it.shouldBeProblem(
                        HttpStatusCode.Conflict,
                        "daily_capacity_reached",
                    )
                }
            }
        }

        test(
            "one patient booking four practitioners at the same time in parallel -> exactly one succeeds (patient row lock serialises rule 5)",
        ).config(enabled = postgresOnly) {
            clinicApp {
                val patient = createPatient()
                val practitioners = (1..4).map { createPractitioner() }
                val responses = fireTogether(practitioners.size) { i -> book(practitioners[i].id, patient.id, at(tuesday, ten)) }
                responses.count { it.status == HttpStatusCode.Created } shouldBe 1
                responses.filter { it.status != HttpStatusCode.Created }.forEach {
                    it.shouldBeProblem(
                        HttpStatusCode.Conflict,
                        "patient_conflict",
                    )
                }
            }
        }

        test(
            "two parallel cancellations of one appointment -> one 200 and one 409 (version column or state machine), history grows by exactly one",
        ).config(enabled = postgresOnly) {
            clinicApp {
                val practitioner = createPractitioner()
                val appointment = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                val responses = fireTogether(2) { post("/appointments/${appointment.id}/cancel", staff) }
                responses.map { it.status } shouldContainExactlyInAnyOrder listOf(HttpStatusCode.OK, HttpStatusCode.Conflict)
                responses.first { it.status == HttpStatusCode.Conflict }.problem().code shouldBeIn
                    listOf("concurrent_modification", "invalid_transition")
                appointment(appointment.id).history shouldHaveSize 2
            }
        }

        test(
            "parallel check-in and clinic cancel on one appointment -> either one loses with 409, or they serialise into Booked -> CheckedIn -> Cancelled; never a 5xx",
        ).config(enabled = postgresOnly) {
            clinicApp {
                val practitioner = createPractitioner()
                val appointment = bookOk(practitioner.id, createPatient().id, at(tuesday, ten))
                val responses =
                    fireTogether(2) { i ->
                        if (i ==
                            0
                        ) {
                            post("/appointments/${appointment.id}/check-in", staff)
                        } else {
                            post("/appointments/${appointment.id}/cancel", staff)
                        }
                    }
                responses.none { it.status.value >= 500 } shouldBe true
                val winners = responses.count { it.status == HttpStatusCode.OK }
                responses.filter { it.status != HttpStatusCode.OK }.forEach { it.status shouldBe HttpStatusCode.Conflict }
                val final = appointment(appointment.id)
                final.history shouldHaveSize 1 + winners
                if (winners == 2) {
                    // check-in committed first, then the clinic cancelled the checked-in appointment: a legal path (rule 10)
                    final.history.map { it.to } shouldBe listOf("Booked", "CheckedIn", "Cancelled")
                }
            }
        }

        test(
            "20x: a late-window reschedule racing a booking for the same patient and practitioner never deadlocks (no 500; locks taken in one order)",
        ).config(enabled = postgresOnly) {
            clinicApp {
                repeat(20) { round ->
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val start = at(tuesday.plusDays(7), ten)
                    val current = bookOk(practitioner.id, patient.id, start)
                    clock.set(start.minus(java.time.Duration.ofHours(3)))
                    val responses =
                        fireTogether(2) { i ->
                            if (i == 0) {
                                post(
                                    "/appointments/${current.id}/reschedule",
                                    patientToken(patient.id),
                                    RescheduleRequest(iso(at(tuesday.plusDays(7), LocalTime.of(14, 0)))),
                                )
                            } else {
                                book(practitioner.id, patient.id, at(tuesday.plusDays(7), LocalTime.of(12, 0)))
                            }
                        }
                    io.kotest.assertions.withClue("round $round: ${responses.map { it.status }}") {
                        responses.none { it.status.value >= 500 } shouldBe true
                        responses.count { it.status == HttpStatusCode.OK || it.status == HttpStatusCode.Created } shouldBe 1
                        responses.filter { it.status.value >= 400 }.forEach {
                            it.problem().code shouldBeIn
                                listOf("patient_conflict", "concurrent_modification")
                        }
                    }
                    val active =
                        get("/patients/${patient.id}/appointments", staff).body<PageResponse<AppointmentResponse>>().items.filter {
                            it.status ==
                                "Booked"
                        }
                    active shouldHaveSize 1
                }
            }
        }

        test("two no-shows for the same patient marked in parallel are both recorded (patient row lock, no lost update)")
            .config(enabled = postgresOnly) {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val a = bookOk(practitioner.id, patient.id, at(tuesday, ten))
                    val b = bookOk(practitioner.id, patient.id, at(tuesday.plusDays(1), ten))
                    clock.set(at(tuesday.plusDays(2), ten))
                    val responses = fireTogether(2) { i -> post("/appointments/${if (i == 0) a.id else b.id}/no-show", staff) }
                    responses.map { it.status } shouldBe listOf(HttpStatusCode.OK, HttpStatusCode.OK)
                    get("/patients/${patient.id}", staff).body<PatientResponse>().noShows shouldBe listOf(a.start, b.start)
                }
            }

        test(
            "5 parallel waitlist joins for the same patient, practitioner and date -> exactly one 201, the rest 409 waitlist_duplicate (patient lock + partial unique index)",
        ).config(enabled = postgresOnly) {
            clinicApp {
                val practitioner = createPractitioner()
                val patient = createPatient()
                val responses =
                    fireTogether(
                        5,
                    ) { post("/waitlist", staff, JoinWaitlistRequest(practitioner.id, patient.id, tuesday.toString(), "Consultation")) }
                responses.count { it.status == HttpStatusCode.Created } shouldBe 1
                responses.filter { it.status != HttpStatusCode.Created }.forEach {
                    it.shouldBeProblem(
                        HttpStatusCode.Conflict,
                        "waitlist_duplicate",
                    )
                }
                get("/waitlist?practitionerId=${practitioner.id}&date=$tuesday", staff).body<PageResponse<WaitlistEntryResponse>>().total shouldBe
                    1
            }
        }
    })
