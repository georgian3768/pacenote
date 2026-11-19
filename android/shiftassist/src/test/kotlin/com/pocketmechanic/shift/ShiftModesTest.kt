package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Per-mode shift points. The formulas are derived from the gear ladder, so the
 * expected values below are the answer to
 *
 *   up(g)   = min(4000, landing * L(g) / L(g+1))
 *   down(g) = max(1100, (landingDown - 300) * L(g) / L(g-1))
 *
 * and not a table the assistant carries. [formulasFollowTheLadder] proves that
 * by swapping the ladder and watching every number move.
 *
 * Tagging used in the comments: DERIVED = from the measured ladder, POLICY =
 * chosen (the landings, the 4000 cap, the 300 margin), NO DATA = no
 * measurement exists (the engine's real lug limit).
 */
class ShiftModesTest {

    /**
     * Rounded fallback ladder (3 significant figures): what `AssistantHost` and
     * [Replay] use when no wizard ratios are stored. The handset stores the
     * six-figure capture in [sixFigure].
     */
    private val shipped = listOf(147.2, 79.3, 53.3, 39.8, 32.3)

    /**
     * The same measurement at six figures, and the ladder the handset actually
     * stores (pulled 2026-11-11). The spec quotes this ladder, but its expected
     * table was computed from the rounded one; see [formulasFollowTheLadder]
     * and the deliverables note.
     */
    private val sixFigure = listOf(147.223, 79.300, 53.279, 39.775, 32.329)

    private fun assistant(mode: DriveMode, ratios: List<Double> = shipped) =
        ShiftAssistant(Config(), GearEstimator(ratios)).also { it.mode = mode }

    private fun speedFor(gear: Int, rpm: Int, ratios: List<Double> = shipped): Double =
        rpm / ratios[gear - 1]

    /**
     * Thresholds for [gear] without caring whether the frame also cues: the
     * assistant reports the thresholds it used on every [Output].
     */
    private fun thresholds(mode: DriveMode, gear: Int, ratios: List<Double> = shipped): Output {
        // At least minSpeedKph, otherwise the estimator calls it clutch/neutral.
        // 1st is the tight one: 2000 rpm is only 13.6 km/h at 147.2 rpm/km/h.
        val rpm = if (gear == 1) 2400 else 1200 + 100 * gear
        return assistant(mode, ratios).update(
            Sample(0, rpm, speedFor(gear, rpm, ratios), brake = false, throttle = null)
        )
    }

    @Test
    @DisplayName("up points are landing x ladder(g)/ladder(g+1), capped at 4000")
    fun upshiftTable() {
        val expected = mapOf(
            DriveMode.ECO to listOf(2969, 2380, 2142, 1971),
            DriveMode.NORMAL to listOf(3712, 2975, 2678, 2464),
            DriveMode.SPORT to listOf(4000, 4000, 3749, 3450),
        )
        for ((mode, row) in expected) {
            for (gear in 1..4) {
                val out = thresholds(mode, gear)
                assertEquals(gear, out.gear, "${mode.name} gear $gear not recognised")
                val up = out.upRpm
                assertTrue(up != null, "${mode.name} gear $gear has no up threshold")
                assertTrue(
                    abs(up!! - row[gear - 1]) <= 1,
                    "${mode.name} up($gear) = $up, expected ${row[gear - 1]}",
                )
            }
            assertEquals(null, thresholds(mode, 5).upRpm, "5th must not have an up point")
        }
    }

    @Test
    @DisplayName("down points are (landingDown - 300) x ladder(g)/ladder(g-1), floored at 1100")
    fun downshiftTable() {
        val expected = mapOf(
            DriveMode.ECO to listOf(1100, 1100, 1100, 1100),
            DriveMode.NORMAL to listOf(1100, 1142, 1269, 1379),
            DriveMode.SPORT to listOf(1100, 1142, 1269, 1379),
        )
        for ((mode, row) in expected) {
            assertEquals(null, thresholds(mode, 1).downRpm, "1st must not have a down point")
            for (gear in 2..5) {
                val out = thresholds(mode, gear)
                assertEquals(gear, out.gear, "${mode.name} gear $gear not recognised")
                val down = out.downRpm
                assertTrue(down != null, "${mode.name} gear $gear has no down threshold")
                assertTrue(
                    abs(down!! - row[gear - 2]) <= 1,
                    "${mode.name} down($gear) = $down, expected ${row[gear - 2]}",
                )
            }
        }
    }

    @Test
    @DisplayName("SPORT downshifts on NORMAL's landing, not its own 2800")
    fun sportBorrowsNormalDownLanding() {
        assertEquals(2000, DriveMode.SPORT.downLandingRpm)
        assertEquals(2000, DriveMode.NORMAL.downLandingRpm)
        assertEquals(2800, DriveMode.SPORT.upLandingRpm, "SPORT's up landing stays 2800")
        // If SPORT used 2800 - 300 the 5th-gear point would be 2030, not 1379.
        assertEquals(1379, thresholds(DriveMode.SPORT, 5).downRpm)
    }

    @Test
    @DisplayName("every down point keeps 300 rpm of daylight under its landing and the 1100 floor")
    fun downPointInvariants() {
        for (mode in DriveMode.entries) {
            for (gear in 2..5) {
                val down = thresholds(mode, gear).downRpm!!
                assertTrue(down >= 1100, "${mode.name} down($gear) = $down is under the floor")
                val landing = mode.downLandingRpm
                assertTrue(
                    landing - down >= 300,
                    "${mode.name} down($gear) = $down is within 300 rpm of the $landing landing",
                )
            }
        }
    }

    @Test
    @DisplayName("no upshift point runs past the 4000 rpm cap in any mode")
    fun upCapHolds() {
        for (mode in DriveMode.entries) {
            for (gear in 1..4) {
                val up = thresholds(mode, gear).upRpm!!
                assertTrue(up <= 4000, "${mode.name} up($gear) = $up exceeds the cap")
                assertTrue(up > 0, "${mode.name} up($gear) = $up")
            }
        }
    }

    @Test
    @DisplayName("re-arm points keep the 300 rpm landing margin and the 4000 cap in every mode")
    fun rearmPointsKeepTheirMargins() {
        // Re-arm is threshold -/+ hysteresis (ShiftAssistant's armedUp/armedDown):
        // an upshift re-arms when rpm drops below up - 150, a downshift when rpm
        // rises above down + 120. Both must stay on the safe side of the same
        // limits the firing points obey, or the latch would be able to re-arm
        // inside the band the landing margin was meant to keep clear.
        val cfg = Config()
        for (mode in DriveMode.entries) {
            for (gear in 1..4) {
                val up = thresholds(mode, gear).upRpm!!
                val upRearm = up - cfg.upHysteresisRpm
                assertTrue(
                    upRearm <= 4000,
                    "${mode.name} up($gear) re-arms at $upRearm, past the 4000 cap",
                )
            }
            for (gear in 2..5) {
                val down = thresholds(mode, gear).downRpm!!
                val downRearm = down + cfg.downHysteresisRpm
                assertTrue(
                    mode.downLandingRpm - downRearm >= 300,
                    "${mode.name} down($gear) re-arms at $downRearm, less than 300 rpm " +
                        "under the ${mode.downLandingRpm} landing",
                )
            }
        }
    }

    @Test
    @DisplayName("the table is derived from the ladder: a different ladder moves every point")
    fun formulasFollowTheLadder() {
        // Six-figure ladder. Nothing here is a table lookup: if the assistant
        // hard-coded the shipped values these would all be wrong.
        val eco = listOf(2970, 2381, 2143, 1968)
        val normal = listOf(3713, 2976, 2679, 2460)
        val sport = listOf(4000, 4000, 3750, 3444)
        for ((mode, row) in mapOf(
            DriveMode.ECO to eco,
            DriveMode.NORMAL to normal,
            DriveMode.SPORT to sport,
        )) {
            for (gear in 1..4) {
                assertEquals(
                    row[gear - 1],
                    thresholds(mode, gear, sixFigure).upRpm,
                    "${mode.name} up($gear) with the six-figure ladder",
                )
            }
        }
        // Down points move too: NORMAL 5th is 1381 on this ladder, 1379 on the
        // shipped one. (This is the cell the spec's +-1 tolerance cannot cover.)
        assertEquals(1381, thresholds(DriveMode.NORMAL, 5, sixFigure).downRpm)
        assertEquals(1142, thresholds(DriveMode.NORMAL, 3, sixFigure).downRpm)
    }

    @Test
    @DisplayName("landing in the next gear never asks for a downshift back")
    fun landingDoesNotHunt() {
        // The hunt the old single 2200 landing caused: ECO left 4th at 1971 rpm,
        // landed in 5th at 1600, and 1600 <= 1785 was an immediate "down" cue.
        for (mode in DriveMode.entries) {
            for (gear in 1..4) {
                val up = thresholds(mode, gear).upRpm!!
                val landing = (up * shipped[gear] / shipped[gear - 1]).toInt()
                val downNext = thresholds(mode, gear + 1).downRpm!!

                val a = assistant(mode)
                val cues = (0..5).map { i ->
                    a.update(
                        Sample(
                            i * 100L,
                            landing,
                            speedFor(gear + 1, landing),
                            brake = false,
                            throttle = null,
                        )
                    ).cue
                }
                assertTrue(
                    cues.none { it == Cue.DOWN },
                    "${mode.name} $gear->${gear + 1}: landing at $landing cues down " +
                        "(down(${gear + 1}) = $downNext): $cues",
                )
                assertTrue(
                    landing - downNext >= 300,
                    "${mode.name} $gear->${gear + 1}: landing $landing is only " +
                        "${landing - downNext} rpm above down(${gear + 1}) = $downNext",
                )
            }
        }
    }

    @Test
    @DisplayName("the shift's own rpm step is not a pull for 1000 ms after the gear changes")
    fun gearChangeBlank() {
        // 3rd at 60 km/h is 3198 rpm; 4th is 2388. From there the rpm climbs
        // 200 rpm/s while the speed is flat - a rev-match blip or the shift's own
        // step, not a pull (speed acceleration is 0.0 m/s^2, well under 0.10).
        val a = assistant(DriveMode.NORMAL)
        val outs = mutableListOf<Output>()
        for (t in 0L..1750L step 100) {
            // Speed is held at 60 km/h and only the rpm moves, so the smoothed
            // speed acceleration stays at 0.0.
            val rpm = if (t < 500) 3198 else 2388 + ((t - 500) / 100).toInt() * 20
            outs += a.update(Sample(t, rpm, 60.0, brake = false, throttle = null))
        }
        val changeIndex = outs.indexOfFirst { it.gear == 4 }
        val changeAt = changeIndex * 100L
        assertTrue(changeAt in 500L..800L, "gear changed at $changeAt ms, expected the majority vote")

        for ((i, out) in outs.withIndex()) {
            val t = i * 100L
            if (t in changeAt until changeAt + 1000) {
                assertFalse(out.accelerating, "accelerating at $t ms, inside the blank")
                assertTrue(out.blanked, "blank flag lost at $t ms")
            } else if (t >= changeAt + 1000) {
                assertFalse(out.blanked, "still blanked at $t ms")
                assertTrue(out.accelerating, "not accelerating at $t ms, after the blank")
            }
        }
    }
}
