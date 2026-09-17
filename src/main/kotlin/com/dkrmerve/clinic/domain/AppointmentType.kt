package com.dkrmerve.clinic.domain

import java.time.Duration

/** Every appointment type has a fixed duration; the clinic does not book "free length" visits. */
enum class AppointmentType(
    val duration: Duration,
) {
    Consultation(Duration.ofMinutes(30)),
    FollowUp(Duration.ofMinutes(15)),
    Procedure(Duration.ofMinutes(60)),
    ;

    val minutes: Long get() = duration.toMinutes()
}
