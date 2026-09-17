package com.dkrmerve.clinic.api

import com.dkrmerve.clinic.application.AddTimeOff
import com.dkrmerve.clinic.application.BookAppointment
import com.dkrmerve.clinic.application.CreatePatient
import com.dkrmerve.clinic.application.CreatePractitioner
import com.dkrmerve.clinic.application.JoinWaitlist
import com.dkrmerve.clinic.application.Page
import com.dkrmerve.clinic.application.Reschedule
import com.dkrmerve.clinic.domain.Actor
import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.AppointmentId
import com.dkrmerve.clinic.domain.AppointmentStatus
import com.dkrmerve.clinic.domain.AppointmentType
import com.dkrmerve.clinic.domain.HistoryEntry
import com.dkrmerve.clinic.domain.Patient
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.Practitioner
import com.dkrmerve.clinic.domain.PractitionerId
import com.dkrmerve.clinic.domain.TimeOff
import com.dkrmerve.clinic.domain.WaitlistEntry
import com.dkrmerve.clinic.domain.WeeklySchedule
import com.dkrmerve.clinic.domain.WorkingWindow
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException

/*
 * Wire types. Deliberately separate from the domain classes so the JSON contract can evolve without touching
 * business rules and the domain stays free of serialization annotations. Times are strings on the wire;
 * ApiTime renders and parses them, the Validator reports bad ones per field.
 */

@Serializable
data class WorkingWindowDto(
    val start: String,
    val end: String,
)

@Serializable
data class CreatePractitionerRequest(
    val name: String = "",
    val specialty: String = "",
    val slotMinutes: Int = 0,
    val bufferMinutes: Int = 0,
    val maxAppointmentsPerDay: Int = 0,
    /** Keys are weekday names (MONDAY .. SUNDAY, any case). Missing days are days off. */
    val schedule: Map<String, WorkingWindowDto> = emptyMap(),
) {
    fun toCommand(): CreatePractitioner =
        Validator.validate {
            check("name", name.isNotBlank()) { "must not be blank" }
            check("specialty", specialty.isNotBlank()) { "must not be blank" }
            check("slotMinutes", slotMinutes in Practitioner.ALLOWED_SLOT_MINUTES) { "must be one of ${Practitioner.ALLOWED_SLOT_MINUTES}" }
            check("bufferMinutes", bufferMinutes >= 0) { "must not be negative" }
            check("maxAppointmentsPerDay", maxAppointmentsPerDay >= 1) { "must be at least 1" }
            check("schedule", schedule.isNotEmpty()) { "must contain at least one weekday" }
            val windows =
                schedule.mapNotNull { (rawDay, window) ->
                    val field = "schedule.$rawDay"
                    val day =
                        parse(field, rawDay, "unknown weekday; expected one of ${allowedValues<DayOfWeek>()}") { enumOrNull<DayOfWeek>(it) }
                    val start = parse("$field.start", window.start, "must be a time like 09:00") { localTimeOrNull(it) }
                    val end = parse("$field.end", window.end, "must be a time like 17:00") { localTimeOrNull(it) }
                    if (day == null || start == null || end == null) return@mapNotNull null
                    check(field, start.isBefore(end)) { "start must be before end" }
                    if (start.isBefore(end)) day to WorkingWindow(start, end) else null
                }
            throwIfInvalid()
            CreatePractitioner(
                name = name,
                specialty = specialty,
                schedule = WeeklySchedule(windows.toMap()),
                slotMinutes = slotMinutes,
                bufferMinutes = bufferMinutes,
                maxAppointmentsPerDay = maxAppointmentsPerDay,
            )
        }
}

@Serializable
data class PractitionerResponse(
    val id: String,
    val name: String,
    val specialty: String,
    val slotMinutes: Int,
    val bufferMinutes: Int,
    val maxAppointmentsPerDay: Int,
    val schedule: Map<String, WorkingWindowDto>,
)

fun Practitioner.toResponse() =
    PractitionerResponse(
        id = id.toString(),
        name = name,
        specialty = specialty,
        slotMinutes = slotMinutes,
        bufferMinutes = bufferMinutes,
        maxAppointmentsPerDay = maxAppointmentsPerDay,
        schedule =
            schedule.windows.entries
                .sortedBy { it.key }
                .associate { (day, window) -> day.name to WorkingWindowDto(window.start.toString(), window.end.toString()) },
    )

