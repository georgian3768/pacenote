package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Table-driven tests for the shift assistant. Every case is a small synthetic
 * trace so a failure points at one rule; the JSONL replay (gradle replay) covers
 * the messy real-capture behaviour.
 *
 * The ladder is the one calibrated on the car on 2026-11-09. With it the NORMAL
 * upshift points are 3712 / 2975 / 2678 / 2464 rpm in gears 1-4, and the
 * downshift points 1100 / 1142 / 1269 / 1379 rpm in gears 2-5 (see
 * [ShiftModesTest] for the per-mode table).
 */
class ShiftAssistantTest {

    private val ratios = listOf(147.2, 79.3, 53.3, 39.8, 32.3)

    private fun assistant(mode: DriveMode = DriveMode.NORMAL) =
        ShiftAssistant(Config(), GearEstimator(ratios)).also { it.mode = mode }

    private fun speedFor(gear: Int, rpm: Int): Double = rpm / ratios[gear - 1]

    private fun sample(
        t: Long,
        rpm: Int?,
        speed: Double?,
        brake: Boolean? = false,
        throttle: Int? = 255,
        engine: Boolean? = true,
    ) = Sample(t, rpm, speed, brake, throttle, canActive = true, engineRunning = engine)

    /** A steady climb in [gear], rpm rising [step] every 100 ms. */
    private fun ramp(
        gear: Int,
        fromRpm: Int,
        step: Int,
        frames: Int,
        brake: Boolean = false,
        engine: Boolean = true,
    ): List<Output> {
        val a = assistant()
        return (0..frames).map { i ->
            val rpm = fromRpm + step * i
            a.update(sample(i * 100L, rpm, speedFor(gear, rpm), brake = brake, engine = engine))
        }
    }

    @Test
    @DisplayName("demand uses the calibrated idle offset, so closed throttle is 0")
    fun demandUsesIdleOffset() {
        val a = ShiftAssistant(
            Config(throttleIdleRaw = 42, throttleFullRaw = 250),
            GearEstimator(ratios),
        )
        val closed = a.update(sample(0, 750, 30.0, throttle = 42))
        assertEquals(0.0, closed.demand!!, 1e-9)
        val half = a.update(sample(100, 750, 30.0, throttle = 146))
        assertEquals(0.5, half.demand!!, 1e-9)
    }

    @Test
    @DisplayName("gear estimate does not flip when rpm and speed are out of phase")
    fun gearIsSmoothed() {
        // Measured on a real drive (2026-11-05, trip #4): consecutive frames gave
        // rpm/speed ratios of two neighbouring gears alternately because 0x124
        // and 0x314 arrive at different times, so the raw estimate went
        // 3 -> 4 -> 5 -> 4 -> 3 within 1.4 s. The assistant must report the
        // settled gear, not each wild frame.
        val a = assistant()
        var t = 0L
        val gears = mutableListOf<Int?>()
        // Two frames per gear-3 ratio, one frame of gear-4 ratio, repeated.
        repeat(8) {
            repeat(2) {
                t += 100
                gears += a.update(sample(t, 1599, 30.0)).gear    // ratio 53.3 -> gear 3
            }
            t += 100
            gears += a.update(sample(t, 1194, 30.0)).gear        // ratio 39.8 -> gear 4
        }
        val tail = gears.drop(6)
        assertEquals(setOf(3), tail.filterNotNull().toSet(), "gear flipped: $gears")
        assertTrue(tail.none { it == 4 }, "single bad frame must not report gear 4: $gears")
    }

    @Test
    @DisplayName("gear estimator maps rpm/speed to the nearest calibrated gear")
    fun gearClusters() {
        val est = GearEstimator(ratios)
        val table = listOf(
            Triple(1500, 10.0, null),   // below minSpeedKph: clutch/neutral
            Triple(2100, 14.0, null),
            Triple(3000, 20.4, 1),
            Triple(4000, 50.4, 2),
            Triple(4200, 78.8, 3),
            Triple(2300, 57.8, 4),
            Triple(3000, 92.9, 5),
        )
        for ((rpm, kph, expected) in table) {
            assertEquals(expected, est.gearFor(rpm, kph), "rpm=$rpm kph=$kph")
        }
    }

