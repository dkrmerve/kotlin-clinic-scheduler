package com.dkrmerve.clinic.domain

import java.time.Instant
import java.time.LocalDate

enum class WaitlistStatus { Waiting, Fulfilled, Expired }

/** A patient asking to be booked with a practitioner on a date as soon as a slot frees up. */
data class WaitlistEntry(
    val id: WaitlistEntryId,
    val practitionerId: PractitionerId,
    val patientId: PatientId,
    val date: LocalDate,
    val type: AppointmentType,
    val createdAt: Instant,
    val status: WaitlistStatus = WaitlistStatus.Waiting,
    val fulfilledBy: AppointmentId? = null,
) {
    /** Lazy expiry: an entry for a date that has passed can never be fulfilled. */
    fun evaluatedOn(today: LocalDate): WaitlistEntry =
        if (status == WaitlistStatus.Waiting && date.isBefore(today)) copy(status = WaitlistStatus.Expired) else this

    fun fulfilled(appointmentId: AppointmentId): WaitlistEntry {
        if (status != WaitlistStatus.Waiting) throw InvalidTransitionException(status.name, WaitlistStatus.Fulfilled.name)
        return copy(status = WaitlistStatus.Fulfilled, fulfilledBy = appointmentId)
    }
}
