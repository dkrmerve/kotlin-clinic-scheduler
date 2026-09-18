package com.dkrmerve.clinic.infrastructure

import com.dkrmerve.clinic.application.AppointmentRepository
import com.dkrmerve.clinic.application.Page
import com.dkrmerve.clinic.application.PageRequest
import com.dkrmerve.clinic.application.PatientRepository
import com.dkrmerve.clinic.application.PractitionerRepository
import com.dkrmerve.clinic.application.TimeOffRepository
import com.dkrmerve.clinic.application.WaitlistRepository
import com.dkrmerve.clinic.domain.Actor
import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.AppointmentId
import com.dkrmerve.clinic.domain.AppointmentStatus
import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.ConcurrencyException
import com.dkrmerve.clinic.domain.ConflictException
import com.dkrmerve.clinic.domain.HistoryEntry
import com.dkrmerve.clinic.domain.Patient
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.Practitioner
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.TimeOff
import com.dkrmerve.clinic.domain.TimeOffId
import com.dkrmerve.clinic.domain.WaitlistEntry
import com.dkrmerve.clinic.domain.WaitlistEntryId
import com.dkrmerve.clinic.domain.WaitlistStatus
import com.dkrmerve.clinic.domain.WeeklySchedule
import com.dkrmerve.clinic.domain.WorkingWindow
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/*
 * Repository adapters over the Exposed DSL. Every method runs inside the transaction opened by
 * ExposedUnitOfWork; the application layer guarantees that.
 */

/** Instants are written as UTC offsets and converted back on read, so the JVM default zone never matters. */
private fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun OffsetDateTime.asInstant(): Instant = toInstant()

private const val UNIQUE_VIOLATION = "23505"

private fun ExposedSQLException.isUniqueViolation(): Boolean = sqlState == UNIQUE_VIOLATION

class ExposedPractitionerRepository : PractitionerRepository {
    override fun save(practitioner: Practitioner) {
        val exists = PractitionersTable.selectAll().where { PractitionersTable.id eq practitioner.id.value }.any()
        if (exists) {
            PractitionersTable.update({ PractitionersTable.id eq practitioner.id.value }) { fill(it, practitioner) }
            WorkingHoursTable.deleteWhere { WorkingHoursTable.practitionerId eq practitioner.id.value }
        } else {
            PractitionersTable.insert {
                it[id] = practitioner.id.value
                fill(it, practitioner)
            }
        }
        practitioner.schedule.windows.forEach { (day, window) ->
            WorkingHoursTable.insert {
                it[practitionerId] = practitioner.id.value
                it[dayOfWeek] = day.name
                it[windowStart] = window.start
                it[windowEnd] = window.end
            }
        }
    }

    private fun fill(
        row: UpdateBuilder<*>,
        practitioner: Practitioner,
    ) {
        row[PractitionersTable.name] = practitioner.name
        row[PractitionersTable.specialty] = practitioner.specialty
        row[PractitionersTable.slotMinutes] = practitioner.slotMinutes
        row[PractitionersTable.bufferMinutes] = practitioner.bufferMinutes
        row[PractitionersTable.maxAppointmentsPerDay] = practitioner.maxAppointmentsPerDay
    }

    override fun findById(id: PractitionerId): Practitioner? {
        val row = PractitionersTable.selectAll().where { PractitionersTable.id eq id.value }.singleOrNull() ?: return null
        val windows =
            WorkingHoursTable
                .selectAll()
                .where { WorkingHoursTable.practitionerId eq id.value }
                .associate {
                    DayOfWeek.valueOf(it[WorkingHoursTable.dayOfWeek]) to
                        WorkingWindow(it[WorkingHoursTable.windowStart], it[WorkingHoursTable.windowEnd])
                }
        return Practitioner(
            id = id,
            name = row[PractitionersTable.name],
            specialty = row[PractitionersTable.specialty],
            schedule = WeeklySchedule(windows),
            slotMinutes = row[PractitionersTable.slotMinutes],
            bufferMinutes = row[PractitionersTable.bufferMinutes],
            maxAppointmentsPerDay = row[PractitionersTable.maxAppointmentsPerDay],
        )
    }

    override fun lockForBooking(id: PractitionerId) {
        PractitionersTable
            .selectAll()
            .where { PractitionersTable.id eq id.value }
            .forUpdate()
            .toList()
    }
}

