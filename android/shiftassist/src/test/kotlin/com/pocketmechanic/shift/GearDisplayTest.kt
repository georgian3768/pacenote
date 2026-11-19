package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The gear bubble's hold behaviour. Every case matters on the road: a stale
 * gear during a shift is a wrong cue, and a gear that blinks off at every
 * clutch-in is noise. All times are explicit, so nothing here depends on a
 * clock.
 */
class GearDisplayTest {

    private val hold = GearDisplay.DEFAULT_HOLD_MS

    @Test
    fun `before any estimate there is nothing to show`() {
        val d = GearDisplay()
        assertNull(d.update(null, 0))
        // An arbitrary late timestamp must not invent a gear either.
        assertNull(d.update(null, 10_000))
    }

    @Test
    fun `a fresh estimate is returned immediately and remembered`() {
        val d = GearDisplay()
        assertEquals(3, d.update(3, 1_000))
        // Inside the hold window the value survives the estimator going silent.
        assertEquals(3, d.update(null, 1_000 + hold))
    }

    @Test
    fun `the last gear is held while the estimator is silent`() {
        val d = GearDisplay()
        assertEquals(4, d.update(4, 0))
        assertEquals(4, d.update(null, 500))
        assertEquals(4, d.update(null, hold))
    }

    @Test
    fun `an expired hold returns null and forgets the gear`() {
        val d = GearDisplay()
        assertEquals(5, d.update(5, 0))
        assertNull(d.update(null, hold + 1))
        // The value is gone, not merely hidden: nothing ancient comes back.
        assertNull(d.update(null, hold + 2))
        assertNull(d.update(null, 1_000_000))
    }

    @Test
    fun `a new estimate during the hold replaces the held value`() {
        val d = GearDisplay()
        assertEquals(2, d.update(2, 0))
        assertEquals(4, d.update(4, 500)) // fresh wins over the held 2
        // The window restarted at 500, so 2 is never shown again and the hold
        // now lives until 500 + hold.
        assertEquals(4, d.update(null, 500 + hold))
        assertNull(d.update(null, 500 + hold + 1))
    }

    @Test
    fun `reset drops the hold immediately`() {
        val d = GearDisplay()
        assertEquals(3, d.update(3, 0))
        d.reset()
        assertNull(d.update(null, 1)) // still well inside the hold window
    }

    @Test
    fun `the hold boundary is inclusive`() {
        val d = GearDisplay(holdMs = 1_000)
        assertEquals(3, d.update(3, 0))
        // The code tests `nowMs - heldAtMs > holdMs`, so exactly holdMs is still
        // held and one millisecond past it is not.
        assertEquals(3, d.update(null, 1_000))
        assertNull(d.update(null, 1_001))
    }

    @Test
    fun `a custom holdMs is respected`() {
        val d = GearDisplay(holdMs = 100)
        assertEquals(2, d.update(2, 0))
        assertEquals(2, d.update(null, 100))
        assertNull(d.update(null, 200)) // long past 100, far inside the default
    }
}
