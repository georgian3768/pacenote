package com.pocketmechanic.shift

import kotlin.math.abs

/**
 * Trip accounting: distance, idle/over-rev time, hard accelerations and an eco
 * score. Pure Kotlin so the arithmetic is unit tested instead of trusted.
 *
 * Everything is fed from telemetry frames; nothing here talks to the network,
 * the CAN bus or the database.
 */
data class TripFrame(
    val tMs: Long,
    val speedKph: Double?,
    val rpm: Int?,
    val canActive: Boolean = true,
    val engineRunning: Boolean? = true,
    val distRaw: Long? = null,
    val brake: Boolean? = null,
)

/** Result of one trip, ready to be stored. */
data class TripSummary(
    val durationMs: Long,
    val distanceKm: Double,
    val maxSpeedKph: Double,
    val maxRpm: Int,
    val avgRpm: Int,
    val movingMs: Long,
    val idleMs: Long,
    val overRevMs: Long,
    val hardAccelCount: Int,
    val distRawDelta: Long?,
    /** Integrated km vs. the raw counter's own scale; null when not comparable. */
    val distRawRatio: Double?,
    val ecoScore: Int,
)

/** One speed reading kept for the smoothed acceleration window. */
private data class TracePoint(val tMs: Long, val speedKph: Double)

/**
 * Accumulates frames into a [TripSummary].
 *
 * Distance is integrated from the CAN speed, because the raw `dist_raw` counter's
 * unit is unknown (it is cross-checked, not trusted). Gaps longer than
 * [MAX_STEP_MS] are ignored: the stream stalls during reconnects, and integrating
 * across a 30 s hole would invent kilometres.
 *
 * Hard accelerations are measured over a smoothed window ([ACCEL_MIN_WINDOW_MS]
 * .. [ACCEL_MAX_WINDOW_MS]), not frame-to-frame: at 13 Hz the single-frame delta
 * is dominated by the +/-1 km/h quantisation of the speed signal, which counted
 * 657 "hard accelerations" on one drive whose largest real delta was 1 km/h
 * (measured 2026-11-08). Braking frames are excluded.
 */
