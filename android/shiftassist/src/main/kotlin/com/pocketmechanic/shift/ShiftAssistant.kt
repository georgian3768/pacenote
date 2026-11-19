package com.pocketmechanic.shift

import kotlin.math.abs

/**
 * Shift-assistant core. Pure Kotlin, no Android dependencies, no I/O: the app
 * feeds it [Sample]s and renders the [Output]. Everything here is testable on
 * the JVM, which is why it lives in its own Gradle module.
 *
 * All numeric thresholds are provisional and marked TODO-tune: they come from
 * the Celerio's published idle/redline and from Session B replay, not from a
 * real drive with a calibrated throttle.
 */

/**
 * Eco / Normal / Sport are rpm *landings* for the next gear, not fractions of
 * the redline.
 *
 * A gear change is a ratio, so the rpm a driver should leave gear g at is
 * `landing * ratio(g) / ratio(g+1)`: the rpm the engine lands on in g+1. Using
 * the calibrated ladder (147.2 / 79.3 / 53.3 / 39.8 / 32.3 rpm per km/h,
 * 2026-11-09) NORMAL gives 3712 rpm in 1st, 2975 in 2nd, 2678 in 3rd and 2464
 * in 4th. That matches the shifts actually observed on the drive (1->2 at 3364,
 * 3->4 at 3017) and, unlike a fixed fraction, stays reachable in every gear -
 * the old 0.70 * redline rule needed 80-123 km/h in 3rd/4th.
 *
 * [downLandingRpm] is the landing the downshift rule is derived from, one gear
 * lower. It used to be a single 2200 for every mode, which put ECO's 5th-gear
 * downshift point (1785) above its own 4th-gear upshift landing (1600): the
 * assistant asked for 5th, then immediately asked to leave it. Each mode now
 * owns its landing, and SPORT deliberately borrows NORMAL's - a 2800 downshift
 * landing would hold every gear above the point the car actually pulls from.
 * These are POLICY values: the ladder is measured, the landings are chosen.
 */
enum class DriveMode(
    val upLandingRpm: Int,
    val downLandingRpm: Int,
    /** Not validated on a drive; the label belongs in the mode setting. */
    val experimental: Boolean = false,
) {
    ECO(1600, 1600),
    NORMAL(2000, 2000),
    SPORT(2800, 2000, experimental = true);

    companion object {
        /**
         * Matches [AssistantMode.DEFAULT]: a missing or unrecognised profile is
         * the mileage one. This is the replay CLI's fallback too, so old runs
         * stay reproducible by passing `--mode` explicitly.
         */
        val DEFAULT = ECO

        fun of(name: String?): DriveMode = when (name?.lowercase()) {
            "eco" -> ECO
            "sport" -> SPORT
            "normal" -> NORMAL
            else -> DEFAULT
        }
    }
}

data class Config(
    /**
     * The engine limit, not the dashboard's red zone. K10B: fuel cut in neutral
     * at about 6400 rpm (owner's manual); peak power 67 bhp @ 6000.
     */
    val redlineRpm: Int = 6400,
    /**
     * False until the driver confirms [redlineRpm] on the calibration screen.
     * The value is a default, so an unconfirmed one must not raise an over-rev
     * cue; the shift points do not depend on it at all (they come from the gear
     * ladder), which is why an unconfirmed redline no longer mutes them.
     */
    val redlineConfirmed: Boolean = true,
    val idleRpm: Int = 700,
    val gearCount: Int = 5,
    val minSpeedKph: Double = 15.0,      // below this, clutch/neutral: suppress
    val upHysteresisRpm: Int = 150,
    val downHysteresisRpm: Int = 120,
    /**
     * Ceiling on any upshift point, in every mode. A design choice (it keeps
     * SPORT's ratio-derived 1st/2nd points from running past the engine's
     * useful range), not a measured limit: see docs.
     */
    val upCapRpm: Int = 4000,
    /**
     * Floor on any downshift point, in every mode. A comfort/economy reference:
     * the factory AMT's own downshifts happen around 1100 rpm. NO DATA on the
     * engine's true lug limit.
     */
    val downFloorRpm: Int = 1100,
    /**
     * A downshift should land at least this far above the lower gear's own
     * upshift point, so the two rules cannot fight each other (the old single
     * 2200 landing gave 5th a 1785 down point against ECO's 1600 up landing).
     */
    val downLandingMarginRpm: Int = 300,
    /**
     * After the smoothed gear changes, the rpm-rise term is ignored for this
     * long. The shift's own rpm step and a rev-match blip look exactly like a
     * pull for a few hundred ms ("blank" in the cue log).
     */
    val gearChangeBlankMs: Long = 1000,
    val minCueGapMs: Long = 4_000,
    /** A downshift is advisory, so it is repeated less eagerly than an upshift. */
    val downCueGapMs: Long = 8_000,
    val minCueOnMs: Long = 800,
    val overRevFraction: Double = 0.95,  // 6400 * 0.95 = 6080 rpm
    val overRevHoldMs: Long = 300,
    /** Smoothed speed gain that counts as "pulling" (m/s^2). */
    val accelerateMps2: Double = 0.10,
    /** Smoothed rpm rise that counts as "pulling" (rpm/s). */
    val rpmRiseRpmPerSec: Double = 150.0,
    val throttleFullRaw: Int = 255,
    /** Closed-throttle raw from the wizard; -1 means "not measured". */
    val throttleIdleRaw: Int = -1,
)

