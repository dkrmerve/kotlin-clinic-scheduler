package com.dkrmerve.clinic.domain

import java.time.Instant

/**
 * Everything the domain can refuse, as a sealed hierarchy. Each subtype corresponds to exactly one
 * HTTP status (mapped once, in the api package, with an exhaustive `when`) and carries a stable
 * snake_case [code] plus a human-readable [detail]. The full catalog lives in docs/errors.md.
 */
sealed class DomainException(
    val code: String,
    val detail: String,
) : RuntimeException(detail)

/** 400: an invariant of an aggregate does not hold (the API validator normally catches these first). */
class ValidationException(
    code: String,
    detail: String,
) : DomainException(code, detail)

/** 404 */
class NotFoundException(
    entity: String,
    id: Any,
) : DomainException("${entity.lowercase().replace(' ', '_')}_not_found", "$entity $id not found")

/** 409: the state machine does not allow this step (rule 10). */
class InvalidTransitionException(
    from: String,
    to: String,
) : DomainException("invalid_transition", "Cannot move from $from to $to")

/** 409: the request is well-formed and in time, but collides with existing state. */
class ConflictException private constructor(
    code: String,
    detail: String,
) : DomainException(code, detail) {
    companion object {
        fun slotTaken(detail: String) = ConflictException("slot_taken", detail)

        fun patientConflict(detail: String) = ConflictException("patient_conflict", detail)

        fun dailyCapacityReached(detail: String) = ConflictException("daily_capacity_reached", detail)

        fun waitlistDuplicate(detail: String) = ConflictException("waitlist_duplicate", detail)

        fun noShowBeforeStart(detail: String) = ConflictException("no_show_before_start", detail)
    }
}

/** 409: somebody else changed the same row first (optimistic lock or unique index). Safe to retry. */
class ConcurrencyException(
    detail: String,
) : DomainException("concurrent_modification", detail)

/** 422: a scheduling rule says no; nothing about the current state would make it pass. */
class RuleViolationException private constructor(
    code: String,
    detail: String,
) : DomainException(code, detail) {
    companion object {
        fun outsideWorkingHours(detail: String) = RuleViolationException("outside_working_hours", detail)

        fun slotMisaligned(detail: String) = RuleViolationException("slot_misaligned", detail)

        fun practitionerUnavailable(detail: String) = RuleViolationException("practitioner_unavailable", detail)

        fun outsideBookingHorizon(detail: String) = RuleViolationException("outside_booking_horizon", detail)

        fun cancellationWindowClosed(detail: String) = RuleViolationException("cancellation_window_closed", detail)
    }
}

/** 403: the patient is blocked (rule 8). The block is derived from [blockedUntil], never stored as a flag. */
class PatientBlockedException(
    val blockedUntil: Instant,
) : DomainException("patient_blocked", "Patient is blocked from booking until $blockedUntil")

/** 401: no valid credential identified the caller. */
class UnauthenticatedException(
    detail: String,
) : DomainException("unauthenticated", detail)

/** 403: the caller is known but may not do this. */
class ForbiddenException private constructor(
    code: String,
    detail: String,
) : DomainException(code, detail) {
    companion object {
        fun forbiddenRole(detail: String) = ForbiddenException("forbidden_role", detail)

        fun notOwner(detail: String) = ForbiddenException("not_owner", detail)
    }
}

/** `require` for aggregates: the same ergonomics, but a typed exception instead of IllegalArgumentException. */
internal inline fun invariant(
    condition: Boolean,
    code: String,
    detail: () -> String,
) {
    if (!condition) throw ValidationException(code, detail())
}