class TripAccountant(
    private val redlineRpm: Int = 6400,
    private val maxStepMs: Long = 1_000,
    private val hardAccelMps2: Double = 1.5,
) {

    private var startedMs: Long? = null
    private var lastMs: Long? = null
    // Distance has its own timeline: frames without a speed value (CAN silent,
    // engine off) must not reset the baseline, or every interleaved null frame
    // silently throws away the distance that followed it (measured 2026-10-31:
    // 0.13 km recorded for a ramp worth ~0.36 km).
    private var lastSpeed: Double? = null
    private var lastSpeedMs: Long? = null
    // Speed history for the smoothed acceleration test. Bounded by
    // ACCEL_MAX_WINDOW_MS so a reconnect gap cannot enter the window.
    private val speedTrace = ArrayDeque<TracePoint>()
    // dist_raw wraps at 8192 (~8100 counts/km) and restarts at every key cycle.
    // The raw value itself is meaningless; only the modulo-unwrapped delta is
    // reported, and outlier readings (the one 16384 seen on 2026-11-08) are
    // ignored rather than poisoning the total.
    private var distRawPrev: Long? = null
    private var distRawTotal = 0L
    private var distRawSeen = false

    private var distanceKm = 0.0
    private var maxSpeed = 0.0
    private var maxRpm = 0
    private var rpmSum = 0L
    private var rpmSamples = 0
    private var movingMs = 0L
    private var idleMs = 0L
    private var overRevMs = 0L
    private var hardAccel = 0
    private var integratedSteps = 0
    private var skippedSteps = 0
    private var lastHardAccelMs = Long.MIN_VALUE / 4

    val startedAtMs: Long? get() = startedMs
    val currentDistanceKm: Double get() = distanceKm
    val frames: Int get() = rpmSamples

    /** Diagnostics: how many speed steps were integrated vs. rejected. */
    val steps: String get() = "integrated=$integratedSteps skipped=$skippedSteps"

    fun update(f: TripFrame): TripSummary? {
        if (startedMs == null) {
            startedMs = f.tMs
            lastMs = f.tMs
            lastSpeed = f.speedKph
            // The first frame must start the speed timeline too, otherwise the
            // first segment falls back to the rectangle rule from a later frame.
            if (f.speedKph != null) lastSpeedMs = f.tMs
            f.distRaw?.let { raw ->
                if (raw in 0 until DIST_RAW_WRAP) {
                    distRawPrev = raw
                    distRawSeen = true
                }
            }
            f.rpm?.let { maxRpm = it; rpmSum += it; rpmSamples++ }
            pushSpeedTrace(f.tMs, f.speedKph)
            return null
        }

        val prevMs = lastMs ?: f.tMs
        val dtMs = f.tMs - prevMs
        val dt = dtMs / 1000.0
        val usable = dtMs > 0 && dtMs <= maxStepMs && f.canActive

        val speed = f.speedKph
        val prevSpeed = lastSpeed
        val prevSpeedMs = lastSpeedMs
        if (usable) {
            if (speed != null && prevSpeedMs != null) {
                val speedDtMs = f.tMs - prevSpeedMs
                if (speedDtMs > 0 && speedDtMs <= maxStepMs) {
                    val speedDt = speedDtMs / 1000.0
                    // Trapezoidal: the frame rate is ~13 Hz, so a rectangle rule
                    // drifts on acceleration. The average is computed first: a
                    // `?:` binds looser than `/`, which silently scaled by 3600.
                    val averageKph = prevSpeed?.let { (speed + it) / 2.0 } ?: speed
                    distanceKm += averageKph / 3600.0 * speedDt
                    integratedSteps++
                }
            } else if (speed != null) {
                distanceKm += speed / 3600.0 * dt
                integratedSteps++
            }

            if (speed != null) {
                if (speed > 3.0) movingMs += dtMs
                if (speed < 1.0 && f.engineRunning != false) idleMs += dtMs
                if (speed > maxSpeed) maxSpeed = speed

                // Counted with a cooldown: one pull crosses the threshold on
                // several frames, and the point is "how often does this driver
                // floor it", not how many frames saw it. The window is smoothed
                // so quantisation noise cannot fake a pull, and braking frames
                // are excluded (a panic stop measured >1.5 m/s^2 "acceleration"
                // from the speed signal's own lag).
                val accel = smoothedAccel(f.tMs, speed)
                if (f.brake != true && accel != null && accel > hardAccelMps2 &&
                    f.tMs - lastHardAccelMs > HARD_ACCEL_COOLDOWN_MS
                ) {
                    hardAccel++
                    lastHardAccelMs = f.tMs
                }
            }

            val rpm = f.rpm
            if (rpm != null && rpm >= (redlineRpm * 0.95).toInt()) overRevMs += dtMs
        }

        f.rpm?.let {
            rpmSum += it
            rpmSamples++
            if (it > maxRpm) maxRpm = it
        }
        f.distRaw?.let { raw ->
            // Ignore the known outliers and unwrap the modulo counter. A reset
            // (key cycle) makes the value jump backwards, which the modulo turns
            // into a small forward step instead of a huge negative one.
            if (raw in 0 until DIST_RAW_WRAP) {
                val prev = distRawPrev
                if (prev != null) {
                    val step = ((raw - prev) % DIST_RAW_WRAP + DIST_RAW_WRAP) % DIST_RAW_WRAP
                    distRawTotal += step
                }
                distRawPrev = raw
                distRawSeen = true
            }
        }

        if (!usable && speed != null) skippedSteps++

        lastMs = f.tMs
        if (speed != null) {
            lastSpeed = speed
            lastSpeedMs = f.tMs
            pushSpeedTrace(f.tMs, speed)
        }
        return null
    }

    private fun pushSpeedTrace(tMs: Long, speed: Double?) {
        if (speed == null) return
        speedTrace.addLast(TracePoint(tMs, speed))
        while (speedTrace.size > 2 && tMs - speedTrace.first().tMs > ACCEL_MAX_WINDOW_MS) {
            speedTrace.removeFirst()
        }
    }

    /**
     * Average acceleration over a window old enough to swamp the 1 km/h speed
     * quantisation. Null when there is no reference point, or when the gap is so
     * long that the average would be meaningless.
     */
    private fun smoothedAccel(nowMs: Long, speed: Double): Double? {
        val ref = speedTrace.lastOrNull { nowMs - it.tMs >= ACCEL_MIN_WINDOW_MS } ?: return null
        val dtMs = nowMs - ref.tMs
        if (dtMs > ACCEL_MAX_WINDOW_MS) return null
        return (speed - ref.speedKph) / 3.6 / (dtMs / 1000.0)
    }

    fun finish(endedMs: Long): TripSummary {
        val start = startedMs ?: endedMs
        val duration = (endedMs - start).coerceAtLeast(0)
        val rawDelta = if (distRawSeen) distRawTotal else null

        // The raw counter's scale is unknown, so only the ratio is reported: it
        // is a cross-check ("did we integrate roughly the same shape?"), not a
        // calibration. A ratio far from the median across trips means one of the
        // two sources is broken.
        val ratio = if (rawDelta != null && rawDelta > 0 && distanceKm > 0.01) {
            rawDelta / distanceKm
        } else null

        return TripSummary(
            durationMs = duration,
            distanceKm = distanceKm,
            maxSpeedKph = maxSpeed,
            maxRpm = maxRpm,
            avgRpm = if (rpmSamples > 0) (rpmSum / rpmSamples).toInt() else 0,
            movingMs = movingMs,
            idleMs = idleMs,
            overRevMs = overRevMs,
            hardAccelCount = hardAccel,
            distRawDelta = rawDelta,
            distRawRatio = ratio,
            ecoScore = ecoScore(duration, distanceKm, movingMs, idleMs, overRevMs, hardAccel, maxSpeed)
        )
    }

    /**
     * The score [finish] would produce right now, or null before the first
     * frame. It reuses the documented formula instead of duplicating it, so a
     * live badge can never disagree with the saved row.
     *
     * The number moves around early in a trip: the penalties are normalised per
     * moving second, and a single redline pull in the first minute looks far
     * worse than the same pull in a ten-minute drive. Callers present it as an
     * estimate, not a verdict.
     */
    fun liveEcoScore(nowMs: Long): Int? {
        val start = startedMs ?: return null
        val duration = (nowMs - start).coerceAtLeast(0)
        return ecoScore(duration, distanceKm, movingMs, idleMs, overRevMs, hardAccel, maxSpeed)
    }

    companion object {
        const val HARD_ACCEL_COOLDOWN_MS = 3_000L

        /** Smoothed acceleration window: never shorter than the signal's noise. */
        const val ACCEL_MIN_WINDOW_MS = 500L
        const val ACCEL_MAX_WINDOW_MS = 1_200L

        /** `dist_raw` is a 13-bit counter (0..8191) that runs ~8100 counts/km. */
        const val DIST_RAW_WRAP = 8192L

        /**
         * Documented formula (docs/ECO-SCORE.md): 100 minus four weighted
         * penalties, each normalised to 0..1. A shorter trip with one redline
         * pull is not punished more than a long clean one, because over-rev is
         * measured per moving second, not per trip.
         */
        fun ecoScore(
            durationMs: Long,
            distanceKm: Double,
            movingMs: Long,
            idleMs: Long,
            overRevMs: Long,
            hardAccelCount: Int,
            maxSpeedKph: Double,
        ): Int {
            val moving = movingMs.coerceAtLeast(1)
            val total = durationMs.coerceAtLeast(1)

            val overRev = (overRevMs.toDouble() / moving).coerceIn(0.0, 1.0)
            val idling = (idleMs.toDouble() / total).coerceIn(0.0, 1.0)
            // Hard accelerations per kilometre, saturating at 2/km.
            val accel = ((hardAccelCount.toDouble() / (distanceKm + 0.5)) / 2.0).coerceIn(0.0, 1.0)
            val speeding = ((maxSpeedKph - 100.0) / 30.0).coerceIn(0.0, 1.0)

            val penalty = 0.40 * overRev + 0.25 * idling + 0.20 * accel + 0.15 * speeding
            return (100.0 * (1.0 - penalty)).toInt().coerceIn(0, 100)
        }
    }
}

/**
 * Distance reminders: "service in N km". Kept here so the trigger arithmetic is
 * testable without Android notifications.
 */
class DistanceReminder(private val intervalKm: Double, private var odometerKm: Double) {

    private var lastFiredKm = 0.0

    fun addTrip(km: Double): Double {
        odometerKm += km
        return odometerKm
    }

    /** The crossed multiple, or null. Fires once per multiple, never twice. */
    fun crossed(): Double? {
        if (intervalKm <= 0.0) return null
        val mark = kotlin.math.floor(odometerKm / intervalKm) * intervalKm
        if (mark > lastFiredKm && mark > 0.0) {
            lastFiredKm = mark
            return mark
        }
        return null
    }

    fun odometerKm(): Double = odometerKm

    fun remainingKm(): Double {
        if (intervalKm <= 0.0) return 0.0
        val next = (kotlin.math.floor(odometerKm / intervalKm) + 1) * intervalKm
        return abs(next - odometerKm)
    }

}
