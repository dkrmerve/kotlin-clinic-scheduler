package com.dkrmerve.clinic

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests can move: every time-dependent rule reads it, so "24 hours later" is one method call. */
class MutableClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    fun set(instant: Instant) {
        now = instant
    }

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
