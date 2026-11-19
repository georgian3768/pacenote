package com.pocketmechanic.spike

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketmechanic.shift.Cue
import com.pocketmechanic.shift.GearDisplay
import com.pocketmechanic.shift.Output
import com.pocketmechanic.shift.SpeedScale

/**
 * Turns the stream into the panel's snapshot.
 *
 * This logic used to live in [ComposeOverlay], which meant only the overlay
 * could show a correct panel: the hold rules (a cue stays until the gear
 * changes, the gear digit survives a clutch-in, numbers repaint at 4 Hz while
 * the cue ring is immediate) are the panel's, not the window's. The landing
 * screen needs exactly the same rules, so they live here and both hosts feed
 * their [TelemetryPanel] from one [PanelFeed].
 *
 * Main thread only: Compose reads [state], so a write from the stream thread
 * would be a data race the snapshot state cannot fix. [ComposeOverlay] posts;
 * the landing already collects on the main dispatcher.
 */
class PanelFeed(private val settings: AppSettings) {

    var state by mutableStateOf(PanelUi())
        private set

    /**
     * Throttle channel sampled on every frame, for the bar only.
     *
     * Deliberately not part of [state]: the digits must stay at 4 Hz, but the
     * bar has to reach the screen at the stream's own rate (~10 Hz) and be
     * interpolated between samples. Keeping both in one snapshot would either
     * drag the bar down to 4 Hz or repaint every digit ten times a second.
     * The panel reads this through a lambda, so a write here invalidates only
     * the bar's draw scope.
     */
    var barThr1 by mutableStateOf<Int?>(null)
        private set

    /**
     * Fired when the cue changes, including to null. The overlay uses it to
     * flash the border and beep; the landing has neither, so it leaves it null.
     */
    var onCue: ((Cue?) -> Unit)? = null

    private var lastTextMs = 0L
    private var lastCue: Cue? = null

    // A cue stays until the gear changes; with no gear estimate a 15 s safety
    // net clears it.
    private var pendingCue: Cue? = null
    private var pendingFromGear: Int? = null
    private var pendingSinceMs = 0L

    private var previewCue: Cue? = null
    private var previewUntilMs = 0L

    /**
     * Keeps the gear digit on screen for a moment while the estimator is silent
     * (clutch-in, walking pace); without it the panel blinks the gear off at
     * every shift. The resets below are the other half of the deal: holding a
     * gear across a cue or an outage would be lying.
     */
    private val gearDisplay = GearDisplay()

    fun onFrame(
        frame: Telemetry,
        out: Output?,
        now: Long = SystemClock.elapsedRealtime(),
    ) {
        val cue = out?.cue
        val previewing = now < previewUntilMs
        val repaintText = now - lastTextMs >= TEXT_REFRESH_MS
        if (repaintText) lastTextMs = now

        // The bar is the one fast-lane control: it follows every frame so the
        // tween in TelemetryPanel has a fresh target, while the digits below
        // stay on the 250 ms clock.
        barThr1 = frame.thr1

        val canSilentNow = !frame.canActive
        // A bus that just went quiet cannot vouch for the gear that was on
        // screen, so the hold is dropped on the transition, not per frame.
        if (canSilentNow && !state.canSilent) gearDisplay.reset()

        // Ring changes immediately (it is the cue); numbers at 4 Hz, because
        // the stream repaints hundreds of times per minute and the digits
        // flicker.
        state = state.copy(
            cue = if (previewing) previewCue else (cue ?: pendingCue),
            previewing = previewing,
            canSilent = canSilentNow,
            linkLost = false,
        )

        if (cue != null) {
            // A cue is the moment the gear is about to change: holding the old
            // digit through it would show the driver a gear they are already
            // leaving.
            gearDisplay.reset()
            pendingCue = cue
            pendingFromGear = out.gear
            pendingSinceMs = now
        } else {
            pendingCue?.let { pending ->
                val from = pendingFromGear
                val gear = out?.gear
                val resolved = when (pending) {
                    Cue.UP, Cue.DOWN -> from != null && gear != null && gear != from
                    Cue.OVER_REV -> true
                }
                if (resolved || now - pendingSinceMs > PENDING_TIMEOUT_MS) {
                    pendingCue = null
                    pendingFromGear = null
                    gearDisplay.reset()
                }
            }
        }

        if (repaintText) {
            state = state.copy(
                rpm = frame.rpm,
                speedKph = SpeedScale.applyFloat(frame.speedKph, settings.gpsSpeedScale),
                gear = out?.gear,
                // Held only while the estimator is momentarily silent; the
                // resets above stop the hold from surviving a cue or outage.
                gearShown = gearDisplay.update(out?.gear, now),
                cueFresh = now - pendingSinceMs < CUE_FRESH_MS,
                silentMs = 0,
                // The 4 Hz throttle number and the brake chip share this
                // snapshot; the bar itself reads barThr1 every frame above.
                thr1 = frame.thr1,
                brake = frame.brake,
            )
        }

        if (cue != lastCue) {
            lastCue = cue
            onCue?.invoke(cue)
        }
    }

    fun onStreamLost(silentMs: Long) {
        pendingCue = null
        pendingFromGear = null
        previewCue = null
        previewUntilMs = 0L
        lastCue = null
        gearDisplay.reset()
        barThr1 = null
        state = PanelUi(linkLost = true, silentMs = silentMs)
    }

    fun previewCue(cue: Cue?) {
        previewCue = cue
        previewUntilMs = if (cue == null) 0L else SystemClock.elapsedRealtime() + PREVIEW_MS
        state = state.copy(cue = cue, cueFresh = cue != null, previewing = cue != null)
    }

    /** Tear the snapshot down: a new window starts with nothing to show. */
    fun reset() {
        lastTextMs = 0L
        lastCue = null
        pendingCue = null
        pendingFromGear = null
        pendingSinceMs = 0L
        previewCue = null
        previewUntilMs = 0L
        gearDisplay.reset()
        barThr1 = null
        state = PanelUi()
    }

    private companion object {
        const val TEXT_REFRESH_MS = 250L
        const val PREVIEW_MS = 5_000L
        const val PENDING_TIMEOUT_MS = 15_000L
    }
}
