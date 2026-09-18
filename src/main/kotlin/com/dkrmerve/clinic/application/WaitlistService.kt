package com.dkrmerve.clinic.application

import com.dkrmerve.clinic.domain.Actor
import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.ConflictException
import com.dkrmerve.clinic.domain.PatientBlockedException
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.RuleViolationException
import com.dkrmerve.clinic.domain.SchedulingRules
import com.dkrmerve.clinic.domain.WaitlistEntry
import com.dkrmerve.clinic.domain.WaitlistEntryId
import com.dkrmerve.clinic.domain.WaitlistStatus
import com.dkrmerve.clinic.domain.clinicDate
import org.slf4j.LoggerFactory
import java.time.LocalDate

data class JoinWaitlist(
    val practitionerId: PractitionerId,
    val patientId: PatientId,
    val date: LocalDate,
    val type: AppointmentType,
)

/**
 * Rule 9. Joining is explicit; promotion is automatic and runs inside the transaction of the cancellation
 * that freed the slot, under a savepoint: a failure while promoting rolls back the promotion attempt only,
 * never the cancellation (see README, "Design decisions").
 */
class WaitlistService(
    private val waitlist: WaitlistRepository,
    private val practitioners: PractitionerRepository,
    private val patients: PatientRepository,
    private val engine: BookingEngine,
    private val rules: SchedulingRules,
    private val uow: UnitOfWork,
) {
    private val log = LoggerFactory.getLogger(WaitlistService::class.java)

    suspend fun join(cmd: JoinWaitlist): WaitlistEntry =
        uow.transaction {
            val practitioner = practitioners.require(cmd.practitionerId)
            patients.lockForBooking(cmd.patientId) // serialises duplicate joins; the partial unique index is the backstop
            val patient = patients.require(cmd.patientId)
            rules.checkPatientNotBlocked(patient)
            if (cmd.date.isBefore(
                    rules.today(),
                )
            ) {
                throw RuleViolationException.outsideBookingHorizon("Waitlist date must not be in the past")
            }
            if (cmd.date.isAfter(rules.lastBookableDate())) {
                throw RuleViolationException.outsideBookingHorizon(
                    "Waitlist date is beyond the booking horizon (${rules.lastBookableDate()})",
                )
            }
            if (practitioner.schedule[cmd.date.dayOfWeek] == null) {
                throw RuleViolationException.outsideWorkingHours("${practitioner.name} does not work on ${cmd.date.dayOfWeek}")
            }
            val duplicate =
                waitlist
                    .forPractitionerAndDate(cmd.practitionerId, cmd.date)
                    .firstOrNull { it.patientId == cmd.patientId && it.status == WaitlistStatus.Waiting }
            if (duplicate != null) {
                throw ConflictException.waitlistDuplicate(
                    "${patient.name} is already on the waitlist for ${cmd.date} (entry ${duplicate.id})",
                )
            }
            WaitlistEntry(
                id = WaitlistEntryId.new(),
                practitionerId = cmd.practitionerId,
                patientId = cmd.patientId,
                date = cmd.date,
                type = cmd.type,
                createdAt = rules.now(),
            ).also(waitlist::save)
        }

    suspend fun get(id: WaitlistEntryId): WaitlistEntry = uow.transaction { sweep(listOf(waitlist.require(id))).single() }

    /** Entries for a practitioner and date, FIFO. Expired entries are swept lazily on read. */
    suspend fun list(
        practitionerId: PractitionerId,
        date: LocalDate,
        page: PageRequest,
    ): Page<WaitlistEntry> =
        uow.transaction {
            practitioners.require(practitionerId)
            val all = sweep(waitlist.forPractitionerAndDate(practitionerId, date))
            Page(all.drop(page.offset.toInt()).take(page.pageSize), page.page, page.pageSize, all.size.toLong())
        }

    /**
     * Called after an appointment became Cancelled, inside the caller's transaction. Walks the waitlist for
     * that practitioner and clinic day in FIFO order and books the first entry that passes every booking
     * rule at the freed start time. Entries that are blocked, conflicting or do not fit are skipped.
     */
    fun promoteFor(freed: Appointment): Appointment? =
        try {
            uow.savepoint { promote(freed) }
        } catch (e: Exception) {
            log.error("Waitlist promotion after cancelling {} failed; the cancellation itself is kept", freed.id, e)
            null
        }

    private fun promote(freed: Appointment): Appointment? {
        val date = freed.start.clinicDate(rules.zone)
        val practitioner = practitioners.require(freed.practitionerId)
        val candidates = sweep(waitlist.forPractitionerAndDate(freed.practitionerId, date)).filter { it.status == WaitlistStatus.Waiting }
        for (entry in candidates) {
            val patient = patients.require(entry.patientId)
            val booked =
                try {
                    engine.book(
                        practitioner = practitioner,
                        patient = patient,
                        type = entry.type,
                        start = freed.start,
                        actor = Actor.Clinic,
                        promotedFromWaitlist = true,
                        note = "Promoted from waitlist entry ${entry.id} after ${freed.id} was cancelled",
                    )
                } catch (skip: PatientBlockedException) {
                    continue
                } catch (skip: ConflictException) {
                    log.debug("Waitlist entry {} skipped for {}: {}", entry.id, freed.start, skip.code)
                    continue
                } catch (skip: RuleViolationException) {
                    log.debug("Waitlist entry {} skipped for {}: {}", entry.id, freed.start, skip.code)
                    continue
                }
            waitlist.save(entry.fulfilled(booked.id))
            log.info("Promoted waitlist entry {} into appointment {}", entry.id, booked.id)
            return booked
        }
        return null
    }

    private fun sweep(entries: List<WaitlistEntry>): List<WaitlistEntry> {
        val today = rules.today()
        return entries.map { entry ->
            entry.evaluatedOn(today).also { evaluated -> if (evaluated !== entry) waitlist.save(evaluated) }
        }
    }
}
