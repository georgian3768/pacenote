package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The guided wizard replaces the three manual calibration screens, and the gear
 * stage records the gear the driver selects (not 1..5 in sequence: a real drive
 * visited only 3rd and 4th and the sequential flow had already skipped past
 * them). These tests replay the whole run because a step that silently never
 * finishes is the exact failure that made the old wizard useless.
 */
class GuidedWizardTest {

    private fun sample(
        t: Long,
        rpm: Float?,
        thr: Int?,
        brake: Boolean? = false,
        speed: Double? = 0.0,
        gps: Double? = null,
    ) = GuidedSample(t, rpm, thr, brake, speed, gps)

    private fun GuidedWizard.feedIdle(from: Long, to: Long, step: Long = 100): Long {
        var t = from
        while (t <= to) {
            update(sample(t, 750f, 37))
            t += step
        }
        return t
    }

    /** Runs the throttle/release/brake preamble and returns the next timestamp. */
    private fun GuidedWizard.preamble(start: Long = 0): Long {
        var t = feedIdle(start, start + 2000)

        while (t <= start + 2900) {
            update(sample(t, 3200f, 240))
            t += 100
        }
        while (t <= start + 3700) {
            update(sample(t, 800f, 36))
            t += 100
        }
        update(sample(t, 800f, 36, brake = true))
        return t + 100
    }

    /** Holds one gear for the wizard's 2 s / 10 sample window. */
    private fun GuidedWizard.feedGear(start: Long, ratio: Double, speed: Double = 40.0): Long {
        var t = start
        repeat(11) {
            update(sample(t, (ratio * speed).toFloat(), 120, speed = speed))
            t += 200
        }
        return t
    }

    @Test
    @DisplayName("a full run records the gears that are selected, in any order")
    fun happyPath() {
        val w = GuidedWizard(gearCount = 5)
        var t = w.preamble()

        // The gear stage waits for a choice: nothing is recorded until tapped.
        assertTrue(w.snapshot().awaitingGearChoice)
        assertNull(w.snapshot().selectedGear)

        // Deliberately out of order: a real drive may only visit 3rd and 4th.
        val ratios = mapOf(3 to 70.0, 5 to 30.0, 1 to 150.0, 4 to 46.0, 2 to 100.0)
        ratios.forEach { (gear, ratio) ->
            w.selectGear(gear)
            assertEquals(gear, w.snapshot().selectedGear)
            t = w.feedGear(t, ratio)
            assertEquals(ratio, w.snapshot().results.gearRatios[gear]!!, 0.01)
            assertTrue(w.snapshot().awaitingGearChoice, "gear $gear should end awaiting the next choice")
        }

        w.continuePastGears()
        assertEquals(GuidedStep(StepKind.GPS), w.snapshot().current)

        // GPS reads 1.5x the CAN speed: the scale the wizard must store.
        repeat(8) {
            w.update(sample(t, 1200f, 120, speed = 40.0, gps = 60.0))
            t += 500
        }

        val s = w.snapshot()
        assertTrue(s.finished, "run should finish: ${s.views}")
        assertEquals(GuidedStep(StepKind.DONE), s.current)
        assertEquals(37, s.results.idleThrottle)
        assertEquals(750, s.results.idleRpm)
        assertEquals(240, s.results.fullThrottle)
        assertEquals(36, s.results.releaseThrottle)
        assertTrue(s.results.brakeSeen)
        assertEquals(setOf(1, 2, 3, 4, 5), s.results.gearRatios.keys)
        assertEquals(46.0, s.results.gearRatios[4]!!, 0.01)
        assertEquals(1.5, s.results.gpsScale!!, 0.001)
    }

    @Test
    @DisplayName("only the selected gears are recorded; the rest stay untouched")
    fun recordsOnlySelectedGears() {
        val w = GuidedWizard()
        var t = w.preamble()

        // Drive in 3rd, then 4th, and record exactly those two.
        w.selectGear(3)
        t = w.feedGear(t, 70.0)
        assertEquals(GuidedStep(StepKind.GEAR, 3), w.snapshot().current)
        assertTrue(w.snapshot().awaitingGearChoice)

        w.selectGear(4)
        t = w.feedGear(t, 46.0)

        val s = w.snapshot()
        assertEquals(setOf(3, 4), s.results.gearRatios.keys)
        assertEquals(70.0, s.results.gearRatios[3]!!, 0.01)
        assertEquals(46.0, s.results.gearRatios[4]!!, 0.01)
        assertEquals(StepStatus.PENDING, s.statusOf(GuidedStep(StepKind.GEAR, 1)))

        w.continuePastGears()
        assertEquals(GuidedStep(StepKind.GPS), w.snapshot().current)
    }

