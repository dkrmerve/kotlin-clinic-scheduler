package com.dkrmerve.clinic.api

import com.dkrmerve.clinic.domain.ConcurrencyException
import com.dkrmerve.clinic.domain.ConflictException
import com.dkrmerve.clinic.domain.DomainException
import com.dkrmerve.clinic.domain.ForbiddenException
import com.dkrmerve.clinic.domain.InvalidTransitionException
import com.dkrmerve.clinic.domain.NotFoundException
import com.dkrmerve.clinic.domain.PatientBlockedException
import com.dkrmerve.clinic.domain.RuleViolationException
import com.dkrmerve.clinic.domain.UnauthenticatedException
import com.dkrmerve.clinic.domain.ValidationException
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import java.time.Instant

/**
 * The single exception-to-status mapping (Problems.kt) and the StatusPages handlers for framework failures.
 * One case per DomainException subtype: the `when` in httpStatus() is exhaustive, so this table is the
 * runtime counterpart of the compile-time check.
 */
class ProblemMappingTest :
    FunSpec({

        context("DomainException -> HTTP status") {
            data class Case(
                val exception: DomainException,
                val status: HttpStatusCode,
            )
            withData(
                nameFn = { "${it.exception::class.simpleName}(${it.exception.code}) -> ${it.status.value}" },
                Case(ValidationException("invalid_thing", "x"), HttpStatusCode.BadRequest),
                Case(UnauthenticatedException("x"), HttpStatusCode.Unauthorized),
                Case(ForbiddenException.forbiddenRole("x"), HttpStatusCode.Forbidden),
                Case(ForbiddenException.notOwner("x"), HttpStatusCode.Forbidden),
                Case(PatientBlockedException(Instant.EPOCH), HttpStatusCode.Forbidden),
                Case(NotFoundException("Patient", "1"), HttpStatusCode.NotFound),
                Case(InvalidTransitionException("Booked", "Completed"), HttpStatusCode.Conflict),
                Case(ConflictException.slotTaken("x"), HttpStatusCode.Conflict),
                Case(ConflictException.patientConflict("x"), HttpStatusCode.Conflict),
                Case(ConflictException.dailyCapacityReached("x"), HttpStatusCode.Conflict),
                Case(ConflictException.waitlistDuplicate("x"), HttpStatusCode.Conflict),
                Case(ConflictException.noShowBeforeStart("x"), HttpStatusCode.Conflict),
                Case(ConcurrencyException("x"), HttpStatusCode.Conflict),
                Case(RuleViolationException.outsideWorkingHours("x"), HttpStatusCode.UnprocessableEntity),
                Case(RuleViolationException.slotMisaligned("x"), HttpStatusCode.UnprocessableEntity),
                Case(RuleViolationException.practitionerUnavailable("x"), HttpStatusCode.UnprocessableEntity),
                Case(RuleViolationException.outsideBookingHorizon("x"), HttpStatusCode.UnprocessableEntity),
                Case(RuleViolationException.cancellationWindowClosed("x"), HttpStatusCode.UnprocessableEntity),
            ) { case ->
                case.exception.httpStatus() shouldBe case.status
            }
        }

        context("StatusPages renders problem+json for every failure class") {
            val boom = RuntimeException("secret internal detail")

            test("an unhandled exception -> 500 internal_error without leaking the message or a stack trace") {
                clinicApp(extraRoutes = { get("/boom") { throw boom } }) {
                    val response = client.get("/boom")
                    val problem = response.shouldBeProblem(HttpStatusCode.InternalServerError, "internal_error")
                    problem.detail shouldNotContain "secret"
                    response.bodyAsText() shouldNotContain "at com.dkrmerve"
                }
            }

            test("a domain exception thrown from any route is mapped, with blockedUntil rendered in the clinic zone") {
                clinicApp(extraRoutes = { get("/blocked") { throw PatientBlockedException(TEST_NOW) } }) {
                    val problem = client.get("/blocked").shouldBeProblem(HttpStatusCode.Forbidden, "patient_blocked")
                    problem.blockedUntil shouldBe "2027-01-11T09:00:00+01:00"
                }
            }

            test("a unique-constraint SQL exception -> 409 conflict, other SQL exceptions -> 500") {
                clinicApp(
                    extraRoutes = { deps ->
                        get(
                            "/dup",
                        ) {
                            transaction(
                                deps.database.database,
                            ) { throw ExposedSQLException(SQLException("boom", "23505"), emptyList(), this) }
                        }
                        get(
                            "/other",
                        ) {
                            transaction(
                                deps.database.database,
                            ) { throw ExposedSQLException(SQLException("boom", "42P01"), emptyList(), this) }
                        }
                    },
                ) {
                    client.get("/dup").shouldBeProblem(HttpStatusCode.Conflict, "conflict")
                    client.get("/other").shouldBeProblem(HttpStatusCode.InternalServerError, "internal_error")
                }
            }

            test("malformed JSON body -> 400 malformed_request") {
                clinicApp {
                    postRaw("/patients", staff, "{ not json").shouldBeProblem(HttpStatusCode.BadRequest, "malformed_request")
                }
            }

            test("empty body on an endpoint that needs one -> 400 malformed_request, not 500") {
                clinicApp {
                    postRaw("/patients", staff, "").shouldBeProblem(HttpStatusCode.BadRequest, "malformed_request")
                }
            }

            test("wrong field types in JSON -> 400 malformed_request") {
                clinicApp {
                    postRaw(
                        "/practitioners",
                        admin,
                        """{"name":"Dr","slotMinutes":"fifteen"}""",
                    ).shouldBeProblem(HttpStatusCode.BadRequest, "malformed_request")
                }
            }

            test("a non-JSON content type -> 415 unsupported_media_type") {
                clinicApp {
                    postRaw(
                        "/patients",
                        staff,
                        "name=x",
                        ContentType.Text.Plain,
                    ).shouldBeProblem(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
                }
            }
        }

        context("database concurrency SQLSTATEs") {
            test("a deadlock (40P01) or serialization failure (40001) -> 409 concurrent_modification, retryable") {
                clinicApp(
                    extraRoutes = { deps ->
                        get("/deadlock") {
                            transaction(
                                deps.database.database,
                            ) { throw ExposedSQLException(SQLException("deadlock detected", "40P01"), emptyList(), this) }
                        }
                        get("/serialization") {
                            transaction(
                                deps.database.database,
                            ) { throw ExposedSQLException(SQLException("could not serialize", "40001"), emptyList(), this) }
                        }
                    },
                ) {
                    client.get("/deadlock").shouldBeProblem(HttpStatusCode.Conflict, "concurrent_modification")
                    client.get("/serialization").shouldBeProblem(HttpStatusCode.Conflict, "concurrent_modification")
                }
            }
        }
    })
