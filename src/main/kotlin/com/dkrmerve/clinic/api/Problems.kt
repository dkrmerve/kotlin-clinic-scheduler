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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory

/** RFC 7807 problem details plus a stable machine-readable `code`; `errors` and `blockedUntil` appear when relevant. */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val code: String,
    val instance: String?,
    val correlationId: String? = null,
    val errors: Map<String, String>? = null,
    val blockedUntil: String? = null,
)

const val PROBLEM_TYPE_BASE = "https://github.com/dkrmerve/kotlin-clinic-scheduler/docs/errors#"

/**
 * The one exception-to-status mapping. Exhaustive over the sealed hierarchy: adding a DomainException
 * subtype without deciding its status is a compile error.
 */
fun DomainException.httpStatus(): HttpStatusCode =
    when (this) {
        is ValidationException -> HttpStatusCode.BadRequest
        is UnauthenticatedException -> HttpStatusCode.Unauthorized
        is ForbiddenException -> HttpStatusCode.Forbidden
        is PatientBlockedException -> HttpStatusCode.Forbidden
        is NotFoundException -> HttpStatusCode.NotFound
        is InvalidTransitionException -> HttpStatusCode.Conflict
        is ConflictException -> HttpStatusCode.Conflict
        is ConcurrencyException -> HttpStatusCode.Conflict
        is RuleViolationException -> HttpStatusCode.UnprocessableEntity
    }

suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    code: String,
    detail: String,
    errors: Map<String, String>? = null,
    blockedUntil: String? = null,
) {
    val problem =
        ProblemDetails(
            type = PROBLEM_TYPE_BASE + code,
            title = status.description,
            status = status.value,
            detail = detail,
            code = code,
            instance = request.path(),
            correlationId = callId,
            errors = errors,
            blockedUntil = blockedUntil,
        )
    respondText(ApiJson.encodeToString(problem), PROBLEM_JSON, status)
}

suspend fun ApplicationCall.respondProblem(
    exception: DomainException,
    time: ApiTime? = null,
) = respondProblem(
    status = exception.httpStatus(),
    code = exception.code,
    detail = exception.detail,
    blockedUntil = (exception as? PatientBlockedException)?.blockedUntil?.let { until -> time?.format(until) ?: until.toString() },
)

private val PROBLEM_JSON = ContentType("application", "problem+json")
private val log = LoggerFactory.getLogger("com.dkrmerve.clinic.api.Problems")

/** StatusPages configuration: every failure becomes problem JSON, never a stack trace. */
fun StatusPagesConfig.problemDetails(time: ApiTime) {
    exception<DomainException> { call, cause -> call.respondProblem(cause, time) }
    exception<RequestValidationException> { call, cause ->
        call.respondProblem(HttpStatusCode.BadRequest, "validation_failed", "One or more fields are invalid", errors = cause.errors)
    }
    exception<BadRequestException> { call, cause ->
        call.respondProblem(HttpStatusCode.BadRequest, "malformed_request", cause.rootMessage())
    }
    exception<SerializationException> { call, cause ->
        call.respondProblem(HttpStatusCode.BadRequest, "malformed_request", cause.rootMessage())
    }
    exception<UnsupportedMediaTypeException> { call, _ ->
        call.respondProblem(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Send application/json")
    }
    exception<CannotTransformContentToTypeException> { call, _ ->
        call.respondProblem(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Send application/json")
    }
    exception<PayloadTooLargeException> { call, _ ->
        call.respondProblem(HttpStatusCode.PayloadTooLarge, "payload_too_large", "Request body exceeds the configured limit")
    }
    exception<ExposedSQLException> { call, cause ->
        if (cause.sqlState == "23505") {
            call.respondProblem(HttpStatusCode.Conflict, "conflict", "The request collides with existing data (unique constraint)")
        } else {
            log.error("Database error (correlationId={})", call.callId, cause)
            call.respondProblem(HttpStatusCode.InternalServerError, "internal_error", "An unexpected error occurred")
        }
    }
    exception<Throwable> { call, cause ->
        log.error("Unhandled error (correlationId={})", call.callId, cause)
        call.respondProblem(HttpStatusCode.InternalServerError, "internal_error", "An unexpected error occurred")
    }
    status(HttpStatusCode.NotFound) { call, _ ->
        call.respondProblem(HttpStatusCode.NotFound, "route_not_found", "No route matches ${call.request.path()}")
    }
    status(HttpStatusCode.MethodNotAllowed) { call, _ ->
        call.respondProblem(HttpStatusCode.MethodNotAllowed, "method_not_allowed", "Method not allowed on ${call.request.path()}")
    }
    status(HttpStatusCode.TooManyRequests) { call, _ ->
        call.respondProblem(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests; try again later")
    }
    status(HttpStatusCode.PayloadTooLarge) { call, _ ->
        call.respondProblem(HttpStatusCode.PayloadTooLarge, "payload_too_large", "Request body exceeds the configured limit")
    }
}

private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current.message?.take(300) ?: "Malformed request body"
}