    @Test
    @DisplayName("valid samples are ignored until a gear is selected")
    fun noMeasurementWithoutSelection() {
        val w = GuidedWizard()
        var t = w.preamble()
        assertTrue(w.snapshot().awaitingGearChoice)

        // Perfect 3rd-gear data, but no gear has been chosen.
        t = w.feedGear(t, 70.0)
        assertTrue(w.snapshot().results.gearRatios.isEmpty())
        assertTrue(w.snapshot().awaitingGearChoice)
        assertEquals(GuidedStep(StepKind.GEAR, 1), w.snapshot().current)
    }

    @Test
    @DisplayName("gears saved in an earlier run are shown as saved and can be skipped")
    fun savedGearsAreSkipped() {
        // 3rd and 4th were measured on a previous drive.
        val w = GuidedWizard(
            gearCount = 5,
            savedRatios = listOf(0.0, 0.0, 70.0, 46.0, 0.0),
        )
        val gear3 = GuidedStep(StepKind.GEAR, 3)
        assertEquals(StepStatus.SAVED, w.snapshot().statusOf(gear3))
        assertEquals(70.0, w.snapshot().results.gearRatios[3]!!, 0.001)

        var t = w.preamble()
        assertTrue(w.snapshot().awaitingGearChoice)
        t = w.selectGear(1).let { w.feedGear(t, 150.0) }
        assertEquals(150.0, w.snapshot().results.gearRatios[1]!!, 0.001)

        w.continuePastGears()
        repeat(8) {
            w.update(sample(t, 1200f, 120, speed = 40.0, gps = 60.0))
            t += 500
        }
        val s = w.snapshot()
        assertTrue(s.finished)
        assertEquals(70.0, s.results.gearRatios[3]!!, 0.001)
        assertEquals(46.0, s.results.gearRatios[4]!!, 0.001)
    }

    @Test
    @DisplayName("skipping without a chosen gear leaves the gear stage without inventing values")
    fun skipLeavesGearStage() {
        val w = GuidedWizard()
        var t = w.preamble()
        assertTrue(w.snapshot().awaitingGearChoice)

        val skipped = w.skip()
        assertEquals(GuidedStep(StepKind.GPS), skipped.current)
        assertEquals(StepStatus.SKIPPED, skipped.statusOf(GuidedStep(StepKind.GEAR, 1)))
        assertTrue(skipped.results.gearRatios.isEmpty())

        // Going back and recording one for real still works.
        w.jumpTo(GuidedStep(StepKind.GEAR, 1))
        assertEquals(GuidedStep(StepKind.GEAR, 1), w.snapshot().current)
        assertEquals(1, w.snapshot().selectedGear)
        t = w.feedGear(t, 150.0)
        assertEquals(150.0, w.snapshot().results.gearRatios[1]!!, 0.001)
    }

    @Test
    @DisplayName("canceling a redo keeps the value measured before it")
    fun cancelRedoKeepsValue() {
        val w = GuidedWizard()
        var t = w.preamble()
        w.selectGear(3)
        t = w.feedGear(t, 70.0)
        assertEquals(70.0, w.snapshot().results.gearRatios[3]!!, 0.01)

        // Tap 3 again, then cancel instead of finishing.
        w.selectGear(3)
        val state = w.skip()
        assertEquals(70.0, state.results.gearRatios[3]!!, 0.01)
        assertEquals(StepStatus.CAPTURED, state.statusOf(GuidedStep(StepKind.GEAR, 3)))
        assertTrue(state.awaitingGearChoice)
    }

    @Test
    @DisplayName("a gear that never reaches speed fails visibly and waits for another choice")
    fun gearTimeoutFailsTheStep() {
        val w = GuidedWizard()
        var t = w.preamble()
        w.selectGear(1)
        // Engine idling at a standstill for 3 minutes: nothing to measure.
        var now = t
        repeat(190) {
            w.update(sample(now, 750f, 37, speed = 0.0))
            now += 1000
        }
        val s = w.snapshot()
        assertEquals(StepStatus.FAILED, s.statusOf(GuidedStep(StepKind.GEAR, 1)))
        assertNull(s.results.gearRatios[1])
        assertFalse(s.finished)
        assertTrue(s.awaitingGearChoice, "should return to the picker, not strand the run")
    }

