package com.dkrmerve.clinic.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/** Rule 8: three no-shows inside a rolling 90-day window (inclusive) block the patient for 30 days from now. */
class NoShowPolicyTest :
    FunSpec({
        val policy = SchedulingPolicy.DEFAULT
        val first: Instant = Instant.parse("2027-01-05T09:00:00Z")
        val second: Instant = first.plus(Duration.ofDays(30))

        context("rolling 90-day window boundaries") {
            data class Case(
                val label: String,
                val thirdOffsetDays: Long,
                val blocked: Boolean,
            )
            withData(
                nameFn = { "third no-show ${it.label} after the first -> ${if (it.blocked) "blocked" else "not blocked"}" },
                Case("exactly 90 days", 90, blocked = true),
                Case("89 days", 89, blocked = true),
                Case("91 days (first drops out of the window)", 91, blocked = false),
            ) { case ->
                val third = first.plus(Duration.ofDays(case.thirdOffsetDays))
                val now = third.plus(Duration.ofHours(1))
                val patient = patient(noShows = listOf(first, second)).recordNoShow(third, now, policy)
                patient.noShows shouldContainExactly listOf(first, second, third)
                if (case.blocked) {
                    patient.blockedUntil shouldBe now.plus(Duration.ofDays(30))
                } else {
                    patient.blockedUntil.shouldBeNull()
                }
            }
        }

        test("two no-shows never block") {
            patient(noShows = listOf(first)).recordNoShow(second, second, policy).blockedUntil.shouldBeNull()
        }

        test("the block is computed from history, so a retroactively recorded older no-show still triggers it") {
            val recentA = first.plus(Duration.ofDays(60))
            val recentB = first.plus(Duration.ofDays(70))
            val now = first.plus(Duration.ofDays(75))
            // The clinic only now records that the patient also missed the appointment on `first` (75 days ago).
            val patient = patient(noShows = listOf(recentA, recentB)).recordNoShow(first, now, policy)
            patient.noShows shouldContainExactly listOf(first, recentA, recentB)
            patient.blockedUntil shouldBe now.plus(Duration.ofDays(30))
        }

        test("an existing block is kept when a later no-show does not reach the limit again") {
            val until = second.plus(Duration.ofDays(10))
            val patient = patient(noShows = listOf(first), blockedUntil = until).recordNoShow(second, second, policy)
            patient.blockedUntil shouldBe until
        }

        context("isBlockedAt boundaries") {
            val until = Instant.parse("2027-02-01T00:00:00Z")
            val blocked = patient(blockedUntil = until)

            test("one second before blockedUntil -> blocked") { blocked.isBlockedAt(until.minusSeconds(1)) shouldBe true }
            test("at blockedUntil exactly -> free again") { blocked.isBlockedAt(until) shouldBe false }
            test("never blocked -> free") { patient().isBlockedAt(until) shouldBe false }
        }

        test("late cancellations are counted, not blocked") {
            patient().recordLateCancellation().recordLateCancellation().lateCancellations shouldBe 2
        }

        test("a stricter policy (limit 2, window 7 days, block 1 day) is honoured") {
            val strict = SchedulingPolicy(noShowLimit = 2, noShowWindow = Duration.ofDays(7), blockDuration = Duration.ofDays(1))
            val now = first.plus(Duration.ofDays(3))
            patient(noShows = listOf(first)).recordNoShow(now, now, strict).blockedUntil shouldBe now.plus(Duration.ofDays(1))
            patient(noShows = listOf(first)).recordNoShow(first.plus(Duration.ofDays(8)), now, strict).blockedUntil.shouldBeNull()
        }
    })
