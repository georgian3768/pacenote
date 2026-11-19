package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * "Saved as each step turns ✓" must be provable without a car. These tests pin
 * the write decisions, including the case that shipped broken before: a value
 * that is captured but never applied.
 */
class CalibrationWritesTest {

    private fun results(
        idle: Int? = null,
        full: Int? = null,
        gears: Map<Int, Double> = emptyMap(),
        gps: Double? = null,
    ) = GuidedResults(
        idleThrottle = idle,
        fullThrottle = full,
        gearRatios = gears,
        gpsScale = gps,
    )

    @Test
    @DisplayName("each captured number becomes exactly one settings write")
    fun writesEveryValue() {
        val before = results()
        val after = results(idle = 37, full = 240, gears = mapOf(3 to 46.0), gps = 1.6)
        val apply = CalibrationWrites.diff(before, after, currentIdle = -1)

        assertEquals(
            listOf(
                SettingWrite.ThrottleIdle(37),
                SettingWrite.ThrottleFull(240),
                SettingWrite.GearRatio(3, 46.0),
                SettingWrite.GpsScale(1.6),
            ),
            apply.writes,
        )
        assertTrue(apply.refusals.isEmpty())
    }

    @Test
    @DisplayName("running the diff again writes nothing twice")
    fun isIdempotent() {
        val after = results(idle = 37, full = 240, gears = mapOf(3 to 46.0), gps = 1.6)
        val first = CalibrationWrites.diff(results(), after, currentIdle = -1)
        assertTrue(first.writes.isNotEmpty())
        assertTrue(CalibrationWrites.diff(after, after, currentIdle = 37).isEmpty)
    }

    @Test
    @DisplayName("a full-throttle value too close to idle is refused, not saved")
    fun narrowPedalRangeIsRefused() {
        val apply = CalibrationWrites.diff(
            results(idle = 37),
            results(idle = 37, full = 80),
            currentIdle = 37,
        )
        assertTrue(apply.writes.none { it is SettingWrite.ThrottleFull })
        assertEquals(1, apply.refusals.size)
        assertTrue(apply.refusals.single().contains("need 60"), apply.refusals.single())
    }

    @Test
    @DisplayName("a redo of one gear rewrites only that gear")
    fun redoOneGear() {
        val before = results(gears = mapOf(1 to 150.0, 3 to 70.0, 5 to 30.0))
        val after = results(gears = mapOf(1 to 150.0, 3 to 69.4, 5 to 30.0))
        val apply = CalibrationWrites.diff(before, after, currentIdle = 37)
        assertEquals(listOf(SettingWrite.GearRatio(3, 69.4)), apply.writes)
    }

    @Test
    @DisplayName("a skipped GPS step never writes a scale")
    fun skippedGpsWritesNothing() {
        val apply = CalibrationWrites.diff(
            results(gps = null),
            results(gps = null),
            currentIdle = 37,
        )
        assertTrue(apply.writes.isEmpty())
    }
}
