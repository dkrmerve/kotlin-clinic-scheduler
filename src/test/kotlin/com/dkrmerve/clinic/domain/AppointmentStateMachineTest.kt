package com.dkrmerve.clinic.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

/** Rules 10 and 11: the legal transitions, and a history entry for each one. */
class AppointmentStateMachineTest :
    FunSpec({
        val doctor = practitioner()
        val pat = patient()
        val booked = appointment(doctor, pat, at(TUESDAY, 10, 0))
        val byPatient = AppointmentStatus.Cancelled(Actor.Patient, late = false)
        val byClinic = AppointmentStatus.Cancelled(Actor.Clinic, late = false)
        val allStatuses =
            listOf(
                AppointmentStatus.Booked,
                AppointmentStatus.CheckedIn,
                AppointmentStatus.Completed,
                AppointmentStatus.NoShow,
                byPatient,
                byClinic,
            )

        context("transition matrix") {
            val allowed =
                setOf(
                    AppointmentStatus.Booked to AppointmentStatus.CheckedIn,
                    AppointmentStatus.Booked to byPatient,
                    AppointmentStatus.Booked to byClinic,
                    AppointmentStatus.Booked to AppointmentStatus.NoShow,
                    AppointmentStatus.CheckedIn to AppointmentStatus.Completed,
                    AppointmentStatus.CheckedIn to byClinic,
                )
            val pairs = allStatuses.flatMap { from -> allStatuses.map { to -> from to to } }
            withData(nameFn = { (from, to) ->
                "${from.describe()} -> ${to.describe()}: ${if (from to to in allowed) "allowed" else "invalid_transition"}"
            }, pairs) { (from, to) ->
                val current = booked.copy(status = from)
                if (from to to in allowed) {
                    current.transitionTo(to, Actor.Clinic, NOW).status shouldBe to
                } else {
                    val e = shouldThrow<InvalidTransitionException> { current.transitionTo(to, Actor.Clinic, NOW) }
                    e.code shouldBe "invalid_transition"
                    e.detail shouldBe "Cannot move from ${from.label} to ${to.label}"
                }
            }

            test("CheckedIn -> Cancelled by patient is the one cancellation the state machine refuses") {
                AppointmentStatus.CheckedIn.canTransitionTo(byPatient) shouldBe false
                AppointmentStatus.CheckedIn.canTransitionTo(byClinic) shouldBe true
            }
        }

        context("history (rule 11)") {
            test("booking writes the first entry with no previous status") {
                val fresh =
                    Appointment.book(
                        doctor.id,
                        pat.id,
                        AppointmentType.FollowUp,
                        at(TUESDAY, 11, 0),
                        NOW,
                        Actor.Patient,
                        note = "via app",
                    )
                fresh.history shouldHaveSize 1
                fresh.history.single() shouldBe HistoryEntry(NOW, Actor.Patient, null, AppointmentStatus.Booked, "via app")
                fresh.end shouldBe fresh.start.plus(Duration.ofMinutes(15))
                fresh.promotedFromWaitlist shouldBe false
                fresh.version shouldBe 0
            }

            test("every transition appends (at, actor, from, to, note) in order") {
                val later = NOW.plus(Duration.ofHours(1))
                val done =
                    booked
                        .transitionTo(AppointmentStatus.CheckedIn, Actor.Clinic, NOW, "front desk")
                        .transitionTo(AppointmentStatus.Completed, Actor.Clinic, later)
                done.history shouldHaveSize 2
                done.history[0] shouldBe
                    HistoryEntry(NOW, Actor.Clinic, AppointmentStatus.Booked, AppointmentStatus.CheckedIn, "front desk")
                done.history[1] shouldBe HistoryEntry(later, Actor.Clinic, AppointmentStatus.CheckedIn, AppointmentStatus.Completed, null)
                done.history[1].note.shouldBeNull()
            }

            test("a refused transition leaves the appointment and its history untouched") {
                val completed = booked.copy(status = AppointmentStatus.Completed)
                shouldThrow<InvalidTransitionException> { completed.transitionTo(byClinic, Actor.Clinic, NOW) }
                completed.history shouldHaveSize 0
            }
        }

        context("status semantics") {
            withData(
                nameFn = { (status, blocks, counts) -> "${status.describe()}: blocksSlot=$blocks countsTowardsCapacity=$counts" },
                Triple(AppointmentStatus.Booked, true, true),
                Triple(AppointmentStatus.CheckedIn, true, true),
                Triple(AppointmentStatus.Completed, true, true),
                Triple(AppointmentStatus.NoShow, false, true),
                Triple(byPatient, false, false),
                Triple(byClinic, false, false),
            ) { (status, blocks, counts) ->
                status.blocksSlot shouldBe blocks
                status.countsTowardsCapacity shouldBe counts
            }

            test("labels are the wire representation") {
                allStatuses.map { it.label } shouldBe listOf("Booked", "CheckedIn", "Completed", "NoShow", "Cancelled", "Cancelled")
            }
        }
    })

private fun AppointmentStatus.describe(): String =
    when (this) {
        is AppointmentStatus.Cancelled -> "Cancelled(${by.name.lowercase()}${if (late) ", late" else ""})"
        else -> label
    }
