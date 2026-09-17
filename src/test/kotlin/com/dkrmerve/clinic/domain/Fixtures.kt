package com.dkrmerve.clinic.domain

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// Builders for domain tests. Dates are chosen so weekdays and DST days are explicit in every test.

val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

/** Monday 2027-01-11 09:00 CET. */
val NOW: Instant = Instant.parse("2027-01-11T08:00:00Z")
val MONDAY: LocalDate = LocalDate.of(2027, 1, 11)
val TUESDAY: LocalDate = LocalDate.of(2027, 1, 12)
val SUNDAY: LocalDate = LocalDate.of(2027, 1, 17)

fun fixedClock(at: Instant = NOW): Clock = Clock.fixed(at, ZoneOffset.UTC)

fun rules(
    now: Instant = NOW,
    policy: SchedulingPolicy = SchedulingPolicy.DEFAULT,
) = SchedulingRules(policy, fixedClock(now))

fun at(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId = ZONE,
): Instant = date.atTime(time).atZone(zone).toInstant()

fun at(
    date: LocalDate,
    hour: Int,
    minute: Int = 0,
): Instant = at(date, LocalTime.of(hour, minute))

fun window(
    start: String,
    end: String,
) = WorkingWindow(LocalTime.parse(start), LocalTime.parse(end))

fun weekdays(
    start: String = "09:00",
    end: String = "17:00",
): WeeklySchedule =
    WeeklySchedule(
        listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            .associateWith { window(start, end) },
    )

/** slotMinutes defaults to 15: the only grid that divides all three appointment types (30, 15, 60 minutes). */
fun practitioner(
    slotMinutes: Int = 15,
    bufferMinutes: Int = 0,
    maxAppointmentsPerDay: Int = 20,
    schedule: WeeklySchedule = weekdays(),
    name: String = "Dr. Test",
) = Practitioner(PractitionerId.new(), name, "General practice", schedule, slotMinutes, bufferMinutes, maxAppointmentsPerDay)

fun patient(
    noShows: List<Instant> = emptyList(),
    lateCancellations: Int = 0,
    blockedUntil: Instant? = null,
    name: String = "Pat Test",
) = Patient(PatientId.new(), name, "pat@example.test", noShows, lateCancellations, blockedUntil)

fun appointment(
    practitioner: Practitioner,
    patient: Patient,
    start: Instant,
    type: AppointmentType = AppointmentType.Consultation,
    status: AppointmentStatus = AppointmentStatus.Booked,
    createdAt: Instant = NOW,
): Appointment =
    Appointment(
        id = AppointmentId.new(),
        practitionerId = practitioner.id,
        patientId = patient.id,
        type = type,
        start = start,
        end = start.plus(type.duration),
        status = status,
        createdAt = createdAt,
    )

fun timeOff(
    practitioner: Practitioner,
    from: Instant,
    to: Instant,
    reason: String = "Conference",
) = TimeOff(TimeOffId.new(), practitioner.id, from, to, reason)

fun context(
    practitioner: Practitioner,
    patient: Patient,
    start: Instant,
    type: AppointmentType = AppointmentType.Consultation,
    practitionerAppointments: List<Appointment> = emptyList(),
    timeOff: List<TimeOff> = emptyList(),
    patientAppointments: List<Appointment> = emptyList(),
) = BookingContext(practitioner, patient, type, start, practitionerAppointments, timeOff, patientAppointments)
