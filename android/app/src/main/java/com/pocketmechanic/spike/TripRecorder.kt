package com.pocketmechanic.spike

import android.os.SystemClock
import com.pocketmechanic.shift.SpeedScale
import com.pocketmechanic.shift.TripAccountant
import com.pocketmechanic.shift.TripFrame
import com.pocketmechanic.shift.TripSummary

/**
 * Decides when a trip starts and ends, and feeds [TripAccountant].
 *
 * Rules from the handoff: start when the engine is running, end when the CAN
 * stream has been inactive for [END_AFTER_CAN_SILENT_MS]. The engine-running bit
 * is itself provisional (decode is incomplete), so the silence rule is what
 * actually closes trips: it also covers "driver switched the ignition off and
 * the ESP32 kept streaming the keep-alive frame" and "phone left the car".
 *
 * The reported end is the last *meaningful* frame - CAN live, engine not off,
 * rpm above zero - not the moment the silence window expired. Trips #19/#20
 * (2026-11-08) were inflated by 60 s and 21 s of frames after the
 * car had stopped, which also dragged the eco score down.
 */
class TripRecorder(var redlineRpm: Int = 6400) {

    /**
     * The GPS wizard's CAN->GPS multiplier. Applied here, once, at the boundary:
     * the assistant and the accountant must reason about the same speed, and
     * applying it in two places would double-scale it.
     */
    var speedScale: Double = 1.0

    private var accountant: TripAccountant? = null
    private var startedAtWall = 0L
    private var lastCanActiveMs = 0L
    // The trip ends when the frames stop, not when the grace window expires, so
    // the last frame's clocks are what a silence close reports.
    private var lastFrameMs = 0L
    private var lastFrameWallMs = 0L
    // Last frame that actually described a running car (see isMeaningful).
    private var lastMeaningfulMs = 0L
    private var lastMeaningfulWallMs = 0L
    private var tripSpeedScale = 1.0

    val recording: Boolean get() = accountant != null
    val distanceKm: Double get() = accountant?.currentDistanceKm ?: 0.0

    /** Milliseconds since the current trip started, 0 when idle. */
    val elapsedMs: Long
        get() = accountant?.startedAtMs?.let {
            (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
        } ?: 0L

    /** Live eco estimate for the running trip, null when idle. */
    fun liveEcoScore(nowMs: Long = SystemClock.elapsedRealtime()): Int? =
        accountant?.liveEcoScore(nowMs)

    /** Scale captured when the current trip started (not the live setting). */
    val recordingSpeedScale: Double get() = tripSpeedScale

    /** Result of a finished trip, for the caller to persist. */
    data class Finished(
        val summary: TripSummary,
        val startedAt: Long,
        val endedAt: Long,
        /** Scale the trip's speeds were recorded under. */
        val speedScale: Double = 1.0,
    )

    @Synchronized
    fun onFrame(frame: Telemetry, nowMs: Long = SystemClock.elapsedRealtime()): Finished? {
        if (frame.canActive) lastCanActiveMs = nowMs
        lastFrameMs = nowMs
        lastFrameWallMs = System.currentTimeMillis()
        if (isMeaningful(frame)) {
            lastMeaningfulMs = nowMs
            lastMeaningfulWallMs = lastFrameWallMs
        }

        if (accountant == null) {
            if (frame.engineRunning != true) return null
            tripSpeedScale = speedScale
            accountant = TripAccountant(redlineRpm)
            startedAtWall = lastFrameWallMs
            lastCanActiveMs = nowMs
            EventLog.add("trip", "start (engine running)")
        }

        val acc = accountant ?: return null
        acc.update(
            TripFrame(
                tMs = nowMs,
                speedKph = SpeedScale.apply(frame.speedKph, tripSpeedScale),
                rpm = frame.rpm?.toInt(),
                canActive = frame.canActive,
                engineRunning = frame.engineRunning,
                distRaw = frame.distRaw?.toLong(),
                brake = frame.brake,
            )
        )

        if (!frame.canActive && nowMs - lastCanActiveMs >= END_AFTER_CAN_SILENT_MS) {
            return finish(endMs(), endWallMs())
        }
        return null
    }

    /**
     * Dev action, "ignition off" path, and the service's silence watchdog: close
     * the trip now. Synchronized because the watchdog runs on the ticker while
     * the stream thread may still be delivering frames.
     */
    @Synchronized
    fun forceEnd(nowMs: Long = SystemClock.elapsedRealtime()): Finished? =
        if (accountant == null) null else finish(nowMs, System.currentTimeMillis())

    /**
     * Close because the stream went quiet. Ends the trip at its last meaningful
     * frame: the grace window exists so a short stop does not split the drive, it
     * must not add that stale minute to the duration or the eco score.
     */
    @Synchronized
    fun endOnSilence(): Finished? =
        if (accountant == null) null else finish(endMs(), endWallMs())

    /**
     * True when the frame describes a running car rather than the ESP32's
     * keep-alive chatter after key-off (canActive stays true, rpm is 0).
     */
    private fun isMeaningful(frame: Telemetry): Boolean =
        frame.canActive && frame.engineRunning != false && (frame.rpm?.toInt() ?: 0) > 0

    private fun endMs(): Long = if (lastMeaningfulMs > 0) lastMeaningfulMs else lastFrameMs

    private fun endWallMs(): Long =
        if (lastMeaningfulMs > 0) lastMeaningfulWallMs else lastFrameWallMs

    private fun finish(nowMs: Long, wallMs: Long): Finished? {
        val acc = accountant ?: return null
        val summary = acc.finish(nowMs)
        accountant = null
        EventLog.add(
            "trip",
            "end: %.2f km, %d s, score %d, max %d rpm, dist_raw %s, %s".format(
                summary.distanceKm,
                summary.durationMs / 1000,
                summary.ecoScore,
                summary.maxRpm,
                summary.distRawDelta?.toString() ?: "n/a",
                acc.steps
            )
        )
        return Finished(summary, startedAtWall, wallMs, tripSpeedScale)
    }

    companion object {
        /** Grace window: shorter stops must not split a trip into two rows. */
        const val END_AFTER_CAN_SILENT_MS = 60_000L

        /**
         * Stand-in redline for an unconfirmed profile. Over-rev is a comparison
         * against the redline, so an unreachable threshold counts none of it
         * instead of inventing a limit the driver never confirmed.
         */
        const val UNKNOWN_REDLINE = Int.MAX_VALUE
    }
}
