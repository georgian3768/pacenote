package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The throttle channel is a raw byte, not a pedal percentage. These cases pin
 * the two honest readings: raw/255 while uncalibrated, and the idle-offset
 * fraction once the wizard has measured closed and full throttle.
 */
class ThrottleTest {

    @Test
    @DisplayName("no raw value means no demand")
    fun nullRaw() {
        assertNull(Throttle.normalized(null, 42, 250))
    }

    @Test
    @DisplayName("uncalibrated falls back to raw / full, including the 42-byte idle")
    fun uncalibrated() {
        // idleRaw = -1 is what Settings returns before the wizard has run.
        assertEquals(42.0 / 255.0, Throttle.normalized(42, -1, 255)!!, 1e-9)
        assertEquals(0.0, Throttle.normalized(0, -1, 255)!!, 1e-9)
        assertEquals(1.0, Throttle.normalized(255, -1, 255)!!, 1e-9)
    }

    @Test
    @DisplayName("calibrated subtracts the idle offset: closed is 0 %, full is 100 %")
    fun calibrated() {
        assertEquals(0.0, Throttle.normalized(42, 42, 250)!!, 1e-9)
        assertEquals(1.0, Throttle.normalized(250, 42, 250)!!, 1e-9)
        assertEquals(0.5, Throttle.normalized(146, 42, 250)!!, 1e-9)
    }

    @Test
    @DisplayName("out-of-range raw is clamped, not extrapolated")
    fun clamps() {
        assertEquals(0.0, Throttle.normalized(10, 42, 250)!!, 1e-9)
        assertEquals(1.0, Throttle.normalized(255, 42, 250)!!, 1e-9)
    }

    @Test
    @DisplayName("a full-throttle value at or below idle is unusable, so fall back")
    fun degenerateCalibration() {
        // A failed capture must not divide by zero or invert the scale.
        assertEquals(100.0 / 100.0, Throttle.normalized(100, 100, 100)!!, 1e-9)
        assertNull(Throttle.normalized(100, 42, 0))
    }
}
