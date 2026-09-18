package com.dkrmerve.clinic.infrastructure

import com.dkrmerve.clinic.TestDatabases
import com.dkrmerve.clinic.application.PageRequest
import com.dkrmerve.clinic.domain.Actor
import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.AppointmentId
import com.dkrmerve.clinic.domain.AppointmentStatus
import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.ConcurrencyException
import com.dkrmerve.clinic.domain.ConflictException
import com.dkrmerve.clinic.domain.NOW
import com.dkrmerve.clinic.domain.Patient
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.Practitioner
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.TUESDAY
import com.dkrmerve.clinic.domain.WaitlistEntry
import com.dkrmerve.clinic.domain.WaitlistEntryId
import com.dkrmerve.clinic.domain.WaitlistStatus
import com.dkrmerve.clinic.domain.at
import com.dkrmerve.clinic.domain.patient
import com.dkrmerve.clinic.domain.practitioner
import com.dkrmerve.clinic.domain.timeOff
import com.dkrmerve.clinic.domain.weekdays
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Repository adapters against the shared test database (H2 by default, PostgreSQL under `integrationTest`). */
class ExposedRepositoriesTest :
    FunSpec({
        val db = TestDatabases.shared.database
        val practitioners = ExposedPractitionerRepository()
        val timeOffRepo = ExposedTimeOffRepository()
        val patients = ExposedPatientRepository()
        val appointments = ExposedAppointmentRepository()
        val waitlist = ExposedWaitlistRepository()

        fun <T> tx(block: () -> T): T = transaction(db) { block() }

        fun saved(
            practitioner: Practitioner = practitioner(),
            patient: Patient = patient(),
        ): Pair<Practitioner, Patient> =
            tx {
                practitioners.save(practitioner)
                patients.save(patient)
                practitioner to patient
            }

        context("practitioners") {
            test("save + findById round-trips schedule and settings; unknown id is null") {
                val original =
                    practitioner(bufferMinutes = 5, maxAppointmentsPerDay = 7, schedule = weekdays("08:30", "12:30"))
                tx { practitioners.save(original) }
                tx { practitioners.findById(original.id) } shouldBe original
                tx { practitioners.findById(PractitionerId.new()) }.shouldBeNull()
            }

            test("saving again updates settings and replaces the working hours") {
                val original = practitioner()
                tx { practitioners.save(original) }
                val changed = original.copy(name = "Dr. Renamed", bufferMinutes = 15, schedule = weekdays("10:00", "14:00"))
                tx { practitioners.save(changed) }
                tx { practitioners.findById(original.id) } shouldBe changed
            }

            test("lockForBooking on existing practitioner and patient rows succeeds inside a transaction") {
                val (practitioner, patient) = saved()
                tx {
                    practitioners.lockForBooking(practitioner.id)
                    patients.lockForBooking(patient.id)
                }
            }
        }

        context("time off") {
            test("forPractitionerBetween returns only intersecting blocks, ordered by start") {
                val (practitioner, _) = saved()
                val morning = timeOff(practitioner, at(TUESDAY, 9, 0), at(TUESDAY, 10, 0), "Morning")
                val evening = timeOff(practitioner, at(TUESDAY, 16, 0), at(TUESDAY, 17, 0), "Evening")
                val otherDay = timeOff(practitioner, at(TUESDAY.plusDays(3), 9, 0), at(TUESDAY.plusDays(3), 10, 0), "Other day")
                tx { listOf(evening, morning, otherDay).forEach(timeOffRepo::save) }
                tx {
                    timeOffRepo.forPractitionerBetween(
                        practitioner.id,
                        at(TUESDAY, 0, 0),
                        at(TUESDAY.plusDays(1), 0, 0),
                    )
                } shouldContainExactly
                    listOf(morning, evening)
                tx { timeOffRepo.forPractitionerBetween(practitioner.id, at(TUESDAY, 10, 0), at(TUESDAY, 16, 0)) }.shouldBeEmpty()
            }
        }

        context("patients") {
            test("save + findById round-trips late cancellations and blockedUntil; unknown id is null") {
                val original = patient(lateCancellations = 2, blockedUntil = NOW.plus(Duration.ofDays(5)))
                tx { patients.save(original) }
                tx { patients.findById(original.id) } shouldBe original
                val updated = original.copy(lateCancellations = 3, blockedUntil = null)
                tx { patients.save(updated) }
                tx { patients.findById(original.id) } shouldBe updated
                tx { patients.findById(PatientId.new()) }.shouldBeNull()
            }

            test("no-shows are derived from NoShow appointments (oldest first), never stored a second time") {
                val (practitioner, patient) = saved()
                val later = at(TUESDAY.plusDays(7), 10, 0)
                val earlier = at(TUESDAY, 10, 0)
                listOf(later, earlier).forEach { start ->
                    val a = Appointment.book(practitioner.id, patient.id, AppointmentType.Consultation, start, NOW, Actor.Clinic)
                    tx { appointments.save(a.transitionTo(AppointmentStatus.NoShow, Actor.Clinic, NOW)) }
                }
                tx {
                    appointments.save(
                        Appointment.book(
                            practitioner.id,
                            patient.id,
                            AppointmentType.Consultation,
                            at(TUESDAY.plusDays(1), 10, 0),
                            NOW,
                            Actor.Clinic,
                        ),
                    )
                }
                tx { patients.findById(patient.id)!! }.noShows shouldContainExactly listOf(earlier, later)
                // An in-memory no-show list passed to save() is not persisted: the appointments are the source of truth.
                tx { patients.save(patient.copy(noShows = listOf(NOW))) }
                tx { patients.findById(patient.id)!! }.noShows shouldContainExactly listOf(earlier, later)
            }
        }

        context("appointments") {
            test("insert, read with history, and range queries by practitioner and patient") {
                val (practitioner, patient) = saved()
                val booked =
                    Appointment.book(
                        practitioner.id,
                        patient.id,
                        AppointmentType.Consultation,
                        at(TUESDAY, 10, 0),
                        NOW,
                        Actor.Patient,
                        note = "first",
                    )
                val persisted = tx { appointments.save(booked) }
                persisted.version shouldBe 0
                tx { appointments.findById(booked.id) } shouldBe booked
                tx { appointments.forPractitionerBetween(practitioner.id, at(TUESDAY, 10, 15), at(TUESDAY, 11, 0)) }.map { it.id } shouldBe
                    listOf(booked.id)
                tx { appointments.forPractitionerBetween(practitioner.id, at(TUESDAY, 10, 30), at(TUESDAY, 11, 0)) }.shouldBeEmpty()
                tx { appointments.forPatientBetween(patient.id, at(TUESDAY, 9, 0), at(TUESDAY, 10, 1)) }.map { it.id } shouldBe
                    listOf(booked.id)
                tx { appointments.findById(AppointmentId.new()) }.shouldBeNull()
            }

            test("update persists the new status, appends history and bumps the version; a stale version -> ConcurrencyException") {
                val (practitioner, patient) = saved()
                val booked =
                    tx {
                        appointments.save(
                            Appointment.book(practitioner.id, patient.id, AppointmentType.FollowUp, at(TUESDAY, 10, 0), NOW, Actor.Clinic),
                        )
                    }
                val cancelled =
                    booked.transitionTo(
                        AppointmentStatus.Cancelled(Actor.Patient, late = true),
                        Actor.Patient,
                        NOW.plusSeconds(5),
                        "sick",
                    )
                val v1 = tx { appointments.save(cancelled) }
                v1.version shouldBe 1
                val reloaded = tx { appointments.findById(booked.id)!! }
                reloaded.status shouldBe AppointmentStatus.Cancelled(Actor.Patient, late = true)
                reloaded.version shouldBe 1
                reloaded.history shouldHaveSize 2
                reloaded.history.last().note shouldBe "sick"
                reloaded.history.last().from shouldBe AppointmentStatus.Booked
                // Somebody else already moved the row to version 1; saving the version-0 copy must fail.
                val stale = booked.transitionTo(AppointmentStatus.CheckedIn, Actor.Clinic, NOW)
                shouldThrow<ConcurrencyException> { tx { appointments.save(stale) } }.detail shouldContain "expected version 0"
            }

            test("forPatient pages newest first with a total") {
                val (practitioner, patient) = saved()
                val starts = (0 until 5).map { at(TUESDAY.plusDays(it.toLong()), 10, 0) }
                tx {
                    starts.forEach {
                        appointments.save(
                            Appointment.book(practitioner.id, patient.id, AppointmentType.Consultation, it, NOW, Actor.Clinic),
                        )
                    }
                }
                val page = tx { appointments.forPatient(patient.id, PageRequest(page = 2, pageSize = 2)) }
                page.total shouldBe 5
                page.items.map { it.start } shouldBe listOf(starts[2], starts[1])
                tx { appointments.forPatient(patient.id, PageRequest(3, 2)) }.items.map { it.start } shouldBe listOf(starts[0])
                tx { appointments.forPatient(PatientId.new(), PageRequest(1, 10)) }.total shouldBe 0
            }

            test("every status round-trips through the codec") {
                val (practitioner, patient) = saved()
                val statuses =
                    listOf(
                        AppointmentStatus.Booked,
                        AppointmentStatus.CheckedIn,
                        AppointmentStatus.Completed,
                        AppointmentStatus.NoShow,
                        AppointmentStatus.Cancelled(Actor.Clinic, late = false),
                        AppointmentStatus.Cancelled(Actor.Patient, late = true),
                    )
                statuses.forEachIndexed { i, status ->
                    val start = at(TUESDAY.plusDays(i.toLong()), 9, 0)
                    val a =
                        Appointment
                            .book(
                                practitioner.id,
                                patient.id,
                                AppointmentType.Consultation,
                                start,
                                NOW,
                                Actor.Clinic,
                            ).copy(status = status)
                    tx { appointments.save(a) }
                    tx { appointments.findById(a.id)!! }.status shouldBe status
                }
            }

            test("StatusCodec rejects inconsistent rows") {
                shouldThrow<IllegalArgumentException> { StatusCodec.decode("Cancelled", null, true) }
                shouldThrow<IllegalArgumentException> { StatusCodec.decode("Cancelled", "Clinic", null) }
                shouldThrow<IllegalStateException> { StatusCodec.decode("Teleported", null, null) }
            }

            test("a unique violation on insert is mapped to ConflictException slot_taken (PostgreSQL partial index)") {
                if (!TestDatabases.isPostgres) return@test
                val (practitioner, patient) = saved()
                val other = tx { patient().also(patients::save) }
                val start = at(TUESDAY, 10, 0)
                tx {
                    appointments.save(
                        Appointment.book(practitioner.id, patient.id, AppointmentType.Consultation, start, NOW, Actor.Clinic),
                    )
                }
                val duplicate = Appointment.book(practitioner.id, other.id, AppointmentType.Consultation, start, NOW, Actor.Clinic)
                shouldThrow<ConflictException> { tx { appointments.save(duplicate) } }.code shouldBe "slot_taken"
                // Raw insert bypassing the domain proves the index itself, not the application, refuses the row.
                shouldThrow<org.jetbrains.exposed.v1.exceptions.ExposedSQLException> {
                    tx {
                        AppointmentsTable.insert {
                            it[id] = AppointmentId.new().value
                            it[practitionerId] = practitioner.id.value
                            it[patientId] = other.id.value
                            it[type] = "Consultation"
                            it[startAt] = OffsetDateTime.ofInstant(start, ZoneOffset.UTC)
                            it[endAt] = OffsetDateTime.ofInstant(start.plus(Duration.ofMinutes(30)), ZoneOffset.UTC)
                            it[status] = "CheckedIn"
                            it[createdAt] = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
                            it[promotedFromWaitlist] = false
                            it[version] = 0
                        }
                    }
                }.sqlState shouldBe "23505"
                // A cancelled row at the same start is allowed: the index only covers active statuses.
                val cancelled = duplicate.copy(status = AppointmentStatus.Cancelled(Actor.Clinic, late = false))
                tx { appointments.save(cancelled) }
            }
        }

        context("waitlist") {
            test("save, findById, FIFO listing by practitioner and date, and status update") {
                val (practitioner, patient) = saved()
                val other = tx { patient().also(patients::save) }
                val first = WaitlistEntry(WaitlistEntryId.new(), practitioner.id, patient.id, TUESDAY, AppointmentType.Consultation, NOW)
                val second =
                    WaitlistEntry(WaitlistEntryId.new(), practitioner.id, other.id, TUESDAY, AppointmentType.FollowUp, NOW.plusSeconds(1))
                val otherDay = first.copy(id = WaitlistEntryId.new(), date = TUESDAY.plusDays(1))
                tx { listOf(second, first, otherDay).forEach(waitlist::save) }
                tx { waitlist.forPractitionerAndDate(practitioner.id, TUESDAY) } shouldContainExactly listOf(first, second)
                tx { waitlist.findById(first.id) } shouldBe first
                tx { waitlist.findById(WaitlistEntryId.new()) }.shouldBeNull()
                val appointment =
                    tx {
                        appointments.save(
                            Appointment.book(
                                practitioner.id,
                                patient.id,
                                AppointmentType.Consultation,
                                at(TUESDAY, 10, 0),
                                NOW,
                                Actor.Clinic,
                            ),
                        )
                    }
                tx { waitlist.save(first.fulfilled(appointment.id)) }
                val reloaded = tx { waitlist.findById(first.id)!! }
                reloaded.status shouldBe WaitlistStatus.Fulfilled
                reloaded.fulfilledBy shouldBe appointment.id
            }
        }
    })