class ExposedTimeOffRepository : TimeOffRepository {
    override fun save(timeOff: TimeOff) {
        TimeOffTable.insert {
            it[id] = timeOff.id.value
            it[practitionerId] = timeOff.practitionerId.value
            it[fromAt] = timeOff.from.toDb()
            it[toAt] = timeOff.to.toDb()
            it[reason] = timeOff.reason
        }
    }

    override fun forPractitionerBetween(
        practitionerId: PractitionerId,
        from: Instant,
        to: Instant,
    ): List<TimeOff> =
        TimeOffTable
            .selectAll()
            .where {
                (TimeOffTable.practitionerId eq practitionerId.value) and
                    (TimeOffTable.fromAt less to.toDb()) and
                    (TimeOffTable.toAt greater from.toDb())
            }.orderBy(TimeOffTable.fromAt, SortOrder.ASC)
            .map {
                TimeOff(
                    id = TimeOffId(it[TimeOffTable.id]),
                    practitionerId = PractitionerId(it[TimeOffTable.practitionerId]),
                    from = it[TimeOffTable.fromAt].asInstant(),
                    to = it[TimeOffTable.toAt].asInstant(),
                    reason = it[TimeOffTable.reason],
                )
            }
}

class ExposedPatientRepository : PatientRepository {
    override fun lockForBooking(id: PatientId) {
        PatientsTable
            .selectAll()
            .where { PatientsTable.id eq id.value }
            .forUpdate()
            .toList()
    }

    override fun save(patient: Patient) {
        val exists = PatientsTable.selectAll().where { PatientsTable.id eq patient.id.value }.any()
        if (exists) {
            PatientsTable.update({ PatientsTable.id eq patient.id.value }) { fill(it, patient) }
        } else {
            PatientsTable.insert {
                it[id] = patient.id.value
                fill(it, patient)
            }
        }
    }

    private fun fill(
        row: UpdateBuilder<*>,
        patient: Patient,
    ) {
        row[PatientsTable.name] = patient.name
        row[PatientsTable.email] = patient.email
        row[PatientsTable.lateCancellations] = patient.lateCancellations
        row[PatientsTable.blockedUntil] = patient.blockedUntil?.toDb()
    }

    override fun findById(id: PatientId): Patient? {
        val row = PatientsTable.selectAll().where { PatientsTable.id eq id.value }.singleOrNull() ?: return null
        // No-shows are not stored twice: they are the patient's appointments in status NoShow (rule 8).
        val noShows =
            AppointmentsTable
                .selectAll()
                .where { (AppointmentsTable.patientId eq id.value) and (AppointmentsTable.status eq AppointmentStatus.NoShow.label) }
                .orderBy(AppointmentsTable.startAt, SortOrder.ASC)
                .map { it[AppointmentsTable.startAt].asInstant() }
        return Patient(
            id = id,
            name = row[PatientsTable.name],
            email = row[PatientsTable.email],
            noShows = noShows,
            lateCancellations = row[PatientsTable.lateCancellations],
            blockedUntil = row[PatientsTable.blockedUntil]?.asInstant(),
        )
    }
}

/** Status is stored as a label plus two nullable columns that only Cancelled uses. */
internal object StatusCodec {
    fun cancelledBy(status: AppointmentStatus): String? = (status as? AppointmentStatus.Cancelled)?.by?.name

    fun late(status: AppointmentStatus): Boolean? = (status as? AppointmentStatus.Cancelled)?.late

    fun decode(
        label: String,
        cancelledBy: String?,
        late: Boolean?,
    ): AppointmentStatus =
        when (label) {
            AppointmentStatus.Booked.label -> {
                AppointmentStatus.Booked
            }

            AppointmentStatus.CheckedIn.label -> {
                AppointmentStatus.CheckedIn
            }

            AppointmentStatus.Completed.label -> {
                AppointmentStatus.Completed
            }

            AppointmentStatus.NoShow.label -> {
                AppointmentStatus.NoShow
            }

            CANCELLED -> {
                AppointmentStatus.Cancelled(
                    by = Actor.valueOf(requireNotNull(cancelledBy) { "Cancelled status without cancelled_by" }),
                    late = requireNotNull(late) { "Cancelled status without late flag" },
                )
            }

            else -> {
                error("Unknown appointment status '$label'")
            }
        }

    private const val CANCELLED = "Cancelled"
}

