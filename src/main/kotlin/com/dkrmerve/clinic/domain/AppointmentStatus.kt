package com.dkrmerve.clinic.domain

/**
 * Lifecycle of an appointment. A sealed interface (rather than an enum) lets [Cancelled] carry data
 * while `when` stays exhaustive.
 */
sealed interface AppointmentStatus {
    val label: String

    data object Booked : AppointmentStatus {
        override val label = "Booked"
    }

    data object CheckedIn : AppointmentStatus {
        override val label = "CheckedIn"
    }

    data object Completed : AppointmentStatus {
        override val label = "Completed"
    }

    data object NoShow : AppointmentStatus {
        override val label = "NoShow"
    }

    data class Cancelled(
        val by: Actor,
        val late: Boolean,
    ) : AppointmentStatus {
        override val label = "Cancelled"
    }

    /** Still occupies the practitioner's calendar (rule 2: overlap). */
    val blocksSlot: Boolean
        get() =
            when (this) {
                Booked, CheckedIn, Completed -> true
                NoShow, is Cancelled -> false
            }

    /** Counts towards the practitioner's daily maximum (rule 6). A no-show still consumed the slot. */
    val countsTowardsCapacity: Boolean
        get() =
            when (this) {
                Booked, CheckedIn, Completed, NoShow -> true
                is Cancelled -> false
            }

    /** Rule 10: the only legal transitions. */
    fun canTransitionTo(next: AppointmentStatus): Boolean =
        when (this) {
            Booked -> next is CheckedIn || next is Cancelled || next is NoShow
            CheckedIn -> next is Completed || (next is Cancelled && next.by == Actor.Clinic)
            Completed, NoShow, is Cancelled -> false
        }
}
