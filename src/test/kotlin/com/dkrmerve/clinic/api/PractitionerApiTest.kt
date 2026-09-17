package com.dkrmerve.clinic.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import java.time.LocalTime

class PractitionerApiTest :
    FunSpec({
        val tuesday = LocalDate.of(2027, 1, 12)

        context("POST /practitioners") {
            test("creates a practitioner -> 201 with Location header, then GET returns the same representation") {
                clinicApp {
                    val request = CreatePractitionerRequest("  Dr. Ada Lovelace ", "Cardiology", 15, 10, 12, WEEKDAYS_9_TO_5)
                    val response = post("/practitioners", admin, request)
                    response.status shouldBe HttpStatusCode.Created
                    val created = response.body<PractitionerResponse>()
                    response.headers[HttpHeaders.Location] shouldBe "/practitioners/${created.id}"
                    created.name shouldBe "Dr. Ada Lovelace"
                    created.schedule.keys shouldBe setOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")
                    created.schedule["MONDAY"] shouldBe WorkingWindowDto("09:00", "17:00")
                    get("/practitioners/${created.id}", staff).body<PractitionerResponse>() shouldBe created
                }
            }

            test("collects every field error in one 400 validation_failed response") {
                clinicApp {
                    val bad =
                        CreatePractitionerRequest(
                            name = " ",
                            specialty = "",
                            slotMinutes = 7,
                            bufferMinutes = -5,
                            maxAppointmentsPerDay = 0,
                            schedule =
                                mapOf(
                                    "FUNDAY" to WorkingWindowDto("09:00", "17:00"),
                                    "MONDAY" to WorkingWindowDto("17:00", "09:00"),
                                    "TUESDAY" to WorkingWindowDto("nine", "17:00"),
                                ),
                        )
                    val problem = post("/practitioners", admin, bad).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    problem.errors!!.shouldContainKeys(
                        "name",
                        "specialty",
                        "slotMinutes",
                        "bufferMinutes",
                        "maxAppointmentsPerDay",
                        "schedule.FUNDAY",
                        "schedule.MONDAY",
                        "schedule.TUESDAY.start",
                    )
                    problem.errors!!["slotMinutes"] shouldContain "10, 15, 20, 30"
                }
            }

            test("an empty schedule is a field error") {
                clinicApp {
                    val problem =
                        post("/practitioners", admin, CreatePractitionerRequest("Dr", "GP", 15, 0, 5, emptyMap()))
                            .shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    problem.errors!!.keys shouldBe setOf("schedule")
                }
            }

            test("slotMinutes 30 is in the allowed set but cannot fit a 15-minute follow-up -> 400 slot_incompatible") {
                clinicApp {
                    post("/practitioners", admin, CreatePractitionerRequest("Dr", "GP", 30, 0, 5, WEEKDAYS_9_TO_5))
                        .shouldBeProblem(HttpStatusCode.BadRequest, "slot_incompatible")
                }
            }

            test("a working window shorter than one slot -> 400 invalid_schedule") {
                clinicApp {
                    post(
                        "/practitioners",
                        admin,
                        CreatePractitionerRequest(
                            "Dr",
                            "GP",
                            15,
                            0,
                            5,
                            mapOf("MONDAY" to WorkingWindowDto("09:00", "09:10")),
                        ),
                    ).shouldBeProblem(HttpStatusCode.BadRequest, "invalid_schedule")
                }
            }
        }

        context("GET /practitioners/{id}") {
            test("unknown id -> 404 practitioner_not_found; non-UUID id -> 404 as well") {
                clinicApp {
                    get(
                        "/practitioners/${java.util.UUID.randomUUID()}",
                        staff,
                    ).shouldBeProblem(HttpStatusCode.NotFound, "practitioner_not_found")
                    get("/practitioners/not-a-uuid", staff).shouldBeProblem(HttpStatusCode.NotFound, "practitioner_not_found")
                }
            }
        }

        context("POST /practitioners/{id}/time-off") {
            test("creates a block -> 201 with Location of the practitioner, and availability honours it") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val from = at(tuesday, LocalTime.of(12, 0))
                    val to = at(tuesday, LocalTime.of(13, 0))
                    val response = post("/practitioners/${practitioner.id}/time-off", staff, TimeOffRequest(iso(from), iso(to), "Lunch"))
                    response.status shouldBe HttpStatusCode.Created
                    response.headers[HttpHeaders.Location] shouldBe "/practitioners/${practitioner.id}"
                    val block = response.body<TimeOffResponse>()
                    block.reason shouldBe "Lunch"
                    block.from shouldBe "2027-01-12T12:00:00+01:00"
                    val slots =
                        get(
                            "/practitioners/${practitioner.id}/availability?date=$tuesday&type=Consultation",
                            staff,
                        ).body<AvailabilityResponse>().slots
                    slots shouldNotContain "2027-01-12T12:00:00+01:00"
                    slots shouldNotContain "2027-01-12T11:45:00+01:00"
                    slots shouldContain "2027-01-12T11:30:00+01:00"
                    slots shouldContain "2027-01-12T13:00:00+01:00"
                }
            }

            test("validation: from >= to, missing offset and blank reason are all reported") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val problem =
                        post(
                            "/practitioners/${practitioner.id}/time-off",
                            staff,
                            TimeOffRequest("2027-01-12T13:00:00+01:00", "2027-01-12T12:00", " "),
                        ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    problem.errors!!.keys shouldBe setOf("to", "reason")
                    val ordered =
                        post(
                            "/practitioners/${practitioner.id}/time-off",
                            staff,
                            TimeOffRequest("2027-01-12T13:00:00+01:00", "2027-01-12T12:00:00+01:00", "x"),
                        ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    ordered.errors!!["to"] shouldContain "after from"
                }
            }

            test("unknown practitioner -> 404") {
                clinicApp {
                    post(
                        "/practitioners/${java.util.UUID.randomUUID()}/time-off",
                        staff,
                        TimeOffRequest("2027-01-12T12:00:00+01:00", "2027-01-12T13:00:00+01:00", "x"),
                    ).shouldBeProblem(HttpStatusCode.NotFound, "practitioner_not_found")
                }
            }
        }

        context("GET /practitioners/{id}/availability") {
            test("a free Tuesday offers 31 consultation slots in clinic-zone ISO format") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val body =
                        get(
                            "/practitioners/${practitioner.id}/availability?date=$tuesday&type=consultation",
                            staff,
                        ).body<AvailabilityResponse>()
                    body.type shouldBe "Consultation"
                    body.durationMinutes shouldBe 30
                    body.slots shouldHaveSize 31
                    body.slots.first() shouldBe "2027-01-12T09:00:00+01:00"
                    body.slots.last() shouldBe "2027-01-12T16:30:00+01:00"
                }
            }

            test("slots that would end after the window are filtered (procedures stop at 16:00)") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val body =
                        get(
                            "/practitioners/${practitioner.id}/availability?date=$tuesday&type=Procedure",
                            staff,
                        ).body<AvailabilityResponse>()
                    body.slots.last() shouldBe "2027-01-12T16:00:00+01:00"
                }
            }

            test("existing appointments and their buffer disappear from availability") {
                clinicApp {
                    val practitioner = createPractitioner(bufferMinutes = 10)
                    bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(10, 0)))
                    val slots =
                        get(
                            "/practitioners/${practitioner.id}/availability?date=$tuesday&type=FollowUp",
                            staff,
                        ).body<AvailabilityResponse>().slots
                    listOf("09:45", "10:00", "10:15", "10:30").forEach { slots shouldNotContain "2027-01-12T$it:00+01:00" }
                    slots shouldContain "2027-01-12T09:30:00+01:00" // ends 09:45, before the 09:50 buffer start
                    slots shouldContain "2027-01-12T10:45:00+01:00" // exactly end + buffer
                }
            }

            test("a full-day time-off block empties the day") {
                clinicApp {
                    val practitioner = createPractitioner()
                    post(
                        "/practitioners/${practitioner.id}/time-off",
                        staff,
                        TimeOffRequest("2027-01-12T00:00:00+01:00", "2027-01-13T00:00:00+01:00", "Conference"),
                    )
                    get(
                        "/practitioners/${practitioner.id}/availability?date=$tuesday&type=Consultation",
                        staff,
                    ).body<AvailabilityResponse>().slots.shouldBeEmpty()
                }
            }

            test("once maxAppointmentsPerDay is reached the day is empty") {
                clinicApp {
                    val practitioner = createPractitioner(maxAppointmentsPerDay = 1)
                    bookOk(practitioner.id, createPatient().id, at(tuesday, LocalTime.of(9, 0)))
                    get(
                        "/practitioners/${practitioner.id}/availability?date=$tuesday&type=Consultation",
                        staff,
                    ).body<AvailabilityResponse>().slots.shouldBeEmpty()
                }
            }

            test("a day off (Saturday) has no slots") {
                clinicApp {
                    val practitioner = createPractitioner()
                    get(
                        "/practitioners/${practitioner.id}/availability?date=2027-01-16&type=Consultation",
                        staff,
                    ).body<AvailabilityResponse>().slots.shouldBeEmpty()
                }
            }

            test("date in the past -> 422 outside_booking_horizon") {
                clinicApp {
                    val practitioner = createPractitioner()
                    get("/practitioners/${practitioner.id}/availability?date=2027-01-08&type=Consultation", staff)
                        .shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_booking_horizon")
                }
            }

            test("date beyond 60 days -> 422 outside_booking_horizon, exactly 60 days ahead is fine") {
                clinicApp {
                    val practitioner = createPractitioner()
                    get("/practitioners/${practitioner.id}/availability?date=2027-03-13&type=Consultation", staff)
                        .shouldBeProblem(HttpStatusCode.UnprocessableEntity, "outside_booking_horizon")
                    get("/practitioners/${practitioner.id}/availability?date=2027-03-12&type=Consultation", staff).status shouldBe
                        HttpStatusCode.OK
                }
            }

            test("invalid date string, missing type and unknown type -> 400 validation_failed with the allowed values") {
                clinicApp {
                    val practitioner = createPractitioner()
                    get("/practitioners/${practitioner.id}/availability?date=12-01-2027&type=Consultation", staff)
                        .shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                        .errors!!
                        .keys shouldBe setOf("date")
                    get("/practitioners/${practitioner.id}/availability?date=$tuesday", staff)
                        .shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                        .errors!!
                        .keys shouldBe setOf("type")
                    val unknown =
                        get("/practitioners/${practitioner.id}/availability?date=$tuesday&type=Massage", staff)
                            .shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    unknown.errors!!["type"] shouldContain "Consultation, FollowUp, Procedure"
                }
            }

            test("unknown practitioner -> 404") {
                clinicApp {
                    get("/practitioners/${java.util.UUID.randomUUID()}/availability?date=$tuesday&type=Consultation", staff)
                        .shouldBeProblem(HttpStatusCode.NotFound, "practitioner_not_found")
                }
            }
        }
    })