class ExposedAppointmentRepository : AppointmentRepository {
    override fun save(appointment: Appointment): Appointment {
        val exists = AppointmentsTable.selectAll().where { AppointmentsTable.id eq appointment.id.value }.any()
        val persisted = if (exists) update(appointment) else insert(appointment)
        // History is append-only (rule 11): persist the entries added since the last save.
        val stored =
            AppointmentHistoryTable
                .selectAll()
                .where { AppointmentHistoryTable.appointmentId eq appointment.id.value }
                .count()
        appointment.history.drop(stored.toInt()).forEach { entry ->
            AppointmentHistoryTable.insert {
                it[appointmentId] = appointment.id.value
                it[occurredAt] = entry.at.toDb()
                it[actor] = entry.actor.name
                it[fromStatus] = entry.from?.label
                it[toStatus] = entry.to.label
                it[toCancelledBy] = StatusCodec.cancelledBy(entry.to)
                it[toLate] = StatusCodec.late(entry.to)
                it[note] = entry.note
            }
        }
        return persisted
    }

    private fun insert(appointment: Appointment): Appointment {
        try {
            AppointmentsTable.insert {
                it[id] = appointment.id.value
                it[practitionerId] = appointment.practitionerId.value
                it[patientId] = appointment.patientId.value
                it[type] = appointment.type.name
                it[startAt] = appointment.start.toDb()
                it[endAt] = appointment.end.toDb()
                it[status] = appointment.status.label
                it[cancelledBy] = StatusCodec.cancelledBy(appointment.status)
                it[lateCancellation] = StatusCodec.late(appointment.status)
                it[createdAt] = appointment.createdAt.toDb()
                it[promotedFromWaitlist] = appointment.promotedFromWaitlist
                it[version] = 0
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) {
                throw ConflictException.slotTaken("Another active appointment already occupies ${appointment.start} for this practitioner")
            }
            throw e
        }
        return appointment.copy(version = 0)
    }

    /** Optimistic locking: the row must still carry the version we loaded, otherwise somebody else changed it. */
    private fun update(appointment: Appointment): Appointment {
        val next = appointment.version + 1
        val updated =
            try {
                AppointmentsTable.update({
                    (AppointmentsTable.id eq appointment.id.value) and (AppointmentsTable.version eq appointment.version)
                }) {
                    it[status] = appointment.status.label
                    it[cancelledBy] = StatusCodec.cancelledBy(appointment.status)
                    it[lateCancellation] = StatusCodec.late(appointment.status)
                    it[version] = next
                }
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) {
                    throw ConflictException.slotTaken(
                        "Another active appointment already occupies ${appointment.start} for this practitioner",
                    )
                }
                throw e
            }
        if (updated == 0) {
            throw ConcurrencyException(
                "Appointment ${appointment.id} was modified by another request (expected version ${appointment.version})",
            )
        }
        return appointment.copy(version = next)
    }

    override fun findById(id: AppointmentId): Appointment? =
        AppointmentsTable
            .selectAll()
            .where { AppointmentsTable.id eq id.value }
            .singleOrNull()
            ?.let { toAppointment(it, historyFor(id)) }

    override fun forPractitionerBetween(
        practitionerId: PractitionerId,
        from: Instant,
        to: Instant,
    ): List<Appointment> =
        AppointmentsTable
            .selectAll()
            .where {
                (AppointmentsTable.practitionerId eq practitionerId.value) and
                    (AppointmentsTable.startAt less to.toDb()) and
                    (AppointmentsTable.endAt greater from.toDb())
            }.orderBy(AppointmentsTable.startAt, SortOrder.ASC)
            .map { toAppointment(it, emptyList()) }

    override fun forPatientBetween(
        patientId: PatientId,
        from: Instant,
        to: Instant,
    ): List<Appointment> =
        AppointmentsTable
            .selectAll()
            .where {
                (AppointmentsTable.patientId eq patientId.value) and
                    (AppointmentsTable.startAt less to.toDb()) and
                    (AppointmentsTable.endAt greater from.toDb())
            }.orderBy(AppointmentsTable.startAt, SortOrder.ASC)
            .map { toAppointment(it, emptyList()) }

    override fun forPatient(
        patientId: PatientId,
        page: PageRequest,
    ): Page<Appointment> {
        val query = AppointmentsTable.selectAll().where { AppointmentsTable.patientId eq patientId.value }
        val total = query.count()
        val items =
            query
                .orderBy(AppointmentsTable.startAt, SortOrder.DESC)
                .limit(page.pageSize)
                .offset(page.offset)
                .map { toAppointment(it, emptyList()) }
        return Page(items, page.page, page.pageSize, total)
    }

    private fun historyFor(id: AppointmentId): List<HistoryEntry> =
        AppointmentHistoryTable
            .selectAll()
            .where { AppointmentHistoryTable.appointmentId eq id.value }
            .orderBy(AppointmentHistoryTable.id, SortOrder.ASC)
            .map {
                HistoryEntry(
                    at = it[AppointmentHistoryTable.occurredAt].asInstant(),
                    actor = Actor.valueOf(it[AppointmentHistoryTable.actor]),
                    from = it[AppointmentHistoryTable.fromStatus]?.let { label -> StatusCodec.decode(label, null, null) },
                    to =
                        StatusCodec.decode(
                            it[AppointmentHistoryTable.toStatus],
                            it[AppointmentHistoryTable.toCancelledBy],
                            it[AppointmentHistoryTable.toLate],
                        ),
                    note = it[AppointmentHistoryTable.note],
                )
            }

    private fun toAppointment(
        row: ResultRow,
        history: List<HistoryEntry>,
    ) = Appointment(
        id = AppointmentId(row[AppointmentsTable.id]),
        practitionerId = PractitionerId(row[AppointmentsTable.practitionerId]),
        patientId = PatientId(row[AppointmentsTable.patientId]),
        type = AppointmentType.valueOf(row[AppointmentsTable.type]),
        start = row[AppointmentsTable.startAt].asInstant(),
        end = row[AppointmentsTable.endAt].asInstant(),
        status =
            StatusCodec.decode(
                row[AppointmentsTable.status],
                row[AppointmentsTable.cancelledBy],
                row[AppointmentsTable.lateCancellation],
            ),
        createdAt = row[AppointmentsTable.createdAt].asInstant(),
        promotedFromWaitlist = row[AppointmentsTable.promotedFromWaitlist],
        history = history,
        version = row[AppointmentsTable.version],
    )
}

