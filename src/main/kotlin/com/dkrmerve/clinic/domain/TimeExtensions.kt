package com.dkrmerve.clinic.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Small extensions that keep clinic-local conversions readable and in one place. */
fun Instant.inZone(zone: ZoneId): ZonedDateTime = atZone(zone)

fun Instant.clinicDate(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()

fun LocalDateTime.toInstantIn(zone: ZoneId): Instant = atZone(zone).toInstant()

/** [from, to) bounds of a calendar day in the given zone. DST-safe because it goes through ZonedDateTime. */
fun LocalDate.dayBounds(zone: ZoneId): Pair<Instant, Instant> = atStartOfDay(zone).toInstant() to plusDays(1).atStartOfDay(zone).toInstant()
