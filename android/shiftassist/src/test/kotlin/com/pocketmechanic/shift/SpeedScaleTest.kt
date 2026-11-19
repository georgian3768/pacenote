package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The GPS calibration multiplier. This exists as a test because the original bug
 * was a value that was saved and never applied: the wizard looked functional on
 * the phone while having no effect at all.
 */
class SpeedScaleTest {

    @Test
    fun `uncalibrated scale leaves the reading untouched`() {
        assertEquals(46.5, SpeedScale.apply(46.5f, 1.0)!!, 1e-6)
    }

    @Test
    fun `scale multiplies the raw reading`() {
        assertEquals(74.4, SpeedScale.apply(46.5f, 1.6)!!, 1e-6)
    }

    @Test
    fun `null stays null instead of becoming zero`() {
        assertNull(SpeedScale.apply(null, 1.6))
    }

    @Test
    fun `implausible scale is ignored rather than trusted`() {
        assertEquals(46.5, SpeedScale.apply(46.5f, 0.0)!!, 1e-6)
        assertEquals(46.5, SpeedScale.apply(46.5f, 7.5)!!, 1e-6)
        assertEquals(46.5, SpeedScale.apply(46.5f, -1.0)!!, 1e-6)
    }

    @Test
    fun `float helper matches the double path`() {
        assertEquals(74.4f, SpeedScale.applyFloat(46.5f, 1.6)!!, 1e-4f)
        assertNull(SpeedScale.applyFloat(null, 1.6))
    }
}
