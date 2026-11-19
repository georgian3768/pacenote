package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * SHADOW orchestration and the frame-level cue log.
 *
 * SHADOW runs every profile on the same frames and hands nothing to the UI; the
 * log then has an explanation for every frame, which is what makes a drive
 * reviewable instead of just countable.
 */
class ShadowModeTest {

    private val ratios = listOf(147.2, 79.3, 53.3, 39.8, 32.3)

    private fun bank(mode: AssistantMode = AssistantMode.SHADOW) =
        AssistantBank(Config(), GearEstimator(ratios), mode)

    private fun speedFor(gear: Int, rpm: Int): Double = rpm / ratios[gear - 1]

    private fun sample(t: Long, rpm: Int?, gear: Int, brake: Boolean? = false, throttle: Int? = null) =
        Sample(
            tMs = t,
            rpm = rpm,
            speedKph = rpm?.let { speedFor(gear, it) },
            brake = brake,
            throttle = throttle,
        )

    @Test
    @DisplayName("SHADOW runs all three profiles and shows none of them")
    fun shadowRunsEveryProfile() {
        val bank = bank(AssistantMode.SHADOW)
        // 3rd at 2300 rpm pulling: 2300 is above ECO's 2142 but below NORMAL's
        // 2678 and SPORT's 3749, so the three profiles must disagree.
        val decisions = (0..14).map { i ->
            val rpm = 2300 + 20 * i
            bank.update(sample(i * 100L, rpm, 3))
        }
        assertTrue(
            decisions.all { it.visible == null },
            "SHADOW must not hand an output to the UI",
        )
        assertTrue(
            decisions.all {
                it.outputs.keys == setOf(DriveMode.ECO, DriveMode.NORMAL, DriveMode.SPORT)
            },
            "SHADOW must run all three profiles",
        )
        assertTrue(
            decisions.any { it.outputs.getValue(DriveMode.ECO).cue == Cue.UP },
            "ECO should cue somewhere above 2142 rpm",
        )
        assertTrue(
            decisions.none { it.outputs.getValue(DriveMode.SPORT).cue != null },
            "SPORT must stay silent below 3749 rpm",
        )
    }

    @Test
    @DisplayName("a real mode runs only its own profile and hands it to the UI")
    fun singleModeRunsOneProfile() {
        val bank = bank(AssistantMode.ECO)
        val out = bank.update(sample(0, 2300, 3))
        assertEquals(setOf(DriveMode.ECO), out.outputs.keys)
        assertNotNull(out.visible)
        assertEquals(DriveMode.ECO, out.visible!!.mode)
        assertEquals(AssistantMode.ECO, out.mode)
    }

    @Test
    @DisplayName("the three profiles share one gear estimate on the same frame")
    fun profilesAgreeOnGear() {
        val bank = bank(AssistantMode.SHADOW)
        val gears = (0..5).map { i ->
            val out = bank.update(sample(i * 100L, 2500, 3))
            out.outputs.values.map { it.gear }.toSet()
        }
        assertTrue(gears.all { it == setOf(3) }, "profiles disagreed about the gear: $gears")
    }

    @Test
    @DisplayName("a cue row has every agreed column, monotonic clock first")
    fun cueRowShape() {
        val out = ShiftAssistant(Config(), GearEstimator(ratios)).update(sample(0, 2500, 3))
        val row = cueRow(
            tMonoMs = 12_345,
            tWallMs = 1_760_000_000_000,
            frameTMs = 12_340,
            output = out,
            mode = DriveMode.NORMAL,
            lastCueAgeMs = null,
            visible = true,
            audioEnabled = true,
        )
        val cols = row.csv().split(",")
        assertEquals(CueRow.HEADER.split(",").size, cols.size, "header/row mismatch: ${row.csv()}")
        assertEquals(18, cols.size)
        assertEquals("12345", cols[0], "monotonic clock must be the first column")
        assertEquals("1760000000000", cols[1], "wall clock must be the second column")
        assertEquals("normal", cols[3])
        assertEquals("3", cols[5])
        assertEquals("2678", cols[11])
        assertEquals("1142", cols[12])
        assertEquals("0", cols[13], "no cue on a steady frame")
        assertEquals("none", cols[14], "2500 rpm in 3rd is below the 2678 up point")
        assertEquals("1", cols[16], "the selected profile is visible")
        assertEquals("0", cols[17], "no cue -> no audio")
    }

