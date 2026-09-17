package com.dkrmerve.clinic.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.time.Duration

/** Rule 7: patients cancel free >= 24h before, late between 24h and 2h, not at all under 2h; the clinic always may. */
class CancellationPolicyTest :
    FunSpec({
        val doctor = practitioner()
        val pat = patient()
        val start = at(TUESDAY, 10, 0)
        val appointment = appointment(doctor, pat, start)

        context("patient-initiated cancellation at exact boundaries") {
            data class Case(
                val label: String,
                val notice: Duration,
                val expectedLate: Boolean?,
            )
            withData(
                nameFn = {
                    "${it.label} -> ${it.expectedLate?.let { late ->
                        if (late) "late" else "free"
                    } ?: "cancellation_window_closed"}"
                },
                Case("48h before", Duration.ofHours(48), expectedLate = false),
                Case("exactly 24h before", Duration.ofHours(24), expectedLate = false),
                Case("24h minus 1s before", Duration.ofHours(24).minusSeconds(1), expectedLate = true),
                Case("3h before", Duration.ofHours(3), expectedLate = true),
                Case("exactly 2h before", Duration.ofHours(2), expectedLate = true),
                Case("2h minus 1s before", Duration.ofHours(2).minusSeconds(1), expectedLate = null),
                Case("1 minute before", Duration.ofMinutes(1), expectedLate = null),
                Case("after the start", Duration.ofMinutes(-10), expectedLate = null),
            ) { case ->
                val rules = rules(now = start.minus(case.notice))
                if (case.expectedLate == null) {
                    shouldThrow<RuleViolationException> { rules.cancellationOutcome(appointment, Actor.Patient) }.code shouldBe
                        "cancellation_window_closed"
                } else {
                    rules.cancellationOutcome(appointment, Actor.Patient) shouldBe
                        AppointmentStatus.Cancelled(Actor.Patient, late = case.expectedLate)
                }
            }
        }

        context("clinic-initiated cancellation") {
            test("1 minute before the start is allowed and never marked late") {
                val rules = rules(now = start.minus(Duration.ofMinutes(1)))
                rules.cancellationOutcome(appointment, Actor.Clinic) shouldBe AppointmentStatus.Cancelled(Actor.Clinic, late = false)
            }

            test("even after the start the clinic may cancel") {
                val rules = rules(now = start.plus(Duration.ofHours(1)))
                rules.cancellationOutcome(appointment, Actor.Clinic).late shouldBe false
            }
        }

        context("policy knobs are respected") {
            test("a policy with a 48h free window and 12h minimum notice moves both boundaries") {
                val policy =
                    SchedulingPolicy(freeCancellationNotice = Duration.ofHours(48), minimumCancellationNotice = Duration.ofHours(12))
                rules(
                    now = start.minus(Duration.ofHours(47)),
                    policy = policy,
                ).cancellationOutcome(appointment, Actor.Patient).late shouldBe
                    true
                rules(
                    now = start.minus(Duration.ofHours(48)),
                    policy = policy,
                ).cancellationOutcome(appointment, Actor.Patient).late shouldBe
                    false
                shouldThrow<RuleViolationException> {
                    rules(now = start.minus(Duration.ofHours(11)), policy = policy).cancellationOutcome(appointment, Actor.Patient)
                }
            }
        }
    })