    @Test
    @DisplayName("a step that times out fails visibly instead of waiting forever")
    fun timeoutFailsTheStep() {
        val w = GuidedWizard()
        w.feedIdle(0, 2000)
        assertEquals(GuidedStep(StepKind.ACCEL), w.snapshot().current)

        // 50 s later the pedal still has not moved (timeout is 45 s).
        w.update(sample(52_000, 750f, 37))
        val s = w.snapshot()
        assertEquals(StepStatus.FAILED, s.statusOf(GuidedStep(StepKind.ACCEL)))
        assertEquals(GuidedStep(StepKind.RELEASE), s.current)
        assertNull(s.results.fullThrottle, "a timed-out step must not save a value")
    }

    @Test
    @DisplayName("with no telemetry the wizard stays on step one and says why")
    fun noSignal() {
        val w = GuidedWizard()
        for (t in 0L..5000L step 100) w.update(GuidedSample(t, null, null, null, null))
        val s = w.snapshot()
        assertEquals(GuidedStep(StepKind.IDLE), s.current)
        assertTrue(s.results.idleThrottle == null)
        assertTrue(s.hint.contains("No rpm or throttle"), s.hint)
    }

    @Test
    @DisplayName("a revving engine does not count as a steady idle")
    fun revvingIsNotIdle() {
        val w = GuidedWizard()
        val hints = mutableSetOf<String>()
        for (t in 0L..4000L step 100) {
            val rpm = if ((t / 100) % 2 == 0L) 750f else 2400f
            w.update(sample(t, rpm, 37))
            hints += w.snapshot().hint
        }
        assertEquals(GuidedStep(StepKind.IDLE), w.snapshot().current)
        assertNull(w.snapshot().results.idleThrottle)
        assertTrue(hints.any { it.contains("rpm is moving") }, "no hint reported the swing: $hints")
    }

    @Test
    @DisplayName("GPS scale outside the plausible range is refused, not saved")
    fun implausibleGpsScaleIsRefused() {
        val w = GuidedWizard()
        var t = w.preamble()
        w.skip() // leave the gear stage, no ratios required for the GPS test

        // GPS reports 3x CAN: not a speed scale, a broken signal.
        repeat(8) {
            w.update(sample(t, 1200f, 120, speed = 40.0, gps = 120.0))
            t += 500
        }
        val s = w.snapshot()
        assertNull(s.results.gpsScale)
        assertEquals(StepStatus.FAILED, s.statusOf(GuidedStep(StepKind.GPS)))
        assertTrue(s.finished, "a failed GPS step must not strand the wizard")
    }

    @Test
    @DisplayName("wandering ratios are rejected instead of averaged")
    fun wanderingRatiosAreRejected() {
        val w = GuidedWizard()
        var t = w.preamble()
        w.selectGear(1)
        // Same gear, but speed and rpm disagree wildly frame to frame.
        repeat(11) {
            val ratio = if (it % 2 == 0) 70.0 else 110.0
            w.update(sample(t, (ratio * 40).toFloat(), 120, speed = 40.0))
            t += 200
        }
        assertNull(w.snapshot().results.gearRatios[1])
        assertTrue(
            w.snapshot().hint.contains("wandered"),
            "expected a wandering warning, hint was: ${w.snapshot().hint}",
        )
    }

    @Test
    @DisplayName("a huge uptime clock does not look like a step that waited forever")
    fun absoluteClockIsFine() {
        // Real timestamps are elapsedRealtime: four days of uptime, not 0.
        val w = GuidedWizard()
        var t = 370_000_000L
        repeat(30) {
            w.update(sample(t, 750f, 37))
            t += 100
        }
        val s = w.snapshot()
        assertEquals(GuidedStep(StepKind.ACCEL), s.current)
        assertEquals(37, s.results.idleThrottle)
        assertTrue(
            s.views.none { it.status == StepStatus.FAILED },
            "idle timed out on the uptime clock: ${s.views}",
        )
    }
}