data class Sample(
    val tMs: Long,
    val rpm: Int?,
    val speedKph: Double?,
    val brake: Boolean?,
    /** Raw throttle channel, null when the throttle wizard has not run. */
    val throttle: Int?,
    val canActive: Boolean = true,
    val engineRunning: Boolean? = true,
)

enum class Cue { UP, DOWN, OVER_REV }

data class Output(
    val gear: Int?,
    val cue: Cue?,
    val overRev: Boolean,
    val demand: Double?,
    val accelMps2: Double?,
    val reason: String,
    val mode: DriveMode,
    /** Thresholds the cue used, for the structured [cue] log line. */
    val upRpm: Int? = null,
    val downRpm: Int? = null,
    /** True when the pull test passed, i.e. an upshift is allowed to fire. */
    val accelerating: Boolean = false,
    /** True while the rpm-rise term is blanked after a gear change. */
    val blanked: Boolean = false,
    val rpmRise: Double? = null,
    /** Raw per-frame gear estimate, before the majority vote. */
    val rawGear: Int? = null,
    /** The frame's own inputs, echoed for the cue log. */
    val rpm: Int? = null,
    val speedKph: Double? = null,
    val braking: Boolean = false,
    /** Why this frame produced no cue; [SuppressedBy.NONE] when one fired. */
    val suppressedBy: SuppressedBy = SuppressedBy.NONE,
)

/**
 * Gear from rpm/speed clusters. `ratios[i]` is the calibrated rpm per km/h for
 * gear i+1; the wizard in S3B produces them. A sample that is not within
 * [tolerance] of any cluster is reported as "no gear" (clutch, neutral, or a
 * transient), which suppresses every cue.
 */
class GearEstimator(ratios: List<Double>, private val tolerance: Double = 0.12) {
    /** Calibrated rpm-per-km/h centres, gear i+1 at index i. */
    val centers = ratios.filter { it > 0.0 }

    init {
        require(centers.size == centers.distinct().size) { "duplicate gear ratios" }
    }

