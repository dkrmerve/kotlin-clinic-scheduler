package com.dkrmerve.clinic.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/** A contiguous block of working time on one weekday, e.g. 09:00 to 17:00. */
data class WorkingWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        invariant(start.isBefore(end), "invalid_schedule") { "Working window must start before it ends ($start to $end)" }
    }

    val length: Duration get() = Duration.between(start, end)

    /** Minutes between the window start and [time]; negative when [time] is before the window. */
    fun minutesFromStart(time: LocalTime): Long = Duration.between(start, time).toMinutes()
}

/** Which weekdays a practitioner works and when. Days that are absent are days off. */
data class WeeklySchedule(
    val windows: Map<DayOfWeek, WorkingWindow>,
) {
    init {
        invariant(windows.isNotEmpty(), "invalid_schedule") { "A schedule needs at least one working day" }
    }

    operator fun get(day: DayOfWeek): WorkingWindow? = windows[day]
}

data class Practitioner(
    val id: PractitionerId,
    val name: String,
    val specialty: String,
    val schedule: WeeklySchedule,
    val slotMinutes: Int,
    val bufferMinutes: Int,
    val maxAppointmentsPerDay: Int,
) {
    init {
        invariant(name.isNotBlank(), "invalid_practitioner") { "Practitioner name must not be blank" }
        invariant(specialty.isNotBlank(), "invalid_practitioner") { "Specialty must not be blank" }
        invariant(slotMinutes in ALLOWED_SLOT_MINUTES, "invalid_practitioner") { "slotMinutes must be one of $ALLOWED_SLOT_MINUTES" }
        invariant(bufferMinutes >= 0, "invalid_practitioner") { "bufferMinutes must not be negative" }
        invariant(maxAppointmentsPerDay >= 1, "invalid_practitioner") { "maxAppointmentsPerDay must be at least 1" }
        AppointmentType.entries.forEach { type ->
            invariant(type.minutes % slotMinutes == 0L, "slot_incompatible") {
                "Slot length of $slotMinutes minutes cannot fit ${type.name} (${type.minutes} min): " +
                    "every appointment type must be a multiple of the slot length"
            }
        }
        schedule.windows.forEach { (day, window) ->
            invariant(window.length.toMinutes() >= slotMinutes, "invalid_schedule") { "Working window on $day is shorter than one slot" }
        }
    }

    val buffer: Duration get() = Duration.ofMinutes(bufferMinutes.toLong())

    companion object {
        val ALLOWED_SLOT_MINUTES: Set<Int> = setOf(10, 15, 20, 30)
    }
}
