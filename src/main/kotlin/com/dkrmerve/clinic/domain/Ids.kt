package com.dkrmerve.clinic.domain

import java.util.UUID

/**
 * Typed identifiers. Value classes cost nothing at runtime (they compile down to a plain UUID)
 * but make it impossible to pass a PatientId where a PractitionerId is expected.
 */
@JvmInline
value class PractitionerId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): PractitionerId = PractitionerId(UUID.randomUUID())

        fun parse(raw: String): PractitionerId = PractitionerId(UUID.fromString(raw))
    }
}

@JvmInline
value class PatientId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): PatientId = PatientId(UUID.randomUUID())

        fun parse(raw: String): PatientId = PatientId(UUID.fromString(raw))
    }
}

@JvmInline
value class AppointmentId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): AppointmentId = AppointmentId(UUID.randomUUID())

        fun parse(raw: String): AppointmentId = AppointmentId(UUID.fromString(raw))
    }
}

@JvmInline
value class TimeOffId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): TimeOffId = TimeOffId(UUID.randomUUID())
    }
}

@JvmInline
value class WaitlistEntryId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): WaitlistEntryId = WaitlistEntryId(UUID.randomUUID())

        fun parse(raw: String): WaitlistEntryId = WaitlistEntryId(UUID.fromString(raw))
    }
}
