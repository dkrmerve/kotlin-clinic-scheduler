package com.dkrmerve.clinic.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import java.time.LocalTime

class PatientApiTest :
    FunSpec({
        val tuesday = LocalDate.of(2027, 1, 12)

        context("POST /patients and GET /patients/{id}") {
            test("creates a patient -> 201 with Location; name is trimmed and email lower-cased; blocked is derived") {
                clinicApp {
                    val response = post("/patients", staff, CreatePatientRequest("  Grace Hopper ", "Grace.Hopper@Example.TEST"))
                    response.status shouldBe HttpStatusCode.Created
                    val created = response.body<PatientResponse>()
                    response.headers[HttpHeaders.Location] shouldBe "/patients/${created.id}"
                    created.name shouldBe "Grace Hopper"
                    created.email shouldBe "grace.hopper@example.test"
                    created.blocked shouldBe false
                    created.blockedUntil shouldBe null
                    created.noShows shouldHaveSize 0
                    get("/patients/${created.id}", staff).body<PatientResponse>() shouldBe created
                }
            }

            test("blank name and malformed email are reported together -> 400 validation_failed") {
                clinicApp {
                    val problem =
                        post(
                            "/patients",
                            staff,
                            CreatePatientRequest("   ", "not-an-email"),
                        ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed")
                    problem.errors!!.keys shouldBe setOf("name", "email")
                }
            }

            test("unknown id -> 404 patient_not_found; non-UUID id -> 404") {
                clinicApp {
                    get("/patients/${java.util.UUID.randomUUID()}", staff).shouldBeProblem(HttpStatusCode.NotFound, "patient_not_found")
                    get("/patients/42", staff).shouldBeProblem(HttpStatusCode.NotFound, "patient_not_found")
                }
            }
        }

        context("GET /patients/{id}/appointments with pagination") {
            test("pages newest-first with page/pageSize and reports the total") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val starts = listOf(LocalTime.of(9, 0), LocalTime.of(11, 0), LocalTime.of(14, 0)).map { at(tuesday, it) }
                    val other = createPractitioner()
                    bookOk(practitioner.id, patient.id, starts[0])
                    bookOk(other.id, patient.id, starts[1])
                    bookOk(createPractitioner().id, patient.id, starts[2])

                    val page1 =
                        get(
                            "/patients/${patient.id}/appointments?page=1&pageSize=2",
                            staff,
                        ).body<PageResponse<AppointmentResponse>>()
                    page1.total shouldBe 3
                    page1.page shouldBe 1
                    page1.pageSize shouldBe 2
                    page1.items.map { it.start } shouldBe listOf(iso(starts[2]), iso(starts[1]))

                    val page2 =
                        get(
                            "/patients/${patient.id}/appointments?page=2&pageSize=2",
                            staff,
                        ).body<PageResponse<AppointmentResponse>>()
                    page2.items.map { it.start } shouldBe listOf(iso(starts[0]))

                    val defaults = get("/patients/${patient.id}/appointments", staff).body<PageResponse<AppointmentResponse>>()
                    defaults.page shouldBe 1
                    defaults.pageSize shouldBe 20
                    defaults.items shouldHaveSize 3
                }
            }

            test("page < 1, pageSize > 100 or non-numeric values -> 400 validation_failed") {
                clinicApp {
                    val patient = createPatient()
                    get(
                        "/patients/${patient.id}/appointments?page=0",
                        staff,
                    ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed").errors!!.keys shouldBe
                        setOf("page")
                    get(
                        "/patients/${patient.id}/appointments?pageSize=101",
                        staff,
                    ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed").errors!!.keys shouldBe
                        setOf("pageSize")
                    get(
                        "/patients/${patient.id}/appointments?page=abc&pageSize=0",
                        staff,
                    ).shouldBeProblem(HttpStatusCode.BadRequest, "validation_failed").errors!!.keys shouldBe
                        setOf("page", "pageSize")
                    get("/patients/${patient.id}/appointments?pageSize=100", staff).status shouldBe HttpStatusCode.OK
                }
            }

            test("unknown patient -> 404") {
                clinicApp {
                    get(
                        "/patients/${java.util.UUID.randomUUID()}/appointments",
                        staff,
                    ).shouldBeProblem(HttpStatusCode.NotFound, "patient_not_found")
                }
            }
        }
    })
