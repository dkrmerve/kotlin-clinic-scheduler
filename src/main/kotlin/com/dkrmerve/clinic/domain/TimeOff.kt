package com.dkrmerve.clinic.domain

import java.time.Instant

/** A block during which the practitioner takes no appointments (holiday, training, sick leave). */
data class TimeOff(
    val id: TimeOffId,
    val practitionerId: PractitionerId,
    val from: Instant,
    val to: Instant,
    val reason: String,
) {
    init {
        invariant(from.isBefore(to), "invalid_time_off") { "Time off must start before it ends" }
        invariant(reason.isNotBlank(), "invalid_time_off") { "Time off needs a reason" }
    }

    /** Half-open interval intersection: [from, to) overlaps [start, end). */
    fun intersects(
        start: Instant,
        end: Instant,
    ): Boolean = start.isBefore(to) && from.isBefore(end)
}
