package com.pocketmechanic.spike

import com.pocketmechanic.shift.Cue
import com.pocketmechanic.shift.Output

/**
 * Everything the service needs from a floating overlay, independent of how it is
 * drawn. Two implementations exist while the Compose rewrite is in progress:
 * [OverlayController] (Views, the default) and [ComposeOverlay] (behind the
 * `compose_overlay` debug pref).
 *
 * Keeping this surface small is deliberate: a rewrite should not be able to
 * change what the stream/assistant contract is, only how it is presented.
 */
interface TelemetryOverlay {
    /**
     * The driver dropped the bubble on the hide target.
     *
     * The policy lives in the service, so the overlay only reports the gesture:
     * whether the bubble comes back (and when) is not the renderer's business.
     * A property rather than a method because both implementations already hold
     * a reference to whatever paints them.
     */
    var onUserDismiss: (() -> Unit)?

    fun canDraw(): Boolean
    fun isShown(): Boolean
    fun show()
    fun hide()

    /**
     * Navigation layout on/off: while a navigation app is in front the bubble
     * becomes the edge HUD, so the maneuver card and the route stay visible.
     *
     * A layout, not a mode: AUTO / ALWAYS / OFF and the hide-target dismissal
     * behave exactly the same either way. Renderers that only know the bubble
     * ignore it (the default), which keeps the legacy overlay unaffected until
     * it is retired.
     */
    fun setNavLayout(enabled: Boolean) {}

    /** Rotation can make a parked bubble off-screen; clamp it back. */
    fun onOrientationChanged()

    /**
     * The "keep screen on" setting changed while a window may be up. Window
     * flags are fixed when a window is added, so the live windows are patched
     * instead of waiting for the next rebuild.
     */
    fun onKeepScreenOnChanged()

    fun onFrame(frame: Telemetry, out: Output?)

    /** No frames for a while: stop presenting stale values as current. */
    fun onStreamLost(silentMs: Long)

    /** Dev only: paint a cue without going through the assistant. */
    fun previewCue(cue: Cue?)

    /** Dev only: play the over-rev tone once. */
    fun testOverRevBeep()

    /** Dev only: flash the border green, then red. */
    fun flashTest()
}