    @Test
    @DisplayName("gear estimator rejects ratios outside tolerance and bad config")
    fun gearEstimatorEdges() {
        val est = GearEstimator(ratios)
        assertNull(est.gearFor(2600, 40.0))          // 65 rpm/km/h: 22% off 53.3, 18% off 79.3
        assertNull(est.gearFor(0, 50.0))
        assertNull(est.gearFor(3000, 0.0))
        assertThrows(IllegalArgumentException::class.java) {
            GearEstimator(listOf(70.0, 70.0))
        }
    }

    @Test
    @DisplayName("upshift points come from the ladder and stay reachable in every gear")
    fun upshiftThresholdsFollowTheLadder() {
        // Gear 3 at 2500 rpm / 46.9 km/h. NORMAL lands at 2000 rpm, so gear 3
        // is left at 2000 * 53.3 / 39.8 = 2678 rpm - not the old 4480 rpm that
        // needed ~84 km/h in third.
        val out = assistant().update(sample(0, 2500, speedFor(3, 2500)))
        assertEquals(3, out.gear)
        assertEquals(2678, out.upRpm)
        assertEquals(1142, out.downRpm)

        val eco = assistant(DriveMode.ECO).update(sample(0, 2500, speedFor(3, 2500)))
        assertEquals(2142, eco.upRpm)
        val sport = assistant(DriveMode.SPORT).update(sample(0, 2500, speedFor(3, 2500)))
        assertEquals(3749, sport.upRpm)
    }

    @Test
    @DisplayName("upshift cues at the ladder point under acceleration")
    fun upshift() {
        val outs = ramp(3, fromRpm = 2500, step = 100, frames = 8)
        val cue = outs.firstOrNull { it.cue == Cue.UP }
        assertTrue(cue != null, "no upshift cue in ${outs.map { it.cue }}")
        assertEquals(3, cue!!.gear)
        assertTrue(cue.reason.contains("up"))
        // Fires just above 2678 + 150; not at the old 4880.
        assertTrue(cue.reason.contains("up 2678"), cue.reason)
    }

    @Test
    @DisplayName("no upshift below the ladder point")
    fun noUpshiftBelowThreshold() {
        val outs = ramp(3, fromRpm = 2400, step = 5, frames = 12) // stays under 2678+150
        assertNull(outs.mapNotNull { it.cue }.firstOrNull(), "cues: ${outs.map { it.cue }}")
    }

    @Test
    @DisplayName("clutch / neutral (speed below minSpeed) suppresses cues")
    fun noCueWhenClutchEngaged() {
        val a = assistant()
        val out = a.update(sample(0, 2500, 8.0))
        assertNull(out.gear)
        assertNull(out.cue)
    }

    @Test
    @DisplayName("braking suppresses upshift")
    fun noUpshiftWhileBraking() {
        val outs = ramp(3, fromRpm = 2500, step = 100, frames = 8, brake = true)
        assertNull(outs.firstOrNull { it.cue == Cue.UP })
    }

    @Test
    @DisplayName("engine off suppresses cues even at high rpm")
    fun noCueEngineOff() {
        val outs = ramp(3, fromRpm = 2500, step = 100, frames = 8, engine = false)
        assertNull(outs.firstOrNull { it.cue == Cue.UP })
    }

    @Test
    @DisplayName("downshift cues below the ladder point, without a throttle gate")
    fun downshift() {
        val a = assistant()
        val cue = (0..3).map { i ->
            val rpm = 1270 - i * 10
            a.update(sample(i * 100L, rpm, speedFor(4, rpm), throttle = null))
        }.firstOrNull { it.cue == Cue.DOWN }
        assertTrue(cue != null, "no downshift cue")
        assertEquals(4, cue!!.gear)
        assertTrue(cue.reason.contains("down 1269"), cue.reason)
    }

    @Test
    @DisplayName("downshift is allowed while braking - that is when it is needed")
    fun downshiftWhileBraking() {
        val a = assistant()
        val cue = (0..3).map { i ->
            val rpm = 1270 - i * 10
            a.update(sample(i * 100L, rpm, speedFor(4, rpm), brake = true, throttle = null))
        }.firstOrNull { it.cue == Cue.DOWN }
        assertTrue(cue != null, "no downshift cue while braking")
    }

