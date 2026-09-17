package com.dkrmerve.clinic.application

import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.Practitioner
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.RuleViolationException
import com.dkrmerve.clinic.domain.SchedulingRules
import com.dkrmerve.clinic.domain.TimeOff
import com.dkrmerve.clinic.domain.TimeOffId
import com.dkrmerve.clinic.domain.WeeklySchedule
import com.dkrmerve.clinic.domain.dayBounds
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class CreatePractitioner(
    val name: String,
    val specialty: String,
    val schedule: WeeklySchedule,
    val slotMinutes: Int,
    val bufferMinutes: Int,
    val maxAppointmentsPerDay: Int,
)

data class AddTimeOff(
    val practitionerId: PractitionerId,
    val from: Instant,
    val to: Instant,
    val reason: String,
)

class PractitionerService(
    private val practitioners: PractitionerRepository,
    private val timeOff: TimeOffRepository,
    private val appointments: AppointmentRepository,
    private val rules: SchedulingRules,
    private val uow: UnitOfWork,
) {
    suspend fun create(cmd: CreatePractitioner): Practitioner =
        uow.transaction {
            // The Practitioner constructor enforces slot/duration compatibility (400 slot_incompatible).
            val practitioner =
                Practitioner(
                    id = PractitionerId.new(),
                    name = cmd.name.trim(),
                    specialty = cmd.specialty.trim(),
                    schedule = cmd.schedule,
                    slotMinutes = cmd.slotMinutes,
                    bufferMinutes = cmd.bufferMinutes,
                    maxAppointmentsPerDay = cmd.maxAppointmentsPerDay,
                )
            practitioners.save(practitioner)
            practitioner
        }

    suspend fun get(id: PractitionerId): Practitioner = uow.transaction { practitioners.require(id) }

    suspend fun addTimeOff(cmd: AddTimeOff): TimeOff =
        uow.transaction {
            practitioners.require(cmd.practitionerId)
            val block = TimeOff(TimeOffId.new(), cmd.practitionerId, cmd.from, cmd.to, cmd.reason.trim())
            timeOff.save(block)
            block
        }

    /** Free, aligned start times for [type] on [date]. See [SchedulingRules.availableStarts]. */
    suspend fun availability(
        id: PractitionerId,
        date: LocalDate,
        type: AppointmentType,
    ): List<Instant> =
        uow.transaction {
            val practitioner = practitioners.require(id)
            if (date.isBefore(rules.today())) throw RuleViolationException.outsideBookingHorizon("Date $date is in the past")
            if (date.isAfter(rules.lastBookableDate())) {
                throw RuleViolationException.outsideBookingHorizon("Date $date is beyond the booking horizon (${rules.lastBookableDate()})")
            }
            val (dayStart, dayEnd) = date.dayBounds(rules.zone)
            val from = dayStart.minus(MARGIN)
            val to = dayEnd.plus(MARGIN)
            rules.availableStarts(
                practitioner = practitioner,
                date = date,
                type = type,
                practitionerAppointments = appointments.forPractitionerBetween(id, from, to),
                timeOff = timeOff.forPractitionerBetween(id, from, to),
            )
        }

    private companion object {
        val MARGIN: Duration = Duration.ofHours(12)
    }
}
