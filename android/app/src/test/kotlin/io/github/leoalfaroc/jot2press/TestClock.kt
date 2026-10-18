package io.github.leoalfaroc.jot2press

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand. It starts on Sunday 11 October 2026 at 14:32:05, at UTC-5. */
class TestClock(
    var now: Instant = Instant.parse("2026-10-11T19:32:05Z"),
    var zoneId: ZoneId = ZoneOffset.ofHours(-5),
) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
    override fun instant(): Instant {
        whenRead()
        return now
    }

    /** Runs each time the time is read: a test uses it to do something in the middle of whatever reads the clock. */
    var whenRead: () -> Unit = {}

    fun advance(duration: Duration) {
        now += duration
    }
}
