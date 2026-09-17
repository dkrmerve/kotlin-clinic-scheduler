package com.dkrmerve.clinic.domain

import java.time.Instant

/** Rule 11: every status change is recorded with who did it and why. */
data class HistoryEntry(
    val at: Instant,
    val actor: Actor,
    val from: AppointmentStatus?,
    val to: AppointmentStatus,
    val note: String? = null,
)

data class Appointment(
    val id: AppointmentId,
    val practitionerId: PractitionerId,
    val patientId: PatientId,
    val type: AppointmentType,
    val start: Instant,
    val end: Instant,
    val status: AppointmentStatus,
    val createdAt: Instant,
    val promotedFromWaitlist: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    /** Optimistic-lock version, owned by the repository: incremented on every persisted update. */
    val version: Int = 0,
) {
    init {
        invariant(end == start.plus(type.duration), "invalid_appointment") {
            "end must equal start + ${type.minutes} min for ${type.name}"
        }
    }

    /** Half-open intersection with [from, to). */
    fun intersects(
        from: Instant,
        to: Instant,
    ): Boolean = start.isBefore(to) && from.isBefore(end)

    /** Rule 10 enforced in one place; rule 11 appends the history entry. */
    fun transitionTo(
        next: AppointmentStatus,
        actor: Actor,
        at: Instant,
        note: String? = null,
    ): Appointment {
        if (!status.canTransitionTo(next)) throw InvalidTransitionException(status.label, next.label)
        return copy(status = next, history = history + HistoryEntry(at, actor, status, next, note))
    }

    companion object {
        fun book(
            practitionerId: PractitionerId,
            patientId: PatientId,
            type: AppointmentType,
            start: Instant,
            now: Instant,
            actor: Actor,
            promotedFromWaitlist: Boolean = false,
            note: String? = null,
        ): Appointment =
            Appointment(
                id = AppointmentId.new(),
                practitionerId = practitionerId,
                patientId = patientId,
                type = type,
                start = start,
                end = start.plus(type.duration),
                status = AppointmentStatus.Booked,
                createdAt = now,
                promotedFromWaitlist = promotedFromWaitlist,
                history = listOf(HistoryEntry(now, actor, null, AppointmentStatus.Booked, note)),
            )
    }
}
