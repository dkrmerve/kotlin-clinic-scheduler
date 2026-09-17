package com.dkrmerve.clinic.domain

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Europe/Amsterdam switches to summer time on Sunday 2027-03-28 (02:00 -> 03:00, the hour does not exist)
 * and back on Sunday 2027-10-31 (03:00 -> 02:00, the hour 02:00-03:00 happens twice).
 */
class DstTest :
    FunSpec({
        val springForward: LocalDate = LocalDate.of(2027, 3, 28)
        val fallBack: LocalDate = LocalDate.of(2027, 10, 31)
        val dayClinic = practitioner(schedule = WeeklySchedule(mapOf(DayOfWeek.SUNDAY to window("09:00", "17:00"))))
        val nightClinic = practitioner(schedule = WeeklySchedule(mapOf(DayOfWeek.SUNDAY to window("01:00", "04:00"))))

        context("spring-forward day 2027-03-28 (02:00 does not exist)") {
            val rules = rules(now = Instant.parse("2027-03-27T12:00:00Z"))

            test("a 09:00-17:00 window still offers 31 consultation slots, the first at 09:00+02:00") {
                val slots = rules.availableStarts(dayClinic, springForward, AppointmentType.Consultation, emptyList(), emptyList())
                slots shouldHaveSize 31
                slots.first() shouldBe Instant.parse("2027-03-28T07:00:00Z")
                slots.first().atZone(ZONE).offset shouldBe ZoneOffset.ofHours(2)
                slots.last() shouldBe Instant.parse("2027-03-28T14:30:00Z")
            }

            test("every consecutive pair of slots is exactly 15 real minutes apart") {
                val slots = rules.availableStarts(dayClinic, springForward, AppointmentType.Consultation, emptyList(), emptyList())
                slots.zipWithNext().forEach { (a, b) -> Duration.between(a, b) shouldBe Duration.ofMinutes(15) }
            }

            test("an overnight 01:00-04:00 window skips the missing hour and yields no duplicate instants") {
                val slots = rules.availableStarts(nightClinic, springForward, AppointmentType.Consultation, emptyList(), emptyList())
                // 01:00, 01:15, 01:30 (+01:00) then 03:00, 03:15, 03:30 (+02:00); 01:45 would end at 03:15 wall time, fine too
                slots shouldContainExactly
                    listOf(
                        Instant.parse("2027-03-28T00:00:00Z"),
                        Instant.parse("2027-03-28T00:15:00Z"),
                        Instant.parse("2027-03-28T00:30:00Z"),
                        Instant.parse("2027-03-28T00:45:00Z"),
                        Instant.parse("2027-03-28T01:00:00Z"),
                        Instant.parse("2027-03-28T01:15:00Z"),
                        Instant.parse("2027-03-28T01:30:00Z"),
                    )
                slots.distinct() shouldHaveSize slots.size
            }

            test("a 30-minute appointment booked at 01:45+01:00 crosses the switch and ends at 03:15+02:00, 30 real minutes later") {
                val start = Instant.parse("2027-03-28T00:45:00Z")
                shouldNotThrowAny { rules.checkSlotAlignment(nightClinic, AppointmentType.Consultation, start) }
                val appointment = appointment(nightClinic, patient(), start)
                Duration.between(appointment.start, appointment.end) shouldBe Duration.ofMinutes(30)
                appointment.end
                    .atZone(ZONE)
                    .toLocalTime()
                    .toString() shouldBe "03:15"
                appointment.end.atZone(ZONE).offset shouldBe ZoneOffset.ofHours(2)
            }

            test("the last slot of the overnight window still respects the 04:00 closing time in instants") {
                val lastStart = Instant.parse("2027-03-28T01:30:00Z") // 03:30+02:00, ends 04:00+02:00
                shouldNotThrowAny { rules.checkSlotAlignment(nightClinic, AppointmentType.Consultation, lastStart) }
                val tooLate = Instant.parse("2027-03-28T01:45:00Z") // 03:45+02:00, would end 04:15
                shouldThrow<RuleViolationException> { rules.checkSlotAlignment(nightClinic, AppointmentType.Consultation, tooLate) }
                    .code shouldBe "outside_working_hours"
            }
        }

        context("fall-back day 2027-10-31 (02:00-03:00 happens twice)") {
            val rules = rules(now = Instant.parse("2027-10-30T12:00:00Z"))

            test("a 09:00-17:00 window offers the normal 31 consultation slots at +01:00") {
                val slots = rules.availableStarts(dayClinic, fallBack, AppointmentType.Consultation, emptyList(), emptyList())
                slots shouldHaveSize 31
                slots.first() shouldBe Instant.parse("2027-10-31T08:00:00Z")
                slots.first().atZone(ZONE).offset shouldBe ZoneOffset.ofHours(1)
            }

            test("an overnight 01:00-04:00 window offers both occurrences of 02:00 and 02:30 as distinct slots") {
                val slots = rules.availableStarts(nightClinic, fallBack, AppointmentType.Consultation, emptyList(), emptyList())
                val firstTwo = Instant.parse("2027-10-31T00:00:00Z") // 02:00+02:00
                val secondTwo = Instant.parse("2027-10-31T01:00:00Z") // 02:00+01:00
                slots.count { it == firstTwo } shouldBe 1
                slots.count { it == secondTwo } shouldBe 1
                // 01:00 .. 01:45 (+02), 02:00 .. 02:45 (+02), 02:00 .. 02:45 (+01), 03:00 .. 03:30 (+01)
                slots shouldHaveSize 4 + 4 + 4 + 3
                slots.zipWithNext().forEach { (a, b) -> Duration.between(a, b) shouldBe Duration.ofMinutes(15) }
            }

            test("appointments at the two occurrences of 02:00 do not overlap each other") {
                val first = appointment(nightClinic, patient(), Instant.parse("2027-10-31T00:00:00Z"))
                val secondStart = Instant.parse("2027-10-31T01:00:00Z")
                shouldNotThrowAny {
                    rules.checkNoOverlap(
                        nightClinic,
                        listOf(first),
                        secondStart,
                        secondStart.plus(Duration.ofMinutes(30)),
                    )
                }
                shouldNotThrowAny { rules.checkSlotAlignment(nightClinic, AppointmentType.Consultation, secondStart) }
            }

            test("an appointment starting at 02:45+02:00 ends at 02:15+01:00 and still lasts 30 real minutes") {
                val appointment = appointment(nightClinic, patient(), Instant.parse("2027-10-31T00:45:00Z"))
                Duration.between(appointment.start, appointment.end) shouldBe Duration.ofMinutes(30)
                appointment.end
                    .atZone(ZONE)
                    .toLocalTime()
                    .toString() shouldBe "02:15"
                appointment.end.atZone(ZONE).offset shouldBe ZoneOffset.ofHours(1)
            }

            test("the clinic day bounds cover 25 hours on the fall-back day") {
                val (start, end) = fallBack.dayBounds(ZONE)
                Duration.between(start, end) shouldBe Duration.ofHours(25)
                val (s2, e2) = springForward.dayBounds(ZONE)
                Duration.between(s2, e2) shouldBe Duration.ofHours(23)
            }
        }
    })