class ExposedWaitlistRepository : WaitlistRepository {
    override fun save(entry: WaitlistEntry) {
        val exists = WaitlistTable.selectAll().where { WaitlistTable.id eq entry.id.value }.any()
        if (exists) {
            WaitlistTable.update({ WaitlistTable.id eq entry.id.value }) {
                it[status] = entry.status.name
                it[fulfilledBy] = entry.fulfilledBy?.value
            }
        } else {
            try {
                insert(entry)
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) {
                    throw ConflictException.waitlistDuplicate("Patient is already waiting for practitioner ${entry.practitionerId} on ${entry.date}")
                }
                throw e
            }
        }
    }

    private fun insert(entry: WaitlistEntry) {
        WaitlistTable.insert {
            it[id] = entry.id.value
            it[practitionerId] = entry.practitionerId.value
            it[patientId] = entry.patientId.value
            it[entryDate] = entry.date
            it[type] = entry.type.name
            it[createdAt] = entry.createdAt.toDb()
            it[status] = entry.status.name
            it[fulfilledBy] = entry.fulfilledBy?.value
        }
    }

    override fun findById(id: WaitlistEntryId): WaitlistEntry? =
        WaitlistTable
            .selectAll()
            .where { WaitlistTable.id eq id.value }
            .singleOrNull()
            ?.let(::toEntry)

    override fun forPractitionerAndDate(
        practitionerId: PractitionerId,
        date: LocalDate,
    ): List<WaitlistEntry> =
        WaitlistTable
            .selectAll()
            .where { (WaitlistTable.practitionerId eq practitionerId.value) and (WaitlistTable.entryDate eq date) }
            .orderBy(WaitlistTable.createdAt, SortOrder.ASC)
            .map(::toEntry)

    private fun toEntry(row: ResultRow) =
        WaitlistEntry(
            id = WaitlistEntryId(row[WaitlistTable.id]),
            practitionerId = PractitionerId(row[WaitlistTable.practitionerId]),
            patientId = PatientId(row[WaitlistTable.patientId]),
            date = row[WaitlistTable.entryDate],
            type = AppointmentType.valueOf(row[WaitlistTable.type]),
            createdAt = row[WaitlistTable.createdAt].asInstant(),
            status = WaitlistStatus.valueOf(row[WaitlistTable.status]),
            fulfilledBy = row[WaitlistTable.fulfilledBy]?.let(::AppointmentId),
        )
}
