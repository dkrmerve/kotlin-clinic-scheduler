package com.dkrmerve.clinic.domain

import java.time.Duration
import java.time.Period
import java.time.ZoneId

/** The tunable numbers behind the rules, in one place. Populated from environment variables at startup. */
data class SchedulingPolicy(
    /** Zone in which all calendar-based rules ("same day", "working hours") are evaluated. */
    val zone: ZoneId = ZoneId.of("Europe/Amsterdam"),
    val bookingHorizon: Period = Period.ofDays(60),
    val freeCancellationNotice: Duration = Duration.ofHours(24),
    val minimumCancellationNotice: Duration = Duration.ofHours(2),
    val noShowLimit: Int = 3,
    val noShowWindow: Duration = Duration.ofDays(90),
    val blockDuration: Duration = Duration.ofDays(30),
) {
    init {
        invariant(!bookingHorizon.isNegative && !bookingHorizon.isZero, "invalid_policy") { "bookingHorizon must be positive" }
        invariant(!minimumCancellationNotice.isNegative, "invalid_policy") { "minimumCancellationNotice must not be negative" }
        invariant(minimumCancellationNotice <= freeCancellationNotice, "invalid_policy") {
            "minimumCancellationNotice must not exceed freeCancellationNotice"
        }
        invariant(noShowLimit >= 1, "invalid_policy") { "noShowLimit must be at least 1" }
        invariant(!noShowWindow.isNegative && !noShowWindow.isZero, "invalid_policy") { "noShowWindow must be positive" }
        invariant(!blockDuration.isNegative && !blockDuration.isZero, "invalid_policy") { "blockDuration must be positive" }
    }

    companion object {
        val DEFAULT = SchedulingPolicy()
    }
}
