package com.dkrmerve.clinic.infrastructure

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed DSL table objects. They mirror the Flyway migration under src/main/resources/db/migration;
 * Flyway owns the schema, these objects only describe it to the query DSL.
 */
object PractitionersTable : Table("practitioners") {
    val id = javaUUID("id")
    val name = varchar("name", 200)
    val specialty = varchar("specialty", 200)
    val slotMinutes = integer("slot_minutes")
    val bufferMinutes = integer("buffer_minutes")
    val maxAppointmentsPerDay = integer("max_appointments_per_day")
    override val primaryKey = PrimaryKey(id)
}

object WorkingHoursTable : Table("practitioner_working_hours") {
    val practitionerId = javaUUID("practitioner_id")
    val dayOfWeek = varchar("day_of_week", 9)
    val windowStart = time("window_start")
    val windowEnd = time("window_end")
    override val primaryKey = PrimaryKey(practitionerId, dayOfWeek)
}

object TimeOffTable : Table("time_off") {
    val id = javaUUID("id")
    val practitionerId = javaUUID("practitioner_id")
    val fromAt = timestampWithTimeZone("from_at")
    val toAt = timestampWithTimeZone("to_at")
    val reason = varchar("reason", 500)
    override val primaryKey = PrimaryKey(id)
}

object PatientsTable : Table("patients") {
    val id = javaUUID("id")
    val name = varchar("name", 200)
    val email = varchar("email", 320)
    val lateCancellations = integer("late_cancellations")
    val blockedUntil = timestampWithTimeZone("blocked_until").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PatientNoShowsTable : Table("patient_no_shows") {
    val patientId = javaUUID("patient_id")
    val occurredAt = timestampWithTimeZone("occurred_at")
}

object AppointmentsTable : Table("appointments") {
    val id = javaUUID("id")
    val practitionerId = javaUUID("practitioner_id")
    val patientId = javaUUID("patient_id")
    val type = varchar("type", 20)
    val startAt = timestampWithTimeZone("start_at")
    val endAt = timestampWithTimeZone("end_at")
    val status = varchar("status", 20)
    val cancelledBy = varchar("cancelled_by", 10).nullable()
    val lateCancellation = bool("late_cancellation").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val promotedFromWaitlist = bool("promoted_from_waitlist")
    val version = integer("version")
    override val primaryKey = PrimaryKey(id)
}

object AppointmentHistoryTable : Table("appointment_history") {
    val id = long("id").autoIncrement()
    val appointmentId = javaUUID("appointment_id")
    val occurredAt = timestampWithTimeZone("occurred_at")
    val actor = varchar("actor", 10)
    val fromStatus = varchar("from_status", 20).nullable()
    val toStatus = varchar("to_status", 20)
    val toCancelledBy = varchar("to_cancelled_by", 10).nullable()
    val toLate = bool("to_late").nullable()
    val note = varchar("note", 500).nullable()
    override val primaryKey = PrimaryKey(id)
}

object WaitlistTable : Table("waitlist_entries") {
    val id = javaUUID("id")
    val practitionerId = javaUUID("practitioner_id")
    val patientId = javaUUID("patient_id")
    val entryDate = date("entry_date")
    val type = varchar("type", 20)
    val createdAt = timestampWithTimeZone("created_at")
    val status = varchar("status", 20)
    val fulfilledBy = javaUUID("fulfilled_by").nullable()
    override val primaryKey = PrimaryKey(id)
}
