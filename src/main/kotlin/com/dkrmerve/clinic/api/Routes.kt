package com.dkrmerve.clinic.api

import com.dkrmerve.clinic.Dependencies
import com.dkrmerve.clinic.application.PageRequest
import com.dkrmerve.clinic.domain.AppointmentId
import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.NotFoundException
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.WaitlistEntryId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

val WRITES_RATE_LIMIT = RateLimitName("writes")

/** Anonymous endpoints: liveness, readiness (real SELECT 1 through the pool) and Prometheus metrics. */
fun Route.operationsRoutes(deps: Dependencies) {
    get("/health/live") { call.respond(HealthResponse("UP")) }
    get("/health/ready") { call.respondReadiness(deps) }
    get("/health") { call.respondReadiness(deps) }
    get("/metrics") { call.respondText(deps.metrics.scrape(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8")) }
}

private suspend fun ApplicationCall.respondReadiness(deps: Dependencies) {
    val databaseUp = withContext(Dispatchers.IO) { deps.database.ping() }
    if (databaseUp) {
        respond(HealthResponse("UP", database = "UP"))
    } else {
        respond(HttpStatusCode.ServiceUnavailable, HealthResponse("DOWN", database = "DOWN"))
    }
}

/** Development-only token minting; the route does not exist at all unless AUTH_DEV_ISSUER_ENABLED=true. */
fun Route.devTokenRoutes(issuer: DevTokenIssuer) {
    rateLimit(WRITES_RATE_LIMIT) {
        post("/auth/token") {
            val request = call.receive<TokenRequest>()
            val role =
                Validator.validate {
                    check("subject", request.subject.isNotBlank()) { "must not be blank" }
                    parse("role", request.role, "must be one of ${Role.entries.joinToString { it.wire }}", Role::fromWire)
                }
            call.respond(TokenResponse(issuer.issue(request.subject, role!!), expiresInSeconds = 3600))
        }
    }
}

fun Route.schedulingRoutes(deps: Dependencies) {
    authenticate(AUTH_SCHEME) {
        practitionerRoutes(deps)
        patientRoutes(deps)
        appointmentRoutes(deps)
        waitlistRoutes(deps)
    }
}

private fun Route.practitionerRoutes(deps: Dependencies) =
    route("/practitioners") {
        val time = deps.time
        rateLimit(WRITES_RATE_LIMIT) {
            post {
                call.caller().requireRole(Role.Admin)
                val practitioner = deps.practitioners.create(call.receive<CreatePractitionerRequest>().toCommand())
                call.created("/practitioners/${practitioner.id}", practitioner.toResponse())
            }
            post("/{id}/time-off") {
                call.caller().requireRole(Role.ClinicStaff, Role.Admin)
                val id = PractitionerId(call.pathId("id", "Practitioner"))
                val block = deps.practitioners.addTimeOff(call.receive<TimeOffRequest>().toCommand(id, time))
                call.created("/practitioners/$id", block.toResponse(time))
            }
        }
        get("/{id}") {
            call.caller()
            call.respond(deps.practitioners.get(PractitionerId(call.pathId("id", "Practitioner"))).toResponse())
        }
        get("/{id}/availability") {
            call.caller()
            val id = PractitionerId(call.pathId("id", "Practitioner"))
            val (date, type) =
                Validator.validate {
                    val date =
                        parse(
                            "date",
                            call.request.queryParameters["date"],
                            "query parameter is required, ISO-8601 date",
                            time::parseDateOrNull,
                        )
                    val type =
                        parse(
                            "type",
                            call.request.queryParameters["type"],
                            "query parameter is required, one of ${allowedValues<AppointmentType>()}",
                        ) {
                            enumOrNull<AppointmentType>(it)
                        }
                    throwIfInvalid()
                    date!! to type!!
                }
            val slots = deps.practitioners.availability(id, date, type)
            call.respond(AvailabilityResponse(id.toString(), date.toString(), type.name, type.minutes, slots.map(time::format)))
        }
    }

private fun Route.patientRoutes(deps: Dependencies) =
    route("/patients") {
        val time = deps.time
        rateLimit(WRITES_RATE_LIMIT) {
            post {
                call.caller().requireRole(Role.ClinicStaff, Role.Admin)
                val patient = deps.patients.create(call.receive<CreatePatientRequest>().toCommand())
                call.created("/patients/${patient.id}", patient.toResponse(time, deps.rules.now()))
            }
        }
        get("/{id}") {
            val id = PatientId(call.pathId("id", "Patient"))
            call.caller().requireOwnerOrStaff(id)
            call.respond(deps.patients.get(id).toResponse(time, deps.rules.now()))
        }
        get("/{id}/appointments") {
            val id = PatientId(call.pathId("id", "Patient"))
            call.caller().requireOwnerOrStaff(id)
            val page = deps.patients.appointments(id, call.pageRequest())
            call.respond(page.toResponse { it.toResponse(time) })
        }
    }

private fun Route.appointmentRoutes(deps: Dependencies) =
    route("/appointments") {
        val time = deps.time
        val service = deps.appointments
        rateLimit(WRITES_RATE_LIMIT) {
            post {
                val caller = call.caller()
                val cmd = call.receive<BookAppointmentRequest>().toCommand(caller.actor, time)
                caller.requireOwnerOrStaff(cmd.patientId)
                val appointment = service.book(cmd)
                call.created("/appointments/${appointment.id}", appointment.toResponse(time))
            }
            post("/{id}/cancel") {
                val caller = call.caller()
                val id = AppointmentId(call.pathId("id", "Appointment"))
                caller.requireOwnerOrStaff(service.get(id).patientId)
                val note = call.receiveOrNull<CancelRequest>()?.validated()
                val result = service.cancel(id, caller.actor, note)
                call.respond(CancellationResponse(result.cancelled.toResponse(time), result.promoted?.toResponse(time)))
            }
            post("/{id}/reschedule") {
                val caller = call.caller()
                val id = AppointmentId(call.pathId("id", "Appointment"))
                caller.requireOwnerOrStaff(service.get(id).patientId)
                val result = service.reschedule(call.receive<RescheduleRequest>().toCommand(id, caller.actor, time))
                call.respond(
                    RescheduleResponse(
                        noOp = result.noOp,
                        previous = result.previous.toResponse(time),
                        replacement = result.replacement.toResponse(time),
                        waitlistPromotion = result.promoted?.toResponse(time),
                    ),
                )
            }
            post("/{id}/check-in") {
                val caller = call.caller().also { it.requireRole(Role.ClinicStaff, Role.Admin) }
                call.respond(service.checkIn(AppointmentId(call.pathId("id", "Appointment")), caller.actor).toResponse(time))
            }
            post("/{id}/complete") {
                val caller = call.caller().also { it.requireRole(Role.ClinicStaff, Role.Admin) }
                call.respond(service.complete(AppointmentId(call.pathId("id", "Appointment")), caller.actor).toResponse(time))
            }
            post("/{id}/no-show") {
                val caller = call.caller().also { it.requireRole(Role.ClinicStaff, Role.Admin) }
                call.respond(service.markNoShow(AppointmentId(call.pathId("id", "Appointment")), caller.actor).toResponse(time))
            }
        }
        get("/{id}") {
            val appointment = service.get(AppointmentId(call.pathId("id", "Appointment")))
            call.caller().requireOwnerOrStaff(appointment.patientId)
            call.respond(appointment.toResponse(time))
        }
    }

private fun Route.waitlistRoutes(deps: Dependencies) =
    route("/waitlist") {
        val time = deps.time
        rateLimit(WRITES_RATE_LIMIT) {
            post {
                val caller = call.caller()
                val cmd = call.receive<JoinWaitlistRequest>().toCommand(time)
                caller.requireOwnerOrStaff(cmd.patientId)
                val entry = deps.waitlist.join(cmd)
                call.created("/waitlist/${entry.id}", entry.toResponse(time))
            }
        }
        get {
            call.caller().requireRole(Role.ClinicStaff, Role.Admin)
            val (practitionerId, date) =
                Validator.validate {
                    val practitioner =
                        parse(
                            "practitionerId",
                            call.request.queryParameters["practitionerId"],
                            "query parameter is required, UUID",
                            ::uuidOrNull,
                        )
                    val date =
                        parse(
                            "date",
                            call.request.queryParameters["date"],
                            "query parameter is required, ISO-8601 date",
                            time::parseDateOrNull,
                        )
                    throwIfInvalid()
                    PractitionerId(practitioner!!) to date!!
                }
            val page = deps.waitlist.list(practitionerId, date, call.pageRequest())
            call.respond(page.toResponse { it.toResponse(time) })
        }
        get("/{id}") {
            val entry = deps.waitlist.get(WaitlistEntryId(call.pathId("id", "Waitlist entry")))
            call.caller().requireOwnerOrStaff(entry.patientId)
            call.respond(entry.toResponse(time))
        }
    }

/** A path id that is not a UUID cannot name anything, so it is a 404 rather than a 400. */
private fun ApplicationCall.pathId(
    name: String,
    entity: String,
): UUID {
    val raw = parameters[name] ?: throw NotFoundException(entity, "?")
    return uuidOrNull(raw) ?: throw NotFoundException(entity, raw)
}

private fun ApplicationCall.pageRequest(): PageRequest =
    Validator.validate {
        val page =
            parse("page", request.queryParameters["page"] ?: "1", "must be an integer >= 1") {
                it.toIntOrNull()?.takeIf { p ->
                    p >= 1
                }
            }
        val pageSize =
            parse("pageSize", request.queryParameters["pageSize"] ?: "20", "must be an integer between 1 and $MAX_PAGE_SIZE") {
                it.toIntOrNull()?.takeIf { size -> size in 1..MAX_PAGE_SIZE }
            }
        throwIfInvalid()
        PageRequest(page!!, pageSize!!)
    }

private const val MAX_PAGE_SIZE = 100

private suspend inline fun <reified T : Any> ApplicationCall.created(
    location: String,
    body: T,
) {
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode.Created, body)
}

/** Optional JSON body: absent or empty bodies yield null; present but malformed bodies are still a 400. */
private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? =
    if ((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) == 0L) null else receive<T>()