@Serializable
data class TimeOffRequest(
    val from: String = "",
    val to: String = "",
    val reason: String = "",
) {
    fun toCommand(
        practitionerId: PractitionerId,
        time: ApiTime,
    ): AddTimeOff =
        Validator.validate {
            val fromAt = parse("from", from, OFFSET_DATE_TIME_HINT, time::parseOrNull)
            val toAt = parse("to", to, OFFSET_DATE_TIME_HINT, time::parseOrNull)
            check("reason", reason.isNotBlank()) { "must not be blank" }
            check("reason", reason.length <= 500) { "must be at most 500 characters" }
            if (fromAt != null && toAt != null) check("to", fromAt.isBefore(toAt)) { "must be after from" }
            throwIfInvalid()
            AddTimeOff(practitionerId, fromAt!!, toAt!!, reason)
        }
}

@Serializable
data class TimeOffResponse(
    val id: String,
    val practitionerId: String,
    val from: String,
    val to: String,
    val reason: String,
)

fun TimeOff.toResponse(time: ApiTime) = TimeOffResponse(id.toString(), practitionerId.toString(), time.format(from), time.format(to), reason)

@Serializable
data class AvailabilityResponse(
    val practitionerId: String,
    val date: String,
    val type: String,
    val durationMinutes: Long,
    val slots: List<String>,
)

@Serializable
data class CreatePatientRequest(
    val name: String = "",
    val email: String = "",
) {
    fun toCommand(): CreatePatient =
        Validator.validate {
            check("name", name.isNotBlank()) { "must not be blank" }
            check("name", name.length <= 200) { "must be at most 200 characters" }
            check("email", EMAIL.matches(email.trim())) { "must be a valid email address" }
            throwIfInvalid()
            CreatePatient(name, email)
        }
}

@Serializable
data class PatientResponse(
    val id: String,
    val name: String,
    val email: String,
    val lateCancellations: Int,
    val noShows: List<String>,
    val blockedUntil: String?,
    /** Derived: blockedUntil lies in the future. */
    val blocked: Boolean,
)

fun Patient.toResponse(
    time: ApiTime,
    now: Instant,
) = PatientResponse(
    id = id.toString(),
    name = name,
    email = email,
    lateCancellations = lateCancellations,
    noShows = noShows.map(time::format),
    blockedUntil = blockedUntil?.let(time::format),
    blocked = isBlockedAt(now),
)

@Serializable
data class BookAppointmentRequest(
    val practitionerId: String = "",
    val patientId: String = "",
    val type: String = "",
    val start: String = "",
) {
    fun toCommand(
        actor: Actor,
        time: ApiTime,
    ): BookAppointment =
        Validator.validate {
            val practitioner = parse("practitionerId", practitionerId, UUID_HINT, ::uuidOrNull)
            val patient = parse("patientId", patientId, UUID_HINT, ::uuidOrNull)
            val appointmentType = parse("type", type, TYPE_HINT) { enumOrNull<AppointmentType>(it) }
            val startAt = parse("start", start, OFFSET_DATE_TIME_HINT, time::parseOrNull)
            throwIfInvalid()
            BookAppointment(PractitionerId(practitioner!!), PatientId(patient!!), appointmentType!!, startAt!!, actor)
        }
}

@Serializable
data class HistoryEntryResponse(
    val at: String,
    val actor: String,
    val from: String?,
    val to: String,
    val cancelledBy: String?,
    val late: Boolean?,
    val note: String?,
)

fun HistoryEntry.toResponse(time: ApiTime) =
    HistoryEntryResponse(
        at = time.format(at),
        actor = actor.name.lowercase(),
        from = from?.label,
        to = to.label,
        cancelledBy = (to as? AppointmentStatus.Cancelled)?.by?.name?.lowercase(),
        late = (to as? AppointmentStatus.Cancelled)?.late,
        note = note,
    )

@Serializable
data class AppointmentResponse(
    val id: String,
    val practitionerId: String,
    val patientId: String,
    val type: String,
    val start: String,
    val end: String,
    val status: String,
    val cancelledBy: String?,
    val lateCancellation: Boolean?,
    val createdAt: String,
    val promotedFromWaitlist: Boolean,
    val version: Int,
    val history: List<HistoryEntryResponse>,
)

