package com.dkrmerve.clinic.api

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Instants travel as ISO-8601 with an explicit offset: rendered in the clinic zone on the way out
 * (2026-10-05T09:30:00+02:00) and accepted with any offset on the way in. Strings without an offset
 * are rejected, because "09:30" means different moments in different zones.
 */
class ApiTime(
    private val zone: ZoneId,
) {
    fun format(instant: Instant): String = instant.atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun parseOrNull(raw: String): Instant? =
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }

    fun parseDateOrNull(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
}
