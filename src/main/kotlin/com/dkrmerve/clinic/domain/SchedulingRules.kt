package com.dkrmerve.clinic.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Everything the rules need to decide on one booking. Built by the application layer, consumed here. */
data class BookingContext(
    val practitioner: Practitioner,
    val patient: Patient,
    val type: AppointmentType,
    val start: Instant,
    /** All appointments of the practitioner that could possibly interact with [start] (same day, plus buffer). */
    val practitionerAppointments: List<Appointment>,
    val timeOff: List<TimeOff>,
    /** All appointments of the patient that could possibly interact with [start]. */
    val patientAppointments: List<Appointment>,
) {
    val end: Instant get() = start.plus(type.duration)
}

/**
 * The business rules as pure functions over a [BookingContext]: no I/O, no framework, fully testable with
 * a fixed [Clock]. Slot alignment is computed in clinic-local wall time; every comparison of moments is
 * done on [Instant] so DST transitions cannot produce phantom or overlapping slots.
 */
class SchedulingRules(
    private val policy: SchedulingPolicy,
    private val clock: Clock,
) {
    val zone get() = policy.zone

    fun now(): Instant = clock.instant()

    fun today(): LocalDate = now().clinicDate(zone)

    /** The last clinic date inside the booking horizon (rule 4). */
    fun lastBookableDate(): LocalDate = now().inZone(zone).plus(policy.bookingHorizon).toLocalDate()

    /** Runs every booking rule; the first violation wins. Order: 8, 4, 1, 3, 2, 6, 5. */
    fun validateBooking(ctx: BookingContext) {
        checkPatientNotBlocked(ctx.patient)
        checkBookingHorizon(ctx.start)
        checkSlotAlignment(ctx.practitioner, ctx.type, ctx.start)
        checkTimeOff(ctx.timeOff, ctx.start, ctx.end)
        checkNoOverlap(ctx.practitioner, ctx.practitionerAppointments, ctx.start, ctx.end)
        checkDailyCapacity(ctx.practitioner, ctx.practitionerAppointments, ctx.start)
        checkPatientConstraints(ctx.patient, ctx.patientAppointments, ctx.practitioner.id, ctx.start, ctx.end)
    }

    /** Rule 8 (consequence): a blocked patient can neither book nor join the waitlist. */
    fun checkPatientNotBlocked(patient: Patient) {
        val until = patient.blockedUntil
        if (until != null && patient.isBlockedAt(now())) throw PatientBlockedException(until)
    }

    /** Rule 4: strictly in the future, at most [SchedulingPolicy.bookingHorizon] ahead (inclusive). */
    fun checkBookingHorizon(start: Instant) {
        val now = now()
        if (!start.isAfter(now)) throw RuleViolationException.outsideBookingHorizon("Appointment start must be in the future")
        val horizonEnd = now.inZone(zone).plus(policy.bookingHorizon).toInstant()
        if (start.isAfter(horizonEnd)) {
            throw RuleViolationException.outsideBookingHorizon(
                "Appointments can be booked at most ${policy.bookingHorizon.days} days ahead (until $horizonEnd)",
            )
        }
    }

    /** Rule 1: inside the weekday's working window, on a slot boundary, and the whole visit ends by closing time. */
    fun checkSlotAlignment(
        practitioner: Practitioner,
        type: AppointmentType,
        start: Instant,
    ) {
        val local = start.inZone(zone)
        val day = local.dayOfWeek
        val window =
            practitioner.schedule[day]
                ?: throw RuleViolationException.outsideWorkingHours("${practitioner.name} does not work on $day")
        val time = local.toLocalTime()
        val minutesFromStart = window.minutesFromStart(time)
        if (minutesFromStart < 0 || time.isAfter(window.end)) {
            throw RuleViolationException.outsideWorkingHours("$time is outside working hours ${window.start} to ${window.end} on $day")
        }
        if (local.second != 0 || local.nano != 0 || minutesFromStart % practitioner.slotMinutes != 0L) {
            throw RuleViolationException.slotMisaligned(
                "$time is not on a ${practitioner.slotMinutes}-minute slot boundary counted from ${window.start}",
            )
        }
        val closing = local.toLocalDate().atTime(window.end).toInstantIn(zone)
        if (start.plus(type.duration).isAfter(closing)) {
            throw RuleViolationException.outsideWorkingHours(
                "A ${type.name} (${type.minutes} min) starting at $time would end after closing time ${window.end}",
            )
        }
    }

    /** Rule 3: no intersection with a time-off block. */
    fun checkTimeOff(
        timeOff: List<TimeOff>,
        start: Instant,
        end: Instant,
    ) {
        timeOff.firstOrNull { it.intersects(start, end) }?.let {
            throw RuleViolationException.practitionerUnavailable("Practitioner is unavailable: ${it.reason}")
        }
    }

    /** Rule 2: no intersection with an existing slot-blocking appointment, each padded by the buffer on both sides. */
    fun checkNoOverlap(
        practitioner: Practitioner,
        existing: List<Appointment>,
        start: Instant,
        end: Instant,
    ) {
        val buffer = practitioner.buffer
        existing.firstOrNull { it.status.blocksSlot && it.intersects(start.minus(buffer), end.plus(buffer)) }?.let {
            throw ConflictException.slotTaken(
                "Slot overlaps appointment ${it.id} (including the ${practitioner.bufferMinutes}-minute buffer)",
            )
        }
    }

    /** Rule 6: the non-cancelled appointments of the practitioner that day must stay below the daily maximum. */
    fun checkDailyCapacity(
        practitioner: Practitioner,
        existing: List<Appointment>,
        start: Instant,
    ) {
        val date = start.clinicDate(zone)
        if (!hasCapacityOn(practitioner, date, existing)) {
            throw ConflictException.dailyCapacityReached(
                "${practitioner.name} already has ${practitioner.maxAppointmentsPerDay} appointments on $date",
            )
        }
    }

    /** Rule 5: one appointment per practitioner per clinic day, and no overlaps across practitioners. */
    fun checkPatientConstraints(
        patient: Patient,
        patientAppointments: List<Appointment>,
        practitionerId: PractitionerId,
        start: Instant,
        end: Instant,
    ) {
        val date = start.clinicDate(zone)
        val active = patientAppointments.filter { it.status.blocksSlot }
        active.firstOrNull { it.practitionerId == practitionerId && it.start.clinicDate(zone) == date }?.let {
            throw ConflictException.patientConflict("${patient.name} already has appointment ${it.id} with this practitioner on $date")
        }
        active.firstOrNull { it.intersects(start, end) }?.let {
            throw ConflictException.patientConflict("${patient.name} already has appointment ${it.id} at that time")
        }
    }

    /**
     * Availability: every aligned start on [date] at which a [type] appointment passes rules 1, 2, 3 and 6,
     * excluding starts that are already in the past (rule 4). Slot starts are enumerated in wall time and
     * mapped to instants; on the autumn DST day a wall time in the repeated hour yields two distinct slots,
     * on the spring DST day the skipped hour yields none.
     */
    fun availableStarts(
        practitioner: Practitioner,
        date: LocalDate,
        type: AppointmentType,
        practitionerAppointments: List<Appointment>,
        timeOff: List<TimeOff>,
    ): List<Instant> {
        val window = practitioner.schedule[date.dayOfWeek] ?: return emptyList()
        if (!hasCapacityOn(practitioner, date, practitionerAppointments)) return emptyList()
        val now = now()
        return wallClockStarts(window, practitioner.slotMinutes, type.duration)
            .flatMap { time -> instantsAt(date, time) }
            .distinct()
            .sorted()
            .filter { start -> start.isAfter(now) && fits(practitioner, type, start, practitionerAppointments, timeOff) }
    }

    /** Rule 7: what a cancellation by [actor] at this moment means, or a refusal if the window has closed. */
    fun cancellationOutcome(
        appointment: Appointment,
        actor: Actor,
    ): AppointmentStatus.Cancelled =
        when (actor) {
            Actor.Clinic -> {
                AppointmentStatus.Cancelled(by = Actor.Clinic, late = false)
            }

            Actor.Patient -> {
                val notice = Duration.between(now(), appointment.start)
                when {
                    notice >= policy.freeCancellationNotice -> AppointmentStatus.Cancelled(by = Actor.Patient, late = false)

                    notice >= policy.minimumCancellationNotice -> AppointmentStatus.Cancelled(by = Actor.Patient, late = true)

                    else -> throw RuleViolationException.cancellationWindowClosed(
                        "Patients must cancel at least ${policy.minimumCancellationNotice.toHours()} hours before the appointment",
                    )
                }
            }
        }

    /** Rule 8: a no-show can only be recorded once the start time has passed. */
    fun checkNoShowAllowed(appointment: Appointment) {
        if (!appointment.start.isBefore(now())) {
            throw ConflictException.noShowBeforeStart("Appointment ${appointment.id} has not started yet")
        }
    }

    private fun hasCapacityOn(
        practitioner: Practitioner,
        date: LocalDate,
        existing: List<Appointment>,
    ): Boolean = existing.count { it.status.countsTowardsCapacity && it.start.clinicDate(zone) == date } < practitioner.maxAppointmentsPerDay

    private fun wallClockStarts(
        window: WorkingWindow,
        slotMinutes: Int,
        duration: Duration,
    ): List<LocalTime> {
        val starts = mutableListOf<LocalTime>()
        var time = window.start
        while (time.plus(duration).isAfter(time) && !time.plus(duration).isAfter(window.end)) {
            starts += time
            val next = time.plusMinutes(slotMinutes.toLong())
            if (!next.isAfter(time)) break // wrapped past midnight
            time = next
        }
        return starts
    }

    private fun instantsAt(
        date: LocalDate,
        time: LocalTime,
    ): List<Instant> {
        val zoned = date.atTime(time).atZone(zone)
        return listOf(zoned.withEarlierOffsetAtOverlap().toInstant(), zoned.withLaterOffsetAtOverlap().toInstant())
    }

    private fun fits(
        practitioner: Practitioner,
        type: AppointmentType,
        start: Instant,
        existing: List<Appointment>,
        timeOff: List<TimeOff>,
    ): Boolean =
        try {
            val end = start.plus(type.duration)
            checkSlotAlignment(practitioner, type, start)
            checkTimeOff(timeOff, start, end)
            checkNoOverlap(practitioner, existing, start, end)
            true
        } catch (_: RuleViolationException) {
            false
        } catch (_: ConflictException) {
            false
        }
}
