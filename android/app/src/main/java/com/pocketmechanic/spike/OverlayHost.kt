package com.pocketmechanic.spike

import android.content.Context
import com.pocketmechanic.shift.Cue
import com.pocketmechanic.shift.Output

/**
 * Selects the overlay implementation from the `compose_overlay` debug pref and
 * forwards the whole [TelemetryOverlay] surface to it.
 *
 * This exists instead of a direct [OverlayController] reference so the service
 * does not care which renderer is active, and so swapping renderers is a
 * deliberate [restart] rather than an app restart.
 */
class OverlayHost(private val context: Context) : TelemetryOverlay {

    /**
     * Stored here rather than on the implementation: [restart] builds a fresh
     * renderer, and the policy's dismissal hook has to survive that swap.
     */
    override var onUserDismiss: (() -> Unit)? = null
        set(value) {
            field = value
            impl.onUserDismiss = value
        }

    private var impl: TelemetryOverlay = build()

    /** Remembered across [restart]: a renderer swap must not drop the layout. */
    private var navLayout = false

    private fun build(): TelemetryOverlay =
        (if (AppSettings(context).composeOverlay) {
            ComposeOverlay(context)
        } else {
            OverlayController(context)
        }).also {
            it.onUserDismiss = onUserDismiss
            it.setNavLayout(navLayout)
        }

    /** Tear the current renderer down and construct the one the pref selects. */
    fun restart() {
        impl.hide()
        impl = build()
    }

    fun activeName(): String = if (impl is ComposeOverlay) "compose" else "legacy"

    override fun canDraw(): Boolean = impl.canDraw()
    override fun isShown(): Boolean = impl.isShown()
    override fun show() = impl.show()
    override fun hide() = impl.hide()
    override fun setNavLayout(enabled: Boolean) {
        navLayout = enabled
        impl.setNavLayout(enabled)
    }

    /** What the service last asked for, for the dev screen's label. */
    fun navLayout(): Boolean = navLayout
    override fun onOrientationChanged() = impl.onOrientationChanged()
    override fun onKeepScreenOnChanged() = impl.onKeepScreenOnChanged()
    override fun onFrame(frame: Telemetry, out: Output?) = impl.onFrame(frame, out)
    override fun onStreamLost(silentMs: Long) = impl.onStreamLost(silentMs)
    override fun previewCue(cue: Cue?) = impl.previewCue(cue)
    override fun testOverRevBeep() = impl.testOverRevBeep()
    override fun flashTest() = impl.flashTest()
}