    fun gearFor(rpm: Int, speedKph: Double, minSpeedKph: Double = 15.0): Int? {
        if (speedKph < minSpeedKph || rpm <= 0) return null
        val ratio = rpm / speedKph
        var best = -1
        var bestErr = Double.MAX_VALUE
        centers.forEachIndexed { i, c ->
            val err = abs(ratio - c) / c
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        return if (best >= 0 && bestErr <= tolerance) best + 1 else null
    }
}

class ShiftAssistant(
    private val cfg: Config = Config(),
    private val estimator: GearEstimator,
) {
    var mode: DriveMode = DriveMode.NORMAL
        set(value) {
            field = value
        }

    private var lastCueMs: Long = Long.MIN_VALUE / 4
    /** Last cue time for the cue log; null until the first cue. */
    val lastCueAtMs: Long? get() = lastCueMs.takeIf { it != Long.MIN_VALUE / 4 }
    private var activeCue: Cue? = null
    private var activeSinceMs: Long = 0
    private var overRevSinceMs: Long? = null
    private var lastOverRevMs: Long = Long.MIN_VALUE / 4
    /** Last cue per gear: one gear cannot be nagged twice inside the gap. */
    private val lastCueByGear = HashMap<Int, Long>()
    /**
     * Hysteresis is a re-arm, not a raised cue point: a gear that has already
     * cued stays quiet until the rpm has come back below the threshold, so it
     * cannot chatter at the boundary. (It used to be added to the threshold,
     * which pushed the real cue point up by ~5%.)
     */
    private val armedUp = HashMap<Int, Boolean>()
    private val armedDown = HashMap<Int, Boolean>()

    /**
     * Samples kept for the smoothed accel/rpm-rise test. At 13 Hz a single-frame
     * delta is quantisation noise, and the old frame-to-frame test both fired on
     * noise and missed slow-but-real pulls.
     */
    private val trace = ArrayDeque<Sample>()

    /**
     * Gear smoothing. rpm (0x124) and speed (0x314) are updated by different
     * messages, so one merged frame can combine a fresh rpm with a stale speed.
     * On a real drive that made the estimate jump 3 -> 4 -> 5 -> 4 -> 3 inside
     * 1.4 s (measured 2026-11-05 from trip #4's frame log), which looks like "the
     * gear is not updating" and fires cues from the wrong gear. The estimate is
     * now the majority of the last [GEAR_WINDOW] frames, and needs
     * [GEAR_MIN_VOTES] agreeing frames before it is allowed to change.
     */
    private val gearHistory = ArrayDeque<Int?>()

    /** Last gear the smoother settled on, and when it changed, for the blank. */
    private var settledGear: Int? = null
    private var lastGearChangeMs: Long = Long.MIN_VALUE / 4

    private fun smoothGear(raw: Int?): Int? {
        if (raw == null) {
            gearHistory.clear()
            return null
        }
        gearHistory.addLast(raw)
        while (gearHistory.size > GEAR_WINDOW) gearHistory.removeFirst()
        if (gearHistory.size < GEAR_MIN_VOTES) return raw
        val counts = gearHistory.filterNotNull().groupingBy { it }.eachCount()
        val best = counts.entries.maxByOrNull { it.value } ?: return raw
        return if (best.value >= GEAR_MIN_VOTES) best.key else raw
    }

    fun reset() {
        gearHistory.clear()
        settledGear = null
        lastGearChangeMs = Long.MIN_VALUE / 4
        lastCueMs = Long.MIN_VALUE / 4
        activeCue = null
        overRevSinceMs = null
        lastOverRevMs = Long.MIN_VALUE / 4
        lastCueByGear.clear()
        armedUp.clear()
        armedDown.clear()
        trace.clear()
    }

    fun update(sample: Sample, warm: Boolean = true): Output {
        val rawGear =
            if (sample.rpm != null && sample.speedKph != null) {
                estimator.gearFor(sample.rpm, sample.speedKph, cfg.minSpeedKph)
            } else null
        val gear = smoothGear(rawGear)
        // A settled change is the moment the drivetrain is re-engaging; the
        // rpm step that goes with it is not a pull. Null frames (clutch in) do
        // not clear the last gear, so a 3 -> null -> 4 shift still counts.
        if (gear != null && settledGear != null && gear != settledGear) {
            lastGearChangeMs = sample.tMs
        }
        if (gear != null) settledGear = gear
        val blanked = sample.tMs - lastGearChangeMs < cfg.gearChangeBlankMs

        // Smoothed over a window, so a single frame's quantisation cannot fake a
        // pull (and a real pull is not missed between two noisy frames).
        val accel = smoothedAccel(sample)
        val rpmRise = smoothedRpmRise(sample)
        trace.addLast(sample)
        while (trace.size > 2 && sample.tMs - trace.first().tMs > SMOOTH_HISTORY_MS) {
            trace.removeFirst()
        }

        // Demand: calibrated throttle when available, rpm-rise heuristic otherwise.
        // The idle offset lives in Throttle so the card can show the same number
        // the cue logic used (42/250 used to read 17 % with the pedal released).
        val demand = Throttle.normalized(sample.throttle, cfg.throttleIdleRaw, cfg.throttleFullRaw)
        val rising = (rpmRise != null && rpmRise > cfg.rpmRiseRpmPerSec && !blanked)
        // Shift-up must not depend on the throttle channel: thr1/thr2 disagree on
        // this car (246 was seen while braking), so a demand gate silences real
        // cues and fires fake ones. A smoothed speed gain or rpm rise is the
        // signal that the driver is actually pulling through the gear.
        val accelerating = (accel != null && accel > cfg.accelerateMps2) || rising
        val braking = sample.brake == true
        val running = sample.engineRunning != false && sample.canActive

        // Over-rev is independent of the shift cue and separately rate limited.
        val rpm = sample.rpm
        val overRevNow = cfg.redlineConfirmed && rpm != null &&
            rpm >= (cfg.redlineRpm * cfg.overRevFraction).toInt()
        if (overRevNow) {
            if (overRevSinceMs == null) overRevSinceMs = sample.tMs
        } else {
            overRevSinceMs = null
        }
        val overRevHeld = overRevSinceMs?.let { sample.tMs - it >= cfg.overRevHoldMs } == true
        var cue: Cue? = null
        var reason = ""

        if (overRevHeld && sample.tMs - lastOverRevMs >= cfg.minCueGapMs) {
            cue = Cue.OVER_REV
            lastOverRevMs = sample.tMs
            reason = "rpm $rpm >= ${(cfg.redlineRpm * cfg.overRevFraction).toInt()} for ${cfg.overRevHoldMs} ms"
        }

        val upRpm = gear?.let(::upThresholdFor)
        val downRpm = gear?.let(::downThresholdFor)
        var suppressedBy = SuppressedBy.NONE

        if (cue == null && gear != null && running) {
            val gapOk = sample.tMs - lastCueMs >= cfg.minCueGapMs
            val onTimeOk = activeCue == null || sample.tMs - activeSinceMs >= cfg.minCueOnMs
            val upGapOk = sample.tMs - (lastCueByGear[gear] ?: Long.MIN_VALUE / 4) >= cfg.minCueGapMs
            val downGapOk =
                sample.tMs - (lastCueByGear[gear] ?: Long.MIN_VALUE / 4) >= cfg.downCueGapMs

            val upArmed = armedUp[gear] ?: true
            val downArmed = armedDown[gear] ?: true
            if (upRpm != null && rpm != null && rpm < upRpm - cfg.upHysteresisRpm) {
                armedUp[gear] = true
            }
            if (downRpm != null && rpm != null && rpm > downRpm + cfg.downHysteresisRpm) {
                armedDown[gear] = true
            }

            when {
                rpm != null && upRpm != null && gear < cfg.gearCount &&
                    rpm >= upRpm && upArmed &&
                    accelerating && !braking && warm && gapOk && upGapOk && onTimeOk -> {
                    cue = Cue.UP
                    reason = "rpm $rpm >= up $upRpm (gear $gear, landing " +
                        "${mode.upLandingRpm}, accel ${accel?.let { "%.2f".format(it) } ?: "n/a"})"
                }

                rpm != null && downRpm != null && gear > 1 &&
                    rpm <= downRpm && downArmed &&
                    !accelerating && warm && gapOk && downGapOk && onTimeOk -> {
                    // Braking and coasting are the normal ways to arrive at a
                    // downshift, so this cue is allowed in both (the old rule
                    // required throttle >= 0.5 and suppressed the one case that
                    // matters). It is advisory and separately rate limited.
                    cue = Cue.DOWN
                    reason = "rpm $rpm <= down $downRpm (gear $gear, landing " +
                        "${mode.downLandingRpm})"
                }
            }

            if (cue == null) {
                suppressedBy = suppressionReason(
                    rpm = rpm,
                    gear = gear,
                    upRpm = upRpm,
                    downRpm = downRpm,
                    upArmed = upArmed,
                    downArmed = downArmed,
                    accelerating = accelerating,
                    braking = braking,
                    blanked = blanked,
                    gapOk = gapOk,
                    upGapOk = upGapOk,
                    downGapOk = downGapOk,
                    onTimeOk = onTimeOk,
                )
            }
        }

        if (cue == null) {
            if (gear == null) suppressedBy = SuppressedBy.GEAR_NULL
            // The over-rev condition is held but its own 4 s gap swallowed the
            // cue; that is more useful to see than whatever the shift rule said.
            if (overRevHeld) suppressedBy = SuppressedBy.OVER_REV
        }

        if (cue != null) {
            lastCueMs = sample.tMs
            gear?.let { g ->
                lastCueByGear[g] = sample.tMs
                when (cue) {
                    Cue.UP -> armedUp[g] = false
                    Cue.DOWN -> armedDown[g] = false
                    Cue.OVER_REV -> {}
                }
            }
            if (cue != activeCue) {
                activeCue = cue
                activeSinceMs = sample.tMs
            }
        } else if (activeCue != null && sample.tMs - activeSinceMs >= cfg.minCueOnMs) {
            activeCue = null
        }

        return Output(
            gear = gear,
            cue = cue,
            overRev = overRevHeld,
            demand = demand,
            accelMps2 = accel,
            reason = reason,
            mode = mode,
            upRpm = upRpm,
            downRpm = downRpm,
            accelerating = accelerating,
            blanked = blanked,
            rpmRise = rpmRise,
            rawGear = rawGear,
            rpm = rpm,
            speedKph = sample.speedKph,
            braking = braking,
            suppressedBy = suppressedBy,
        )
    }

    /**
     * Why an eligible frame produced no cue. Ordered by the gate the decision
     * stopped at, so the log names the first thing that would have to change.
     * [SuppressedBy.COAST] is never returned: downshifts are allowed while
     * coasting, so nothing suppresses them for it.
     */
    private fun suppressionReason(
        rpm: Int?,
        gear: Int,
        upRpm: Int?,
        downRpm: Int?,
        upArmed: Boolean,
        downArmed: Boolean,
        accelerating: Boolean,
        braking: Boolean,
        blanked: Boolean,
        gapOk: Boolean,
        upGapOk: Boolean,
        downGapOk: Boolean,
        onTimeOk: Boolean,
    ): SuppressedBy {
        val upCandidate = rpm != null && upRpm != null && gear < cfg.gearCount && rpm >= upRpm
        val downCandidate = rpm != null && downRpm != null && gear > 1 && rpm <= downRpm
        return when {
            // The assistant is still showing the previous cue; that is the most
            // immediate reason this frame added nothing, whatever else is true.
            !onTimeOk -> SuppressedBy.ON_TIME
            // A latched gear is quiet by design, not by a timing gate.
            upCandidate && !upArmed -> SuppressedBy.NONE
            upCandidate && braking -> SuppressedBy.BRAKE
            upCandidate && !accelerating ->
                if (blanked) SuppressedBy.BLANK else SuppressedBy.NOT_ACCELERATING
            upCandidate && (!gapOk || !upGapOk) -> SuppressedBy.GAP
            downCandidate && !downArmed -> SuppressedBy.NONE
            downCandidate && accelerating -> SuppressedBy.ACCELERATING
            downCandidate && (!gapOk || !downGapOk) -> SuppressedBy.GAP
            else -> SuppressedBy.NONE
        }
    }

    /**
     * rpm at which gear [gear] should be left: the rpm the engine would land on
     * in the next gear, capped so a short ladder cannot ask for an unburnable
     * rpm. Null for the top gear or a ladder too short to know.
     */
    private fun upThresholdFor(gear: Int): Int? {
        val centers = estimator.centers
        if (gear < 1 || gear >= centers.size) return null
        val from = centers[gear - 1]
        val to = centers[gear]
        if (to <= 0.0) return null
        return minOf(cfg.upCapRpm, (mode.upLandingRpm * from / to).toInt())
    }

    /**
     * rpm below which gear [gear] should be dropped one. Same ratio rule, with
     * the landing pulled down by [Config.downLandingMarginRpm] so the downshift
     * always lands clear of the gear's own upshift point, and floored at
     * [Config.downFloorRpm].
     */
    private fun downThresholdFor(gear: Int): Int? {
        val centers = estimator.centers
        if (gear <= 1 || gear > centers.size) return null
        val from = centers[gear - 1]
        val to = centers[gear - 2]
        if (to <= 0.0) return null
        val landing = (mode.downLandingRpm - cfg.downLandingMarginRpm).coerceAtLeast(0)
        return maxOf(cfg.downFloorRpm, (landing * from / to).toInt())
    }

    /** Most recent trace sample at least [SMOOTH_MIN_WINDOW_MS] old. */
    private fun reference(sample: Sample): Sample? =
        trace.lastOrNull { sample.tMs - it.tMs >= SMOOTH_MIN_WINDOW_MS }

    private fun smoothedAccel(sample: Sample): Double? {
        val speed = sample.speedKph ?: return null
        val ref = reference(sample) ?: return null
        val refSpeed = ref.speedKph ?: return null
        val dtMs = sample.tMs - ref.tMs
        if (dtMs > SMOOTH_MAX_WINDOW_MS) return null
        return (speed - refSpeed) / 3.6 / (dtMs / 1000.0)
    }

    private fun smoothedRpmRise(sample: Sample): Double? {
        val rpm = sample.rpm ?: return null
        val ref = reference(sample) ?: return null
        val refRpm = ref.rpm ?: return null
        val dtMs = sample.tMs - ref.tMs
        if (dtMs > SMOOTH_MAX_WINDOW_MS) return null
        return (rpm - refRpm) / (dtMs / 1000.0)
    }

    companion object {
        /** Frames in the majority vote for the gear estimate. */
        const val GEAR_WINDOW = 5

        /** Agreeing frames needed before the gear is allowed to change. */
        const val GEAR_MIN_VOTES = 3

        /** Accel / rpm-rise window, long enough to swamp 1 km/h quantisation. */
        const val SMOOTH_MIN_WINDOW_MS = 500L
        const val SMOOTH_MAX_WINDOW_MS = 1_200L
        const val SMOOTH_HISTORY_MS = 2_400L
    }
}
