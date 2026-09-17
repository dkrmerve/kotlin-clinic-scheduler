package com.dkrmerve.clinic.application

import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.AppointmentId
import com.dkrmerve.clinic.domain.NotFoundException
import com.dkrmerve.clinic.domain.Patient
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.Practitioner
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.TimeOff
import com.dkrmerve.clinic.domain.WaitlistEntry
import com.dkrmerve.clinic.domain.WaitlistEntryId
import java.time.Instant
import java.time.LocalDate

/*
 * Ports: what the use cases need from the outside world, in domain terms. The infrastructure package
 * implements them with Exposed; tests implement failing variants to prove transactional behaviour.
 */

/** One database transaction per use case. */
interface UnitOfWork {
    /** Runs [block] in a new transaction on an I/O dispatcher and commits, or rolls back on any exception. */
    suspend fun <T> transaction(block: () -> T): T

    /**
     * Runs [block] inside the current transaction under a savepoint: if it throws, only its own writes are
     * rolled back and the surrounding transaction stays usable. Must be called from within [transaction].
     */
    fun <T> savepoint(block: () -> T): T
}

interface PractitionerRepository {
    fun save(practitioner: Practitioner)

    fun findById(id: PractitionerId): Practitioner?

    /**
     * Serialises concurrent bookings for one practitioner (SELECT ... FOR UPDATE on the practitioner row)
     * so two requests cannot both pass the overlap check and then both insert. Rows inserted by the
     * transaction that held the lock before us are visible once the lock is granted (READ COMMITTED).
     */
    fun lockForBooking(id: PractitionerId)
}

interface TimeOffRepository {
    fun save(timeOff: TimeOff)

    fun forPractitionerBetween(
        practitionerId: PractitionerId,
        from: Instant,
        to: Instant,
    ): List<TimeOff>
}

interface PatientRepository {
    fun save(patient: Patient)

    fun findById(id: PatientId): Patient?

    /** Row lock taken after the practitioner lock (always in that order, so no deadlock cycle) to serialise rule 5. */
    fun lockForBooking(id: PatientId)
}

interface AppointmentRepository {
    /** Inserts or updates. Updates are optimistic: a stale [Appointment.version] raises ConcurrencyException. */
    fun save(appointment: Appointment): Appointment

    fun findById(id: AppointmentId): Appointment?

    /** Appointments intersecting [from, to). */
    fun forPractitionerBetween(
        practitionerId: PractitionerId,
        from: Instant,
        to: Instant,
    ): List<Appointment>

    fun forPatientBetween(
        patientId: PatientId,
        from: Instant,
        to: Instant,
    ): List<Appointment>

    /** All appointments of a patient, newest start first, paged. */
    fun forPatient(
        patientId: PatientId,
        page: PageRequest,
    ): Page<Appointment>
}

interface WaitlistRepository {
    fun save(entry: WaitlistEntry)

    fun findById(id: WaitlistEntryId): WaitlistEntry?

    /** Entries for a practitioner on a date, oldest first (FIFO promotion order). */
    fun forPractitionerAndDate(
        practitionerId: PractitionerId,
        date: LocalDate,
    ): List<WaitlistEntry>
}

data class PageRequest(
    val page: Int,
    val pageSize: Int,
) {
    val offset: Long get() = (page - 1).toLong() * pageSize
}

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
) {
    fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), page, pageSize, total)
}

fun PractitionerRepository.require(id: PractitionerId): Practitioner = findById(id) ?: throw NotFoundException("Practitioner", id)

fun PatientRepository.require(id: PatientId): Patient = findById(id) ?: throw NotFoundException("Patient", id)

fun AppointmentRepository.require(id: AppointmentId): Appointment = findById(id) ?: throw NotFoundException("Appointment", id)

fun WaitlistRepository.require(id: WaitlistEntryId): WaitlistEntry = findById(id) ?: throw NotFoundException("Waitlist entry", id)
