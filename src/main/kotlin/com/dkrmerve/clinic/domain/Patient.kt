package com.dkrmerve.clinic.domain

import java.time.Instant

/**
 * A patient. "Blocked" is not a flag that someone has to remember to reset: it is derived from
 * [blockedUntil] compared with the current time, so a block expires by itself.
 */
data class Patient(
    val id: PatientId,
    val name: String,
    val email: String,
    /** Start times of appointments the patient did not show up for, oldest first. */
    val noShows: List<Instant> = emptyList(),
    val lateCancellations: Int = 0,
    val blockedUntil: Instant? = null,
) {
    init {
        invariant(name.isNotBlank(), "invalid_patient") { "Patient name must not be blank" }
        invariant(EMAIL.matches(email), "invalid_patient") { "Email looks invalid: $email" }
        invariant(lateCancellations >= 0, "invalid_patient") { "lateCancellations must not be negative" }
    }

    /** Blocked strictly before [blockedUntil]; at that instant the patient is free again. */
    fun isBlockedAt(now: Instant): Boolean = blockedUntil?.isAfter(now) == true

    fun recordLateCancellation(): Patient = copy(lateCancellations = lateCancellations + 1)

    /**
     * Rule 8. Records the no-show of an appointment that started at [occurredAt] and recomputes the block
     * from the whole history: if [SchedulingPolicy.noShowLimit] no-shows fall inside the rolling
     * [SchedulingPolicy.noShowWindow] ending at the most recent no-show (both ends inclusive), the patient
     * is blocked until [now] + [SchedulingPolicy.blockDuration]. Because it is computed from history,
     * marking an older appointment as no-show later still produces the block.
     */
    fun recordNoShow(
        occurredAt: Instant,
        now: Instant,
        policy: SchedulingPolicy,
    ): Patient {
        val history = (noShows + occurredAt).sorted()
        val windowStart = history.last().minus(policy.noShowWindow)
        val inWindow = history.count { !it.isBefore(windowStart) }
        val block = if (inWindow >= policy.noShowLimit) now.plus(policy.blockDuration) else blockedUntil
        return copy(noShows = history, blockedUntil = block)
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
