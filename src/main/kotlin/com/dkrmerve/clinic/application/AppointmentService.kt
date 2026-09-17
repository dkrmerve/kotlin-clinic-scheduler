package com.dkrmerve.clinic.application

import com.dkrmerve.clinic.domain.Actor
import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.AppointmentId
import com.dkrmerve.clinic.domain.AppointmentStatus
import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.SchedulingPolicy
import com.dkrmerve.clinic.domain.SchedulingRules
import java.time.Instant

data class BookAppointment(
    val practitionerId: PractitionerId,
    val patientId: PatientId,
    val type: AppointmentType,
    val start: Instant,
    val actor: Actor,
)

data class Reschedule(
    val appointmentId: AppointmentId,
    val newStart: Instant,
    /** Defaults to the current practitioner. */
    val newPractitionerId: PractitionerId? = null,
    /** Defaults to the current type. */
    val newType: AppointmentType? = null,
    val actor: Actor,
)

/** The cancelled appointment and, if the waitlist filled the gap, the appointment that took its place. */
data class CancellationResult(
    val cancelled: Appointment,
    val promoted: Appointment?,
)

/** [noOp] is true when the requested slot equals the current one: nothing was changed. */
data class RescheduleResult(
    val previous: Appointment,
    val replacement: Appointment,
    val promoted: Appointment?,
    val noOp: Boolean,
)

class AppointmentService(
    private val practitioners: PractitionerRepository,
    private val patients: PatientRepository,
    private val appointments: AppointmentRepository,
    private val engine: BookingEngine,
    private val waitlist: WaitlistService,
    private val rules: SchedulingRules,
    private val policy: SchedulingPolicy,
    private val uow: UnitOfWork,
) {
    suspend fun book(cmd: BookAppointment): Appointment =
        uow.transaction {
            val practitioner = practitioners.require(cmd.practitionerId)
            val patient = patients.require(cmd.patientId)
            engine.book(practitioner, patient, cmd.type, cmd.start, cmd.actor)
        }

    suspend fun get(id: AppointmentId): Appointment = uow.transaction { appointments.require(id) }

    /** Rule 7 (policy), rule 10 (transition), rule 11 (history), then rule 9 (promotion) in the same transaction. */
    suspend fun cancel(
        id: AppointmentId,
        actor: Actor,
        note: String? = null,
    ): CancellationResult =
        uow.transaction {
            val cancelled = cancelInside(appointments.require(id), actor, note)
            CancellationResult(cancelled, waitlist.promoteFor(cancelled))
        }

    suspend fun checkIn(
        id: AppointmentId,
        actor: Actor,
    ): Appointment =
        uow.transaction {
            transition(id, AppointmentStatus.CheckedIn, actor)
        }

    suspend fun complete(
        id: AppointmentId,
        actor: Actor,
    ): Appointment =
        uow.transaction {
            transition(id, AppointmentStatus.Completed, actor)
        }

    /** Rule 8: only after the start time (the route restricts it to clinic roles); may block the patient. */
    suspend fun markNoShow(
        id: AppointmentId,
        actor: Actor,
    ): Appointment =
        uow.transaction {
            val appointment = appointments.require(id)
            rules.checkNoShowAllowed(appointment)
            val now = rules.now()
            val updated = appointments.save(appointment.transitionTo(AppointmentStatus.NoShow, actor, now))
            patients.save(patients.require(appointment.patientId).recordNoShow(appointment.start, now, policy))
            updated
        }

    /**
     * Rule 10: atomic cancel + book. Both happen in one transaction, so if any rule rejects the new slot the
     * whole thing rolls back and the original appointment is untouched. Rescheduling to the identical slot
     * is a no-op that returns the current appointment.
     */
    suspend fun reschedule(cmd: Reschedule): RescheduleResult =
        uow.transaction {
            val current = appointments.require(cmd.appointmentId)
            val practitioner = practitioners.require(cmd.newPractitionerId ?: current.practitionerId)
            val type = cmd.newType ?: current.type
            if (practitioner.id == current.practitionerId && type == current.type && cmd.newStart == current.start) {
                return@transaction RescheduleResult(current, current, promoted = null, noOp = true)
            }
            val patient = patients.require(current.patientId)
            val cancelled = cancelInside(current, cmd.actor, "Rescheduled to ${cmd.newStart}")
            val replacement = engine.book(practitioner, patient, type, cmd.newStart, cmd.actor, note = "Rescheduled from ${current.id}")
            RescheduleResult(cancelled, replacement, waitlist.promoteFor(cancelled), noOp = false)
        }

    private fun cancelInside(
        appointment: Appointment,
        actor: Actor,
        note: String?,
    ): Appointment {
        val outcome = rules.cancellationOutcome(appointment, actor)
        val cancelled = appointments.save(appointment.transitionTo(outcome, actor, rules.now(), note))
        if (outcome.late) patients.save(patients.require(appointment.patientId).recordLateCancellation())
        return cancelled
    }

    private fun transition(
        id: AppointmentId,
        to: AppointmentStatus,
        actor: Actor,
    ): Appointment = appointments.save(appointments.require(id).transitionTo(to, actor, rules.now()))
}
