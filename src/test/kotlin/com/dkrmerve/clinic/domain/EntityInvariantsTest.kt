package com.dkrmerve.clinic.domain

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.util.UUID

/** Aggregate invariants: every `invariant(...)` in the domain throws a typed ValidationException with a stable code. */
class EntityInvariantsTest :
    FunSpec({

        context("Practitioner") {
            test("a valid practitioner exposes its buffer as a Duration") {
                practitioner(bufferMinutes = 10).buffer shouldBe Duration.ofMinutes(10)
            }

            withData(
                nameFn = { "slotMinutes ${it.first} -> ${it.second}" },
                10 to "slot_incompatible",
                20 to "slot_incompatible",
                30 to "slot_incompatible",
                45 to "invalid_practitioner",
            ) { (slot, code) ->
                shouldThrow<ValidationException> { practitioner(slotMinutes = slot) }.code shouldBe code
            }

            test("slot 15 is compatible with every appointment type") {
                shouldNotThrowAny { practitioner(slotMinutes = 15) }
            }

            test("blank name, blank specialty, negative buffer and zero capacity are invalid_practitioner") {
                shouldThrow<ValidationException> { practitioner(name = " ") }.code shouldBe "invalid_practitioner"
                shouldThrow<ValidationException> {
                    Practitioner(PractitionerId.new(), "Dr", " ", weekdays(), 15, 0, 5)
                }.detail shouldContain "Specialty"
                shouldThrow<ValidationException> { practitioner(bufferMinutes = -1) }.detail shouldContain "bufferMinutes"
                shouldThrow<ValidationException> { practitioner(maxAppointmentsPerDay = 0) }.detail shouldContain "maxAppointmentsPerDay"
            }

            test("a working window shorter than one slot is invalid_schedule") {
                val tiny = WeeklySchedule(mapOf(DayOfWeek.MONDAY to window("09:00", "09:10")))
                shouldThrow<ValidationException> { practitioner(schedule = tiny) }.code shouldBe "invalid_schedule"
            }
        }

        context("WorkingWindow and WeeklySchedule") {
            test("start must be before end") {
                shouldThrow<ValidationException> { window("17:00", "09:00") }.code shouldBe "invalid_schedule"
                shouldThrow<ValidationException> { window("09:00", "09:00") }.code shouldBe "invalid_schedule"
            }

            test("length and minutesFromStart") {
                val w = window("09:00", "17:00")
                w.length shouldBe Duration.ofHours(8)
                w.minutesFromStart(LocalTime.of(9, 45)) shouldBe 45
                w.minutesFromStart(LocalTime.of(8, 30)) shouldBe -30
            }

            test("an empty schedule is invalid_schedule; lookup by weekday returns null for days off") {
                shouldThrow<ValidationException> { WeeklySchedule(emptyMap()) }.code shouldBe "invalid_schedule"
                weekdays()[DayOfWeek.SATURDAY].shouldBeNull()
                weekdays()[DayOfWeek.MONDAY] shouldBe window("09:00", "17:00")
            }
        }

        context("Patient") {
            test("blank name -> invalid_patient") {
                shouldThrow<ValidationException> { patient(name = "") }.code shouldBe "invalid_patient"
            }

            withData(
                nameFn = { "email '$it' is rejected" },
                "no-at.example.test",
                "a@b",
                "a b@example.test",
                "@example.test",
                "",
            ) { email ->
                shouldThrow<ValidationException> { Patient(PatientId.new(), "Pat", email) }.code shouldBe "invalid_patient"
            }

            test("negative lateCancellations -> invalid_patient") {
                shouldThrow<ValidationException> { patient(lateCancellations = -1) }.detail shouldContain "lateCancellations"
            }
        }

        context("TimeOff") {
            val doctor = practitioner()

            test("must start before it ends and needs a reason") {
                shouldThrow<ValidationException> { timeOff(doctor, NOW, NOW) }.code shouldBe "invalid_time_off"
                shouldThrow<ValidationException> { timeOff(doctor, NOW, NOW.plusSeconds(60), reason = " ") }.code shouldBe
                    "invalid_time_off"
            }

            test("intersects is half-open on both sides") {
                val block = timeOff(doctor, NOW, NOW.plus(Duration.ofHours(1)))
                block.intersects(NOW.minusSeconds(1), NOW) shouldBe false
                block.intersects(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2))) shouldBe false
                block.intersects(NOW.plus(Duration.ofMinutes(59)), NOW.plus(Duration.ofHours(2))) shouldBe true
            }
        }

        context("Appointment") {
            test("end must equal start + duration of the type") {
                val doctor = practitioner()
                shouldThrow<ValidationException> {
                    Appointment(
                        AppointmentId.new(),
                        doctor.id,
                        PatientId.new(),
                        AppointmentType.Consultation,
                        NOW,
                        NOW.plusSeconds(60),
                        AppointmentStatus.Booked,
                        NOW,
                    )
                }.code shouldBe "invalid_appointment"
            }
        }

        context("WaitlistEntry") {
            val entry = WaitlistEntry(WaitlistEntryId.new(), PractitionerId.new(), PatientId.new(), TUESDAY, AppointmentType.FollowUp, NOW)

            test("expires lazily once its date is in the past, and only while waiting") {
                entry.evaluatedOn(TUESDAY).status shouldBe WaitlistStatus.Waiting
                entry.evaluatedOn(TUESDAY.plusDays(1)).status shouldBe WaitlistStatus.Expired
                val fulfilled = entry.fulfilled(AppointmentId.new())
                fulfilled.evaluatedOn(TUESDAY.plusDays(1)).status shouldBe WaitlistStatus.Fulfilled
            }

            test("evaluatedOn returns the same instance when nothing changes") {
                (entry.evaluatedOn(MONDAY) === entry) shouldBe true
            }

            test("fulfilling records the appointment; fulfilling twice is an invalid transition") {
                val id = AppointmentId.new()
                val fulfilled = entry.fulfilled(id)
                fulfilled.fulfilledBy shouldBe id
                shouldThrow<InvalidTransitionException> { fulfilled.fulfilled(AppointmentId.new()) }.code shouldBe "invalid_transition"
                shouldThrow<InvalidTransitionException> { entry.copy(status = WaitlistStatus.Expired).fulfilled(id) }
            }
        }

        context("SchedulingPolicy") {
            test("defaults") {
                val p = SchedulingPolicy.DEFAULT
                p.zone shouldBe ZoneId.of("Europe/Amsterdam")
                p.bookingHorizon shouldBe Period.ofDays(60)
                p.freeCancellationNotice shouldBe Duration.ofHours(24)
                p.minimumCancellationNotice shouldBe Duration.ofHours(2)
                p.noShowLimit shouldBe 3
                p.noShowWindow shouldBe Duration.ofDays(90)
                p.blockDuration shouldBe Duration.ofDays(30)
            }

            withData(
                nameFn = { "invalid policy: ${it.first}" },
                "zero horizon" to { SchedulingPolicy(bookingHorizon = Period.ZERO) },
                "negative minimum notice" to { SchedulingPolicy(minimumCancellationNotice = Duration.ofHours(-1)) },
                "minimum notice above free notice" to { SchedulingPolicy(minimumCancellationNotice = Duration.ofHours(48)) },
                "no-show limit 0" to { SchedulingPolicy(noShowLimit = 0) },
                "zero no-show window" to { SchedulingPolicy(noShowWindow = Duration.ZERO) },
                "zero block" to { SchedulingPolicy(blockDuration = Duration.ZERO) },
            ) { (_, build) ->
                shouldThrow<ValidationException> { build() }.code shouldBe "invalid_policy"
            }
        }

        context("ids, types and time helpers") {
            test("value class ids round-trip through their string form") {
                val raw = UUID.randomUUID()
                PractitionerId.parse(raw.toString()) shouldBe PractitionerId(raw)
                PatientId.parse(raw.toString()).toString() shouldBe raw.toString()
                AppointmentId.parse(raw.toString()).value shouldBe raw
                WaitlistEntryId.parse(raw.toString()).value shouldBe raw
                TimeOffId.new().toString().length shouldBe 36
                val fresh = PractitionerId.new()
                fresh shouldBe PractitionerId(fresh.value)
            }

            test("appointment types carry fixed durations") {
                AppointmentType.Consultation.minutes shouldBe 30
                AppointmentType.FollowUp.minutes shouldBe 15
                AppointmentType.Procedure.minutes shouldBe 60
            }

            test("clinicDate and inZone follow the clinic zone, toInstantIn goes back") {
                val lateEvening = Instant.parse("2027-01-11T23:30:00Z") // 00:30 next day in Amsterdam
                lateEvening.clinicDate(ZONE) shouldBe LocalDate.of(2027, 1, 12)
                lateEvening.inZone(ZONE).hour shouldBe 0
                LocalDate.of(2027, 1, 12).atTime(0, 30).toInstantIn(ZONE) shouldBe lateEvening
            }
        }

        context("exception catalog") {
            test("every factory yields the documented code and a non-blank detail") {
                val samples =
                    listOf(
                        ConflictException.slotTaken("x") to "slot_taken",
                        ConflictException.patientConflict("x") to "patient_conflict",
                        ConflictException.dailyCapacityReached("x") to "daily_capacity_reached",
                        ConflictException.waitlistDuplicate("x") to "waitlist_duplicate",
                        ConflictException.noShowBeforeStart("x") to "no_show_before_start",
                        RuleViolationException.outsideWorkingHours("x") to "outside_working_hours",
                        RuleViolationException.slotMisaligned("x") to "slot_misaligned",
                        RuleViolationException.practitionerUnavailable("x") to "practitioner_unavailable",
                        RuleViolationException.outsideBookingHorizon("x") to "outside_booking_horizon",
                        RuleViolationException.cancellationWindowClosed("x") to "cancellation_window_closed",
                        ForbiddenException.forbiddenRole("x") to "forbidden_role",
                        ForbiddenException.notOwner("x") to "not_owner",
                        UnauthenticatedException("x") to "unauthenticated",
                        ConcurrencyException("x") to "concurrent_modification",
                        NotFoundException("Waitlist entry", "42") to "waitlist_entry_not_found",
                        InvalidTransitionException("A", "B") to "invalid_transition",
                        PatientBlockedException(NOW) to "patient_blocked",
                        ValidationException("invalid_thing", "x") to "invalid_thing",
                    )
                samples.forEach { (exception, code) ->
                    exception.code shouldBe code
                    exception.detail.isNotBlank() shouldBe true
                    exception.message shouldBe exception.detail
                }
                PatientBlockedException(NOW).blockedUntil shouldBe NOW
                NotFoundException("Patient", "42").detail shouldBe "Patient 42 not found"
            }
        }
    })
