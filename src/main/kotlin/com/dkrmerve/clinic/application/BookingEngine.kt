package com.dkrmerve.clinic.application

import com.dkrmerve.clinic.domain.Actor
import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.BookingContext
import com.dkrmerve.clinic.domain.Patient
import com.dkrmerve.clinic.domain.Practitioner
import com.dkrmerve.clinic.domain.SchedulingRules
import java.time.Duration
import java.time.Instant

/**
 * The one place that turns "I want this slot" into a persisted appointment. Direct bookings, reschedules
 * and waitlist promotion all go through it, so all three obey exactly the same rules.
 *
 * Must be called inside a [UnitOfWork.transaction]. It locks the practitioner row and then the patient row
 * (always in that order), so concurrent bookings for the same practitioner or the same patient are serialised
 * by the database and every rule sees the committed state of the request that went first.
 */
class BookingEngine(
    private val practitioners: PractitionerRepository,
    private val patients: PatientRepository,
    private val timeOff: TimeOffRepository,
    private val appointments: AppointmentRepository,
    private val rules: SchedulingRules,
) {
    fun book(
        practitioner: Practitioner,
        patient: Patient,
        type: AppointmentType,
        start: Instant,
        actor: Actor,
        promotedFromWaitlist: Boolean = false,
        note: String? = null,
    ): Appointment {
        practitioners.lockForBooking(practitioner.id)
        patients.lockForBooking(patient.id)
        rules.validateBooking(contextFor(practitioner, patient, type, start))
        val appointment =
            Appointment.book(
                practitionerId = practitioner.id,
                patientId = patient.id,
                type = type,
                start = start,
                now = rules.now(),
                actor = actor,
                promotedFromWaitlist = promotedFromWaitlist,
                note = note,
            )
        return appointments.save(appointment)
    }

    /** Loads the neighbourhood of [start] wide enough that no rule can miss an interacting record. */
    private fun contextFor(
        practitioner: Practitioner,
        patient: Patient,
        type: AppointmentType,
        start: Instant,
    ): BookingContext {
        val from = start.minus(LOOKAROUND)
        val to = start.plus(type.duration).plus(LOOKAROUND)
        return BookingContext(
            practitioner = practitioner,
            patient = patient,
            type = type,
            start = start,
            practitionerAppointments = appointments.forPractitionerBetween(practitioner.id, from, to),
            timeOff = timeOff.forPractitionerBetween(practitioner.id, from, to),
            patientAppointments = appointments.forPatientBetween(patient.id, from, to),
        )
    }

    private companion object {
        /** Wide enough to cover a whole clinic day on either side, whatever the zone offset. */
        val LOOKAROUND: Duration = Duration.ofHours(36)
    }
}
