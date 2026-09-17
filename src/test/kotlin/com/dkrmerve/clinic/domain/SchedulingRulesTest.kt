package com.dkrmerve.clinic.domain

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class SchedulingRulesTest :
    FunSpec({
        val rules = rules()
        val doctor = practitioner() // 15-minute grid, Mon-Fri 09:00-17:00
        val pat = patient()

        context("Rule 1 - slot alignment inside the working window (422 outside_working_hours / slot_misaligned)") {
            data class Case(
                val label: String,
                val time: LocalTime,
                val type: AppointmentType,
                val expectedCode: String?,
            )
            withData(
                nameFn = { "${it.label} -> ${it.expectedCode ?: "allowed"}" },
                Case("start exactly at opening 09:00", LocalTime.of(9, 0), AppointmentType.Consultation, null),
                Case("consultation ending exactly at closing 17:00", LocalTime.of(16, 30), AppointmentType.Consultation, null),
                Case("follow-up ending exactly at closing 17:00", LocalTime.of(16, 45), AppointmentType.FollowUp, null),
                Case("procedure that would end at 17:30", LocalTime.of(16, 30), AppointmentType.Procedure, "outside_working_hours"),
                Case("consultation that would end at 17:15", LocalTime.of(16, 45), AppointmentType.Consultation, "outside_working_hours"),
                Case("start at closing time 17:00", LocalTime.of(17, 0), AppointmentType.FollowUp, "outside_working_hours"),
                Case("start before opening 08:45", LocalTime.of(8, 45), AppointmentType.Consultation, "outside_working_hours"),
                Case("start off the 15-minute grid 09:10", LocalTime.of(9, 10), AppointmentType.FollowUp, "slot_misaligned"),
                Case("start with seconds 09:00:30", LocalTime.of(9, 0, 30), AppointmentType.FollowUp, "slot_misaligned"),
            ) { case ->
                val start = at(TUESDAY, case.time)
                if (case.expectedCode == null) {
                    shouldNotThrowAny { rules.checkSlotAlignment(doctor, case.type, start) }
                } else {
                    shouldThrow<RuleViolationException> { rules.checkSlotAlignment(doctor, case.type, start) }.code shouldBe
                        case.expectedCode
                }
            }

            test("a weekday without a working window (Sunday) -> outside_working_hours") {
                val e =
                    shouldThrow<RuleViolationException> {
                        rules.checkSlotAlignment(
                            doctor,
                            AppointmentType.Consultation,
                            at(SUNDAY, 10, 0),
                        )
                    }
                e.code shouldBe "outside_working_hours"
                e.detail shouldContain "SUNDAY"
            }

            test("ending one minute after closing is rejected while ending exactly at closing is allowed") {
                val closesAt1659 = practitioner(schedule = weekdays("09:00", "16:59"))
                val closesAt1700 = practitioner(schedule = weekdays("09:00", "17:00"))
                val start = at(TUESDAY, 16, 45)
                shouldNotThrowAny { rules.checkSlotAlignment(closesAt1700, AppointmentType.FollowUp, start) }
                shouldThrow<RuleViolationException> { rules.checkSlotAlignment(closesAt1659, AppointmentType.FollowUp, start) }
                    .code shouldBe "outside_working_hours"
            }

            test("property: every start on the 15-minute grid whose consultation ends by closing time is aligned") {
                checkAll(Arb.int(0..30)) { k ->
                    val start = at(TUESDAY, 9, 0).plus(Duration.ofMinutes(15L * k))
                    shouldNotThrowAny { rules.checkSlotAlignment(doctor, AppointmentType.Consultation, start) }
                }
            }

            test("property: any start strictly between grid points is slot_misaligned") {
                checkAll(Arb.int(0..30), Arb.int(1..14)) { k, offsetMinutes ->
                    val start = at(TUESDAY, 9, 0).plus(Duration.ofMinutes(15L * k + offsetMinutes))
                    shouldThrow<RuleViolationException> { rules.checkSlotAlignment(doctor, AppointmentType.FollowUp, start) }
                        .code shouldBe "slot_misaligned"
                }
            }

            test("slot boundaries are counted from the window start, not from the hour") {
                val opensAt0910 = practitioner(schedule = weekdays("09:10", "17:00"))
                shouldNotThrowAny { rules.checkSlotAlignment(opensAt0910, AppointmentType.Consultation, at(TUESDAY, 9, 10)) }
                shouldNotThrowAny { rules.checkSlotAlignment(opensAt0910, AppointmentType.Consultation, at(TUESDAY, 9, 25)) }
                shouldThrow<RuleViolationException> {
                    rules.checkSlotAlignment(
                        opensAt0910,
                        AppointmentType.Consultation,
                        at(TUESDAY, 9, 15),
                    )
                }.code shouldBe "slot_misaligned"
            }
        }

        context("Rule 2 - no overlap including the buffer (409 slot_taken)") {
            val buffered = practitioner(bufferMinutes = 10)
            val existing = appointment(buffered, patient(), at(TUESDAY, 10, 0)) // 10:00-10:30, padded to 09:50-10:40

            data class Case(
                val label: String,
                val start: Instant,
                val taken: Boolean,
            )
            withData(
                nameFn = { "${it.label} -> ${if (it.taken) "slot_taken" else "allowed"}" },
                Case("new start exactly at existing.end + buffer (10:40)", at(TUESDAY, 10, 40), taken = false),
                Case("new start one minute inside the trailing buffer (10:39)", at(TUESDAY, 10, 39), taken = true),
                Case("new start at existing.end without buffer (10:30)", at(TUESDAY, 10, 30), taken = true),
                Case("new end exactly at existing.start - buffer (09:20-09:50)", at(TUESDAY, 9, 20), taken = false),
                Case("new end one minute inside the leading buffer (09:21-09:51)", at(TUESDAY, 9, 21), taken = true),
                Case("identical slot", at(TUESDAY, 10, 0), taken = true),
            ) { case ->
                val end = case.start.plus(AppointmentType.Consultation.duration)
                if (case.taken) {
                    shouldThrow<ConflictException> { rules.checkNoOverlap(buffered, listOf(existing), case.start, end) }.code shouldBe
                        "slot_taken"
                } else {
                    shouldNotThrowAny { rules.checkNoOverlap(buffered, listOf(existing), case.start, end) }
                }
            }

            test("with bufferMinutes = 0 two back-to-back appointments touch without overlapping") {
                val first = appointment(doctor, patient(), at(TUESDAY, 10, 0))
                val start = at(TUESDAY, 10, 30)
                shouldNotThrowAny { rules.checkNoOverlap(doctor, listOf(first), start, start.plus(Duration.ofMinutes(30))) }
            }

            withData(
                nameFn = { (status, blocks) -> "existing ${status.describe()} appointment ${if (blocks) "blocks" else "frees"} the slot" },
                AppointmentStatus.Booked to true,
                AppointmentStatus.CheckedIn to true,
                AppointmentStatus.Completed to true,
                AppointmentStatus.NoShow to false,
                AppointmentStatus.Cancelled(Actor.Patient, late = false) to false,
                AppointmentStatus.Cancelled(Actor.Clinic, late = false) to false,
            ) { (status, blocks) ->
                val existing = appointment(doctor, patient(), at(TUESDAY, 10, 0), status = status)
                val start = at(TUESDAY, 10, 0)
                val end = start.plus(Duration.ofMinutes(30))
                if (blocks) {
                    shouldThrow<ConflictException> { rules.checkNoOverlap(doctor, listOf(existing), start, end) }
                } else {
                    shouldNotThrowAny { rules.checkNoOverlap(doctor, listOf(existing), start, end) }
                }
            }

            test("property: interval intersection is symmetric and matches the half-open definition") {
                val base = at(TUESDAY, 9, 0)
                checkAll(Arb.long(0L..600L), Arb.long(0L..600L), Arb.long(1L..120L)) { s1, s2, d2 ->
                    val a = appointment(doctor, pat, base.plus(Duration.ofMinutes(s1)))
                    val bStart = base.plus(Duration.ofMinutes(s2))
                    val bEnd = bStart.plus(Duration.ofMinutes(d2))
                    a.intersects(bStart, bEnd) shouldBe (a.start.isBefore(bEnd) && bStart.isBefore(a.end))
                    val b = appointment(doctor, pat, bStart)
                    b.intersects(a.start, a.end) shouldBe a.intersects(b.start, b.end)
                }
            }
        }

        context("Rule 3 - time off (422 practitioner_unavailable)") {
            val lunch = timeOff(doctor, at(TUESDAY, 12, 0), at(TUESDAY, 13, 0), reason = "Lunch")

            test("an appointment overlapping the block is rejected with the reason in the detail") {
                val e = shouldThrow<RuleViolationException> { rules.checkTimeOff(listOf(lunch), at(TUESDAY, 12, 30), at(TUESDAY, 13, 0)) }
                e.code shouldBe "practitioner_unavailable"
                e.detail shouldContain "Lunch"
            }

            test("an appointment ending exactly when the block starts is allowed") {
                shouldNotThrowAny { rules.checkTimeOff(listOf(lunch), at(TUESDAY, 11, 30), at(TUESDAY, 12, 0)) }
            }

            test("an appointment starting exactly when the block ends is allowed") {
                shouldNotThrowAny { rules.checkTimeOff(listOf(lunch), at(TUESDAY, 13, 0), at(TUESDAY, 13, 30)) }
            }
        }

        context("Rule 4 - booking horizon (422 outside_booking_horizon)") {
            data class Case(
                val label: String,
                val start: Instant,
                val allowed: Boolean,
            )
            withData(
                nameFn = { "${it.label} -> ${if (it.allowed) "allowed" else "outside_booking_horizon"}" },
                Case("start == now", NOW, allowed = false),
                Case("start one second in the past", NOW.minusSeconds(1), allowed = false),
                Case("start one second in the future", NOW.plusSeconds(1), allowed = true),
                Case("start exactly now + 60 days", NOW.plus(Duration.ofDays(60)), allowed = true),
                Case("start now + 60 days + 1 minute", NOW.plus(Duration.ofDays(60)).plus(Duration.ofMinutes(1)), allowed = false),
            ) { case ->
                if (case.allowed) {
                    shouldNotThrowAny { rules.checkBookingHorizon(case.start) }
                } else {
                    shouldThrow<RuleViolationException> { rules.checkBookingHorizon(case.start) }.code shouldBe "outside_booking_horizon"
                }
            }

            test("today and lastBookableDate are evaluated in the clinic zone") {
                rules.today() shouldBe MONDAY
                rules.lastBookableDate() shouldBe MONDAY.plusDays(60)
            }
        }

        context("Rule 5 - patient constraints (409 patient_conflict)") {
            val other = practitioner(name = "Dr. Other")
            val withDoctorAt10 = appointment(doctor, pat, at(TUESDAY, 10, 0))

            test("a second appointment with the same practitioner on the same clinic day is rejected") {
                shouldThrow<ConflictException> {
                    rules.checkPatientConstraints(pat, listOf(withDoctorAt10), doctor.id, at(TUESDAY, 15, 0), at(TUESDAY, 15, 30))
                }.code shouldBe "patient_conflict"
            }

            test("an overlapping appointment with another practitioner is rejected") {
                shouldThrow<ConflictException> {
                    rules.checkPatientConstraints(pat, listOf(withDoctorAt10), other.id, at(TUESDAY, 10, 15), at(TUESDAY, 10, 45))
                }.code shouldBe "patient_conflict"
            }

            test("another practitioner at a different time on the same day is allowed") {
                shouldNotThrowAny {
                    rules.checkPatientConstraints(
                        pat,
                        listOf(withDoctorAt10),
                        other.id,
                        at(TUESDAY, 11, 0),
                        at(TUESDAY, 11, 30),
                    )
                }
            }

            test("the same practitioner on the next day is allowed") {
                val wednesday = TUESDAY.plusDays(1)
                shouldNotThrowAny {
                    rules.checkPatientConstraints(
                        pat,
                        listOf(withDoctorAt10),
                        doctor.id,
                        at(wednesday, 10, 0),
                        at(wednesday, 10, 30),
                    )
                }
            }

            test("cancelled and no-show appointments do not count") {
                val cancelled = withDoctorAt10.copy(status = AppointmentStatus.Cancelled(Actor.Patient, late = false))
                val noShow = withDoctorAt10.copy(status = AppointmentStatus.NoShow)
                shouldNotThrowAny {
                    rules.checkPatientConstraints(
                        pat,
                        listOf(cancelled, noShow),
                        doctor.id,
                        at(TUESDAY, 10, 0),
                        at(TUESDAY, 10, 30),
                    )
                }
            }
        }

        context("Rule 6 - daily capacity (409 daily_capacity_reached)") {
            val twoPerDay = practitioner(maxAppointmentsPerDay = 2)
            val first = appointment(twoPerDay, patient(), at(TUESDAY, 9, 0))
            val second = appointment(twoPerDay, patient(), at(TUESDAY, 10, 0))

            test("the maximum is reached -> rejected") {
                shouldThrow<ConflictException> { rules.checkDailyCapacity(twoPerDay, listOf(first, second), at(TUESDAY, 11, 0)) }
                    .code shouldBe "daily_capacity_reached"
            }

            test("a cancelled appointment frees capacity, a no-show does not") {
                val cancelled = second.copy(status = AppointmentStatus.Cancelled(Actor.Clinic, late = false))
                shouldNotThrowAny { rules.checkDailyCapacity(twoPerDay, listOf(first, cancelled), at(TUESDAY, 11, 0)) }
                val noShow = second.copy(status = AppointmentStatus.NoShow)
                shouldThrow<ConflictException> { rules.checkDailyCapacity(twoPerDay, listOf(first, noShow), at(TUESDAY, 11, 0)) }
            }

            test("appointments on other days do not count") {
                val yesterday = appointment(twoPerDay, patient(), at(MONDAY, 9, 0))
                shouldNotThrowAny { rules.checkDailyCapacity(twoPerDay, listOf(first, yesterday), at(TUESDAY, 11, 0)) }
            }
        }

        context("Rule 8 - blocked patients (403 patient_blocked)") {
            test("a patient blocked until the future cannot book; the block end travels with the exception") {
                val until = NOW.plus(Duration.ofDays(3))
                shouldThrow<PatientBlockedException> { rules.checkPatientNotBlocked(patient(blockedUntil = until)) }.blockedUntil shouldBe
                    until
            }

            test("at blockedUntil exactly the patient is free again") {
                shouldNotThrowAny { rules.checkPatientNotBlocked(patient(blockedUntil = NOW)) }
            }

            test("a patient that was never blocked passes") {
                shouldNotThrowAny { rules.checkPatientNotBlocked(patient()) }
            }
        }

        context("Rule 8 - no-show timing (409 no_show_before_start)") {
            test("before the start time -> rejected") {
                shouldThrow<ConflictException> { rules.checkNoShowAllowed(appointment(doctor, pat, NOW.plus(Duration.ofHours(1)))) }
                    .code shouldBe "no_show_before_start"
            }

            test("exactly at the start time -> still rejected (start must have passed)") {
                shouldThrow<ConflictException> { rules.checkNoShowAllowed(appointment(doctor, pat, NOW)) }
            }

            test("after the start time -> allowed") {
                shouldNotThrowAny { rules.checkNoShowAllowed(appointment(doctor, pat, NOW.minusSeconds(1))) }
            }
        }

        context("validateBooking runs every rule") {
            test("a clean context passes") {
                shouldNotThrowAny { rules.validateBooking(context(doctor, pat, at(TUESDAY, 10, 0))) }
            }

            test("the block check comes first, before the horizon") {
                val blocked = patient(blockedUntil = NOW.plus(Duration.ofDays(1)))
                shouldThrow<PatientBlockedException> { rules.validateBooking(context(doctor, blocked, NOW.minusSeconds(1))) }
            }

            test("each later rule is reachable through validateBooking") {
                shouldThrow<RuleViolationException> { rules.validateBooking(context(doctor, pat, at(TUESDAY, 8, 0))) }.code shouldBe
                    "outside_working_hours"
                val lunch = timeOff(doctor, at(TUESDAY, 12, 0), at(TUESDAY, 13, 0))
                shouldThrow<RuleViolationException> {
                    rules.validateBooking(
                        context(doctor, pat, at(TUESDAY, 12, 0), timeOff = listOf(lunch)),
                    )
                }.code shouldBe "practitioner_unavailable"
                val taken = appointment(doctor, patient(), at(TUESDAY, 10, 0))
                shouldThrow<ConflictException> {
                    rules.validateBooking(
                        context(doctor, pat, at(TUESDAY, 10, 0), practitionerAppointments = listOf(taken)),
                    )
                }.code shouldBe "slot_taken"
                val onePerDay = practitioner(maxAppointmentsPerDay = 1)
                val full = appointment(onePerDay, patient(), at(TUESDAY, 9, 0))
                shouldThrow<ConflictException> {
                    rules.validateBooking(context(onePerDay, pat, at(TUESDAY, 10, 0), practitionerAppointments = listOf(full)))
                }.code shouldBe "daily_capacity_reached"
                val elsewhere = appointment(practitioner(), pat, at(TUESDAY, 10, 0))
                shouldThrow<ConflictException> {
                    rules.validateBooking(
                        context(doctor, pat, at(TUESDAY, 10, 0), patientAppointments = listOf(elsewhere)),
                    )
                }.code shouldBe "patient_conflict"
            }
        }

        context("availability") {
            test("a plain Tuesday on a 15-minute grid offers 31 consultation starts from 09:00 to 16:30") {
                val slots = rules.availableStarts(doctor, TUESDAY, AppointmentType.Consultation, emptyList(), emptyList())
                slots shouldHaveSize 31
                slots.first() shouldBe at(TUESDAY, 9, 0)
                slots.last() shouldBe at(TUESDAY, 16, 30)
            }

            test("starts whose appointment would end after closing are filtered (procedure: last start 16:00)") {
                val slots = rules.availableStarts(doctor, TUESDAY, AppointmentType.Procedure, emptyList(), emptyList())
                slots shouldHaveSize 29
                slots.last() shouldBe at(TUESDAY, 16, 0)
            }

            test("a day without a working window has no slots") {
                rules.availableStarts(doctor, SUNDAY, AppointmentType.Consultation, emptyList(), emptyList()).shouldBeEmpty()
            }

            test("a full-day time-off block empties the day") {
                val holiday = timeOff(doctor, at(TUESDAY, 0, 0), at(TUESDAY.plusDays(1), 0, 0), reason = "Holiday")
                rules.availableStarts(doctor, TUESDAY, AppointmentType.Consultation, emptyList(), listOf(holiday)).shouldBeEmpty()
            }

            test("existing appointments remove their own slot and the buffered neighbours") {
                val buffered = practitioner(bufferMinutes = 10)
                val existing = appointment(buffered, patient(), at(TUESDAY, 10, 0)) // padded 09:50-10:40
                val slots = rules.availableStarts(buffered, TUESDAY, AppointmentType.Consultation, listOf(existing), emptyList())
                listOf(at(TUESDAY, 9, 30), at(TUESDAY, 9, 45), at(TUESDAY, 10, 0), at(TUESDAY, 10, 15), at(TUESDAY, 10, 30)).forEach {
                    slots shouldNotContain
                        it
                }
                slots.filter { it.isAfter(at(TUESDAY, 9, 0)) && it.isBefore(at(TUESDAY, 11, 30)) } shouldContainExactly
                    listOf(at(TUESDAY, 9, 15), at(TUESDAY, 10, 45), at(TUESDAY, 11, 0), at(TUESDAY, 11, 15))
            }

            test("once maxAppointmentsPerDay is reached the day is empty") {
                val onePerDay = practitioner(maxAppointmentsPerDay = 1)
                val existing = appointment(onePerDay, patient(), at(TUESDAY, 9, 0))
                rules.availableStarts(onePerDay, TUESDAY, AppointmentType.Consultation, listOf(existing), emptyList()).shouldBeEmpty()
            }

            test("slots already in the past today are not offered (now is Monday 09:00, so 09:15 is the first)") {
                val slots = rules.availableStarts(doctor, MONDAY, AppointmentType.Consultation, emptyList(), emptyList())
                slots.first() shouldBe at(MONDAY, 9, 15)
            }

            test("a window ending just before midnight stops cleanly instead of wrapping") {
                val night = practitioner(schedule = WeeklySchedule(mapOf(DayOfWeek.TUESDAY to window("22:00", "23:59"))))
                val slots = rules.availableStarts(night, TUESDAY, AppointmentType.Consultation, emptyList(), emptyList())
                slots shouldContainExactly (0..5).map { at(TUESDAY, 22, 0).plus(Duration.ofMinutes(15L * it)) }
            }
        }
    })

private fun AppointmentStatus.describe(): String =
    when (this) {
        is AppointmentStatus.Cancelled -> "Cancelled by ${by.name.lowercase()}"
        else -> label
    }