fun Appointment.toResponse(time: ApiTime) =
    AppointmentResponse(
        id = id.toString(),
        practitionerId = practitionerId.toString(),
        patientId = patientId.toString(),
        type = type.name,
        start = time.format(start),
        end = time.format(end),
        status = status.label,
        cancelledBy = (status as? AppointmentStatus.Cancelled)?.by?.name?.lowercase(),
        lateCancellation = (status as? AppointmentStatus.Cancelled)?.late,
        createdAt = time.format(createdAt),
        promotedFromWaitlist = promotedFromWaitlist,
        version = version,
        history = history.map { it.toResponse(time) },
    )

@Serializable
data class CancelRequest(
    val note: String? = null,
) {
    fun validated(): String? =
        Validator.validate {
            check("note", note == null || note.length <= 500) { "must be at most 500 characters" }
            note?.takeIf { it.isNotBlank() }
        }
}

@Serializable
data class CancellationResponse(
    val appointment: AppointmentResponse,
    val waitlistPromotion: AppointmentResponse?,
)

@Serializable
data class RescheduleRequest(
    val newStart: String = "",
    val practitionerId: String? = null,
    val type: String? = null,
) {
    fun toCommand(
        appointmentId: AppointmentId,
        actor: Actor,
        time: ApiTime,
    ): Reschedule =
        Validator.validate {
            val startAt = parse("newStart", newStart, OFFSET_DATE_TIME_HINT, time::parseOrNull)
            val practitioner = practitionerId?.let { parse("practitionerId", it, UUID_HINT, ::uuidOrNull) }
            val newType = type?.let { raw -> parse("type", raw, TYPE_HINT) { enumOrNull<AppointmentType>(it) } }
            throwIfInvalid()
            Reschedule(appointmentId, startAt!!, practitioner?.let(::PractitionerId), newType, actor)
        }
}

@Serializable
data class RescheduleResponse(
    val noOp: Boolean,
    val previous: AppointmentResponse,
    val replacement: AppointmentResponse,
    val waitlistPromotion: AppointmentResponse?,
)

@Serializable
data class JoinWaitlistRequest(
    val practitionerId: String = "",
    val patientId: String = "",
    val date: String = "",
    val type: String = "",
) {
    fun toCommand(time: ApiTime): JoinWaitlist =
        Validator.validate {
            val practitioner = parse("practitionerId", practitionerId, UUID_HINT, ::uuidOrNull)
            val patient = parse("patientId", patientId, UUID_HINT, ::uuidOrNull)
            val day = parse("date", date, DATE_HINT, time::parseDateOrNull)
            val appointmentType = parse("type", type, TYPE_HINT) { enumOrNull<AppointmentType>(it) }
            throwIfInvalid()
            JoinWaitlist(PractitionerId(practitioner!!), PatientId(patient!!), day!!, appointmentType!!)
        }
}

@Serializable
data class WaitlistEntryResponse(
    val id: String,
    val practitionerId: String,
    val patientId: String,
    val date: String,
    val type: String,
    val createdAt: String,
    val status: String,
    val fulfilledBy: String?,
)

fun WaitlistEntry.toResponse(time: ApiTime) =
    WaitlistEntryResponse(
        id = id.toString(),
        practitionerId = practitionerId.toString(),
        patientId = patientId.toString(),
        date = date.toString(),
        type = type.name,
        createdAt = time.format(createdAt),
        status = status.name,
        fulfilledBy = fulfilledBy?.toString(),
    )

@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
)

fun <T, R> Page<T>.toResponse(transform: (T) -> R) = PageResponse(items.map(transform), page, pageSize, total)

@Serializable
data class TokenRequest(
    val subject: String = "",
    val role: String = "",
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
)

@Serializable
data class HealthResponse(
    val status: String,
    val database: String? = null,
)

private const val UUID_HINT = "must be a UUID"
private const val OFFSET_DATE_TIME_HINT = "must be an ISO-8601 date-time with offset, e.g. 2026-10-05T09:30:00+02:00"
private const val DATE_HINT = "must be an ISO-8601 date, e.g. 2026-10-05"
private val TYPE_HINT = "must be one of ${allowedValues<AppointmentType>()}"
private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

private fun localTimeOrNull(raw: String): LocalTime? =
    try {
        LocalTime.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }
