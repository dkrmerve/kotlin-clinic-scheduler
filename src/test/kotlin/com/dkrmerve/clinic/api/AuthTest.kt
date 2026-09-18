package com.dkrmerve.clinic.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.LocalDate

/** JWT authentication (401) and role / ownership authorization (403). */
class AuthTest :
    FunSpec({
        val tuesday = LocalDate.of(2027, 1, 12)

        context("authentication -> 401 unauthenticated") {
            test("no token") {
                clinicApp {
                    val practitioner = createPractitioner()
                    client.get("/practitioners/${practitioner.id}").shouldBeProblem(HttpStatusCode.Unauthorized, "unauthenticated")
                }
            }

            test("expired token") {
                clinicApp {
                    val practitioner = createPractitioner()
                    get("/practitioners/${practitioner.id}", expiredToken()).shouldBeProblem(HttpStatusCode.Unauthorized, "unauthenticated")
                }
            }

            test("token signed with another key") {
                clinicApp {
                    val practitioner = createPractitioner()
                    get(
                        "/practitioners/${practitioner.id}",
                        foreignKeyToken(),
                    ).shouldBeProblem(HttpStatusCode.Unauthorized, "unauthenticated")
                }
            }

            test("token with an unknown role claim or a blank subject is rejected") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val issuer = DevTokenIssuer(TEST_SIGNING_KEY, TEST_ISSUER, Clock.systemUTC())
                    val unknownRole =
                        com.auth0.jwt.JWT
                            .create()
                            .withIssuer(TEST_ISSUER)
                            .withSubject("someone")
                            .withClaim(ROLE_CLAIM, "king")
                            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
                            .sign(
                                com.auth0.jwt.algorithms.Algorithm
                                    .HMAC256(TEST_SIGNING_KEY),
                            )
                    get("/practitioners/${practitioner.id}", unknownRole).shouldBeProblem(HttpStatusCode.Unauthorized, "unauthenticated")
                    get(
                        "/practitioners/${practitioner.id}",
                        issuer.issue(" ", Role.Admin),
                    ).shouldBeProblem(HttpStatusCode.Unauthorized, "unauthenticated")
                }
            }

            test("a malformed Authorization header") {
                clinicApp {
                    val practitioner = createPractitioner()
                    client
                        .get(
                            "/practitioners/${practitioner.id}",
                        ) { bearerAuth("not-a-jwt") }
                        .shouldBeProblem(HttpStatusCode.Unauthorized, "unauthenticated")
                }
            }
        }

        context("role authorization -> 403 forbidden_role") {
            test("POST /practitioners requires admin: staff and patients are refused") {
                clinicApp {
                    val body = CreatePractitionerRequest("Dr. Nope", "GP", 15, 0, 10, WEEKDAYS_9_TO_5)
                    post("/practitioners", staff, body).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    post("/practitioners", patientToken("p-1"), body).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    post("/practitioners", admin, body).status shouldBe HttpStatusCode.Created
                }
            }

            test("POST /patients, time-off, check-in, complete, no-show and GET /waitlist are staff-or-admin only") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val appointment = bookOk(practitioner.id, patient.id, at(tuesday, java.time.LocalTime.of(10, 0)))
                    val token = patientToken(patient.id)
                    post(
                        "/patients",
                        token,
                        CreatePatientRequest("X", "x@example.test"),
                    ).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    post(
                        "/practitioners/${practitioner.id}/time-off",
                        token,
                        TimeOffRequest("2027-02-01T09:00:00+01:00", "2027-02-01T10:00:00+01:00", "x"),
                    ).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    post("/appointments/${appointment.id}/check-in", token).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    post("/appointments/${appointment.id}/complete", token).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    post("/appointments/${appointment.id}/no-show", token).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    get(
                        "/waitlist?practitionerId=${practitioner.id}&date=$tuesday",
                        token,
                    ).shouldBeProblem(HttpStatusCode.Forbidden, "forbidden_role")
                    get("/waitlist?practitionerId=${practitioner.id}&date=$tuesday", admin).status shouldBe HttpStatusCode.OK
                }
            }
        }

        context("ownership -> 403 not_owner") {
            test("a patient can read and act on their own records but not on another patient's") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val alice = createPatient("Alice")
                    val bob = createPatient("Bob")
                    val aliceToken = patientToken(alice.id)
                    val bobsAppointment = bookOk(practitioner.id, bob.id, at(tuesday, java.time.LocalTime.of(10, 0)))

                    get("/patients/${alice.id}", aliceToken).status shouldBe HttpStatusCode.OK
                    get("/patients/${bob.id}", aliceToken).shouldBeProblem(HttpStatusCode.Forbidden, "not_owner")
                    get("/patients/${bob.id}/appointments", aliceToken).shouldBeProblem(HttpStatusCode.Forbidden, "not_owner")
                    get("/appointments/${bobsAppointment.id}", aliceToken).shouldBeProblem(HttpStatusCode.Forbidden, "not_owner")
                    post("/appointments/${bobsAppointment.id}/cancel", aliceToken).shouldBeProblem(HttpStatusCode.Forbidden, "not_owner")
                    post(
                        "/appointments/${bobsAppointment.id}/reschedule",
                        aliceToken,
                        RescheduleRequest(iso(at(tuesday, java.time.LocalTime.of(11, 0)))),
                    ).shouldBeProblem(HttpStatusCode.Forbidden, "not_owner")
                    book(practitioner.id, bob.id, at(tuesday, java.time.LocalTime.of(14, 0)), token = aliceToken)
                        .shouldBeProblem(HttpStatusCode.Forbidden, "not_owner")
                    post("/waitlist", aliceToken, JoinWaitlistRequest(practitioner.id, bob.id, tuesday.toString(), "FollowUp"))
                        .shouldBeProblem(HttpStatusCode.Forbidden, "not_owner")

                    val own = bookOk(practitioner.id, alice.id, at(tuesday, java.time.LocalTime.of(11, 0)), token = aliceToken)
                    get("/appointments/${own.id}", aliceToken).status shouldBe HttpStatusCode.OK
                    get("/patients/${alice.id}/appointments", aliceToken).status shouldBe HttpStatusCode.OK
                    post("/appointments/${own.id}/cancel", aliceToken).status shouldBe HttpStatusCode.OK
                }
            }

            test("staff and admin pass every ownership check") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val appointment = bookOk(practitioner.id, patient.id, at(tuesday, java.time.LocalTime.of(10, 0)))
                    get("/patients/${patient.id}", staff).status shouldBe HttpStatusCode.OK
                    get("/appointments/${appointment.id}", admin).status shouldBe HttpStatusCode.OK
                    get("/patients/${patient.id}/appointments", admin).status shouldBe HttpStatusCode.OK
                }
            }
        }

        context("actor derived from the role") {
            test("a staff cancellation is a clinic cancellation (never late), a patient cancellation follows the policy") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val patient = createPatient()
                    val soon = at(tuesday, java.time.LocalTime.of(10, 0))
                    val a = bookOk(practitioner.id, patient.id, soon)
                    val b = bookOk(practitioner.id, createPatient().id, at(tuesday, java.time.LocalTime.of(11, 0)))
                    clock.set(soon.minus(java.time.Duration.ofHours(3)))
                    val staffCancel = post("/appointments/${a.id}/cancel", staff)
                    staffCancel.status shouldBe HttpStatusCode.OK
                    appointment(a.id).cancelledBy shouldBe "clinic"
                    appointment(a.id).lateCancellation shouldBe false
                    val patientCancel = post("/appointments/${b.id}/cancel", patientToken(b.patientId))
                    patientCancel.status shouldBe HttpStatusCode.OK
                    appointment(b.id).cancelledBy shouldBe "patient"
                    appointment(b.id).lateCancellation shouldBe true
                }
            }
        }

        context("token lifetime") {
            test("a token without an exp claim is rejected with 401 (expiry is mandatory, not optional)") {
                clinicApp {
                    val practitioner = createPractitioner()
                    val eternal =
                        com.auth0.jwt.JWT
                            .create()
                            .withIssuer(TEST_ISSUER)
                            .withSubject("staff-1")
                            .withClaim(ROLE_CLAIM, "clinic_staff")
                            .sign(
                                com.auth0.jwt.algorithms.Algorithm
                                    .HMAC256(TEST_SIGNING_KEY),
                            )
                    get("/practitioners/${practitioner.id}", eternal).shouldBeProblem(HttpStatusCode.Unauthorized, "unauthenticated")
                }
            }
        }
    })