    @Test
    @DisplayName("over-rev fires after the hold time, independently of the shift cue")
    fun overRev() {
        val a = assistant()
        // 6400 engine limit -> cue at 0.95 * 6400 = 6080 rpm.
        assertNull(a.update(sample(0, 6100, speedFor(4, 6100))).cue)
        assertNull(a.update(sample(100, 6100, speedFor(4, 6100))).cue)
        assertNull(a.update(sample(200, 6100, speedFor(4, 6100))).cue)
        val out = a.update(sample(300, 6100, speedFor(4, 6100)))
        assertEquals(Cue.OVER_REV, out.cue, out.reason)
        assertTrue(out.overRev)
    }

    @Test
    @DisplayName("an unconfirmed redline never raises over-rev, even above it")
    fun overRevNeedsConfirmedRedline() {
        val unconfirmed = ShiftAssistant(
            Config(redlineConfirmed = false),
            GearEstimator(ratios),
        )
        repeat(6) { i ->
            val rpm = 6100
            val out = unconfirmed.update(sample(i * 100L, rpm, speedFor(4, rpm)))
            assertNull(out.cue, "cue at ${i * 100} ms with an unconfirmed redline")
        }
        val confirmed = ShiftAssistant(Config(), GearEstimator(ratios))
        repeat(3) { confirmed.update(sample(it * 100L, 6100, speedFor(4, 6100))) }
        assertEquals(
            Cue.OVER_REV,
            confirmed.update(sample(300, 6100, speedFor(4, 6100))).cue
        )
    }

    @Test
    @DisplayName("over-rev does not fire for a brief spike")
    fun overRevSpike() {
        val a = assistant()
        a.update(sample(0, 6100, speedFor(4, 6100)))
        a.update(sample(100, 6100, speedFor(4, 6100)))
        assertNull(a.update(sample(200, 4000, speedFor(4, 4000))).cue)   // dropped before 300 ms
    }

    @Test
    @DisplayName("a gear that has cued stays quiet until the rpm re-arms it")
    fun cueRateLimit() {
        val a = assistant()
        // Pull through gear 3: cue as the ladder point (2678) is crossed.
        var cueAt = -1L
        var t = 0L
        for (i in 0..5) {
            val rpm = 2500 + 100 * i
            val out = a.update(sample(t, rpm, speedFor(3, rpm)))
            if (out.cue == Cue.UP) cueAt = t
            t += 100
        }
        assertTrue(cueAt >= 0, "expected an upshift cue")

        // Stay above the threshold, still creeping forward: neither the gap nor
        // a re-arm allows a repeat, so the driver is not nagged.
        while (t <= cueAt + 4_000) {
            val speed = speedFor(3, 3000) + (t - cueAt) / 100.0 * 0.1
            assertNull(a.update(sample(t, 3000, speed)).cue, "repeat cue at $t")
            t += 100
        }

        // Fall straight back below threshold (a shift-like drop), then pull again.
        var out: Output = a.update(sample(t, 2400, speedFor(3, 2400)))
        assertNull(out.cue, "no cue at the drop")
        var rpm = 2400
        while (rpm < 2900 && out.cue == null) {
            rpm += 100
            t += 100
            out = a.update(sample(t, rpm, speedFor(3, rpm)))
        }
        assertEquals(Cue.UP, out.cue, "re-armed pull should cue again: ${out.reason}")
    }

    @Test
    @DisplayName("null signals never throw and never cue")
    fun nullSignals() {
        val a = assistant()
        val out = a.update(sample(0, null, null, brake = null, throttle = null, engine = null))
        assertNull(out.gear)
        assertNull(out.cue)
        assertNull(out.demand)
    }

    @Test
    @DisplayName("mode changes move the upshift point")
    fun modeChangesThreshold() {
        // Gear 3 at 3000 rpm: above ECO's 2142, below SPORT's 3749.
        fun anyUp(mode: DriveMode): Boolean {
            val a = assistant(mode)
            var up = false
            a.update(sample(0, 2800, speedFor(3, 2800)))
            for (i in 1..6) {
                val rpm = 2800 + 100 * i
                if (a.update(sample(i * 100L, rpm, speedFor(3, rpm))).cue == Cue.UP) up = true
            }
            return up
        }
        assertTrue(anyUp(DriveMode.ECO), "ECO should cue up at ~2142 rpm")
        assertTrue(!anyUp(DriveMode.SPORT), "SPORT should stay silent below 3749 rpm")
    }
}