    @Test
    @DisplayName("thr1/thr2 cannot change a cue decision")
    fun throttleIsNotACueInput() {
        // Same trace, every throttle value the channel can carry. The cue
        // sequence must be identical; only the displayed demand may move.
        fun cues(throttle: Int): List<Cue?> {
            val a = ShiftAssistant(Config(throttleIdleRaw = 31, throttleFullRaw = 219), GearEstimator(ratios))
            return (0..10).map { i ->
                val rpm = 2500 + 100 * i
                a.update(Sample(i * 100L, rpm, speedFor(3, rpm), false, throttle)).cue
            }
        }
        val reference = cues(0)
        assertEquals(Cue.UP, reference.filterNotNull().first(), "the trace should cue up")
        for (throttle in listOf(31, 64, 128, 219, 255)) {
            assertEquals(reference, cues(throttle), "throttle=$throttle changed the cues")
        }
        // And the channel is still read, just not for the decision.
        val demand = ShiftAssistant(Config(throttleIdleRaw = 31, throttleFullRaw = 219), GearEstimator(ratios))
            .update(Sample(0, 800, 16.0, false, 125))
        assertEquals(0.5, demand.demand!!, 1e-9)
    }

    @Test
    @DisplayName("suppressed_by names the gate that stopped each cue")
    fun suppressionReasons() {
        // gear_null: below minSpeed the estimator refuses a gear.
        val clutch = ShiftAssistant(Config(), GearEstimator(ratios))
            .update(Sample(0, 2500, 8.0, false, null))
        assertEquals(SuppressedBy.GEAR_NULL, clutch.suppressedBy)

        // brake: an upshift candidate while braking.
        val braking = ShiftAssistant(Config(), GearEstimator(ratios))
        val brakeOut = (0..5).map { i ->
            val rpm = 2500 + 100 * i
            braking.update(Sample(i * 100L, rpm, speedFor(3, rpm), true, null))
        }.last()
        assertEquals(SuppressedBy.BRAKE, brakeOut.suppressedBy)

        // not_accelerating: at the up point, but holding steady.
        val steady = ShiftAssistant(Config(), GearEstimator(ratios))
        var steadyOut = steady.update(Sample(0, 2700, speedFor(3, 2700), false, null))
        repeat(6) { i -> steadyOut = steady.update(Sample((i + 1) * 100L, 2700, speedFor(3, 2700), false, null)) }
        assertEquals(SuppressedBy.NOT_ACCELERATING, steadyOut.suppressedBy)

        // accelerating: a downshift candidate while the car is pulling - 4th at
        // 1250 rpm under load, which is exactly the case the down cue must not
        // nag about.
        val pulling = ShiftAssistant(Config(), GearEstimator(ratios))
        var pullOut = pulling.update(Sample(0, 1300, 32.66, false, null))
        for (i in 1..10) {
            val rpm = if (i <= 5) 1300 else 1250
            pullOut = pulling.update(Sample(i * 100L, rpm, 32.66 + 0.04 * i, false, null))
        }
        assertEquals(SuppressedBy.ACCELERATING, pullOut.suppressedBy)

        // over_rev: held past the redline, but the over-rev cue's own gap is shut.
        val revving = ShiftAssistant(Config(), GearEstimator(ratios))
        val revOuts = (0..4).map { i ->
            revving.update(Sample(i * 100L, 6100, speedFor(4, 6100), false, null))
        }
        val revCueAt = revOuts.indexOfFirst { it.cue == Cue.OVER_REV }
        assertTrue(revCueAt >= 0, "no over-rev cue in ${revOuts.map { it.cue }}")
        val heldRev = revving.update(Sample(500, 6100, speedFor(4, 6100), false, null))
        assertNull(heldRev.cue)
        assertEquals(SuppressedBy.OVER_REV, heldRev.suppressedBy)
    }

    @Test
    @DisplayName("a cue still on screen reads on_time, the later window reads gap")
    fun onTimeThenGap() {
        val a = ShiftAssistant(Config(minCueGapMs = 1_300), GearEstimator(ratios))
        // Pull 3rd until the up cue fires.
        var t = 0L
        var rpm = 2500
        var out = a.update(Sample(t, rpm, speedFor(3, rpm), false, null))
        while (out.cue != Cue.UP && t < 2_000) {
            t += 100
            rpm += 100
            out = a.update(Sample(t, rpm, speedFor(3, rpm), false, null))
        }
        assertEquals(Cue.UP, out.cue, "no up cue to build on")

        // The next frame is 100 ms later: the cue is still on screen.
        t += 100
        out = a.update(Sample(t, 2800, speedFor(3, 2800), false, null))
        assertEquals(SuppressedBy.ON_TIME, out.suppressedBy)

        // Drop back to re-arm, then pull again inside the 1.3 s gap.
        t += 100
        rpm = 2400
        out = a.update(Sample(t, rpm, speedFor(3, rpm), false, null))
        t += 100
        while (t <= 1_300) {
            rpm += 100
            out = a.update(Sample(t, rpm, speedFor(3, rpm), false, null))
            t += 100
        }
        assertTrue(out.rpmRise != null && out.rpmRise!! > 150, "not pulling: ${out.rpmRise}")
        assertEquals(SuppressedBy.GAP, out.suppressedBy)
    }
}
