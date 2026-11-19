package com.pocketmechanic.spike

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pocketmechanic.shift.Cue
import com.pocketmechanic.shift.Output
import com.pocketmechanic.shift.Throttle
import kotlin.math.abs

/**
 * The Compose bubble (stage 1 of the overlay rewrite), behind the
 * `compose_overlay` debug pref.
 *
 * Deliberately one window and one ComposeView. The legacy overlay needed a
 * separate card, border and hide-target window; those arrive in later stages.
 * Keeping the first step to the bubble means a failure here can only cost the
 * bubble, not the trip recorder or the stream.
 *
 * Overlay windows have no Activity, so the ComposeView gets its lifecycle,
 * saved-state and view-model owners wired by hand. The composition strategy is
 * DisposeOnViewTreeLifecycleDestroyed so [hide] really tears Compose down.
 */
class ComposeOverlay(private val context: Context) : TelemetryOverlay {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val settings by lazy { AppSettings(context) }

    /**
     * A WindowManager window is owned by the service, so it is not recreated
     * when the system theme changes. Snapshot state makes the write recompose
     * the bubble/panel in place; [onOrientationChanged] refreshes it.
     */
    private val tokens = mutableStateOf(context.themeTokens())

    private var composeView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var shown = false

    private var border: OverlayBorderView? = null

    private var cardView: ComposeView? = null
    private var cardOwner: OverlayLifecycleOwner? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var cardVisible = false

    /**
     * Full-screen touch layer behind the card. It exists because the panel no
     * longer hides itself: the only way out is a tap outside it, and that tap
     * must not reach the app underneath (the user's signal is "I want the map
     * back", so the tap is consumed here).
     */
    private var scrimView: ComposeView? = null
    private var scrimOwner: OverlayLifecycleOwner? = null

    /** The bubble window is detached while the panel is up: never both. */
    private var bubbleAttached = false

    /**
     * Navigation layout on/off, from the service. Read on the main thread only:
     * [setNavLayout] posts, and every reader runs inside a main-thread post.
     * Only the geometry and the content change; the window, drag and dismissal
     * behaviour are the same as the bubble's.
     */
    private var navLayout = false

    /** "Drop here to hide" target, shown only while the car is parked/slow. */
    private var hideTarget: TextView? = null
    private val hideTargetBounds = Rect()

    private var audio: AudioCue? = null

    /**
     * The panel snapshot, shared with the landing screen: the same hold rules
     * and the same 4 Hz repaint, whether the panel is an overlay or a segment
     * of the app.
     */
    private val panel = PanelFeed(settings)

    init {
        // Audio and the border flash belong to the overlay, not the panel: the
        // landing shows the cue but must not beep over the app it is part of.
        panel.onCue = { cue ->
            flash(cue)
            if (cue != null && settings.audioCues) initialiseAudio().play(cue)
        }
    }

    /** False until the first frame of this showing: see [firstFrameCheck]. */
    private var everHadFrame = false

    /**
     * If no frame ever arrives, the service's stream watchdog never fires (it
     * measures the gap after the *last* frame), so a bubble with a dead link
     * would sit there saying nothing at all. This one-shot turns "never
     * connected" into the same NO LINK state as "connection died".
     */
    private val firstFrameCheck = Runnable {
        if (!shown) return@Runnable
        if (!everHadFrame) onStreamLost(STREAM_LOST_MS)
    }

    // Drag bookkeeping. Positions are raw screen coordinates so moving the
    // window cannot make the pointer jump: raw = view-location + event position.
    private var dragStartLpX = 0
    private var dragStartLpY = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragMoved = false
    private var longPressed = false

    override var onUserDismiss: (() -> Unit)? = null

    override fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    override fun isShown(): Boolean = shown

    override fun show() {
        main.post {
            if (shown) return@post
            if (!canDraw()) {
                EventLog.add("overlay", "compose: not shown, overlay permission missing")
                return@post
            }
            try {
                AssistantHost.syncMode(settings)
                addBubble()
                addBorder()
                shown = true
                everHadFrame = false
                main.removeCallbacks(firstFrameCheck)
                main.postDelayed(firstFrameCheck, STREAM_LOST_MS)
                EventLog.add("overlay", "compose bubble + border added")
            } catch (t: Throwable) {
                EventLog.add("overlay", "compose add failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    override fun hide() {
        main.removeCallbacks(firstFrameCheck)
        main.post {
            removeHideTarget()
            removeCard(reattachBubble = false)
            removeBorder()
            setBubbleAttached(false)
            composeView = null
            owner?.destroy()
            owner = null
            params = null
            shown = false
            panel.reset()
            audio?.release()
            audio = null
            EventLog.add("overlay", "compose overlays removed")
        }
    }

    override fun onOrientationChanged() {
        main.post {
            // A configuration change is also how a system light/dark switch
            // arrives at a service window: refresh before rebuilding the card so
            // the new bubble and panel are born in the right palette.
            tokens.value = context.themeTokens()
            removeHideTarget()
            reclampBubble()
            if (cardVisible) {
                // Rebuild against the new orientation's window parameters; the
                // bubble stays detached while the panel is up.
                removeCard(reattachBubble = false)
                addCard()
            }
        }
    }

    /**
     * Switch between the bubble and the edge HUD.
     *
     * The window is rebuilt rather than just re-laid-out: the two layouts have
     * different sizes and different content, and a rebuild is the one path that
     * already produces both reliably (see [rebuildBubble]). The HUD returns to
     * its resting place (left edge, vertically centred) because that is where
     * the design puts it; a position dragged for the bubble means nothing for a
     * 132x146 card.
     */
    override fun setNavLayout(enabled: Boolean) {
        main.post {
            if (navLayout == enabled) return@post
            navLayout = enabled
            EventLog.add("overlay", "compose nav layout ${if (enabled) "hud" else "bubble"}")
            // While the card is open the bubble is not attached; the rebuild
            // happens when the card closes and asks for it back.
            if (shown && bubbleAttached) rebuildBubble(resetPosition = true)
        }
    }

    override fun onKeepScreenOnChanged() {
        main.post {
            val flag = keepScreenOnFlag()
            fun patch(lp: WindowManager.LayoutParams?) {
                if (lp == null) return
                lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()) or flag
            }
            params?.let {
                patch(it)
                composeView?.let { v -> runCatching { wm?.updateViewLayout(v, it) } }
            }
            cardParams?.let {
                patch(it)
                cardView?.let { v -> runCatching { wm?.updateViewLayout(v, it) } }
            }
        }
    }

    /** FLAG_KEEP_SCREEN_ON when the setting is on, 0 otherwise. */
    private fun keepScreenOnFlag(): Int =
        if (settings.keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0

    override fun onFrame(frame: Telemetry, out: Output?) {
        everHadFrame = true
        main.post {
            // Hidden by the user: keep draining frames, but paint nothing.
            if (!shown) return@post
            val recovering = panel.state.linkLost
            panel.onFrame(frame, out)
            // The first live frame after an outage repaints from a fresh
            // composition: the bubble that was showing NO LINK is not trusted
            // to bring itself back (see [rebuildBubble]).
            if (recovering && bubbleAttached) rebuildBubble()
        }
    }

    override fun onStreamLost(silentMs: Long) {
        main.post {
            if (!shown) return@post
            panel.onStreamLost(silentMs)
            EventLog.add(
                "overlay",
                "compose: stream lost for ${silentMs / 1000}s - values cleared"
            )
        }
    }

    override fun previewCue(cue: Cue?) {
        main.post { panel.previewCue(cue) }
    }

    override fun testOverRevBeep() {
        main.post { initialiseAudio().play(Cue.OVER_REV) }
    }

    override fun flashTest() {
        main.post {
            flash(Cue.UP)
            main.postDelayed({ flash(Cue.DOWN) }, 1200)
            main.postDelayed({ flash(null) }, 2400)
        }
    }

    // --- border --------------------------------------------------------------

    private fun addBorder() {
        val view = OverlayBorderView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm?.addView(view, lp) }
            .onFailure { EventLog.add("overlay", "compose border add failed: ${it.message}") }
        view.alpha = 0f
        border = view
    }

    private fun removeBorder() {
        border?.let { runCatching { wm?.removeView(it) } }
        border = null
    }

    private fun flash(cue: Cue?) {
        val view = border ?: return
        val color = when (cue) {
            Cue.UP -> OverlayBorderView.COLOR_UP
            Cue.DOWN, Cue.OVER_REV -> OverlayBorderView.COLOR_DOWN
            null -> {
                view.animate().cancel()
                view.animate().alpha(0f).setDuration(FADE_MS).start()
                return
            }
        }
        view.setColor(color)
        view.animate().cancel()
        view.alpha = 1f
    }

    // --- window --------------------------------------------------------------

    private fun addBubble() {
        val area = bubbleArea()
        val width = dp(if (navLayout) HUD_W_DP else BUBBLE_DP)
        val height = dp(if (navLayout) HUD_H_DP else BUBBLE_DP)
        val lp = WindowManager.LayoutParams(
            width, height,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                keepScreenOnFlag(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // The HUD lives on the left edge, vertically centred: on the side
            // the driver's hand already is, clear of the maneuver card (top)
            // and the ETA bar (bottom) that navigation apps own.
            x = if (navLayout) area.left + dp(6) else dp(10)
            y = if (navLayout) {
                (area.top + (area.height() - height) / 2).coerceAtLeast(area.top)
            } else {
                dp(200)
            }
        }
        params = lp

        val lifecycleOwner = OverlayLifecycleOwner().also { it.create() }
        owner = lifecycleOwner

        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                CompositionLocalProvider(LocalTokens provides tokens.value) {
                    if (navLayout) HudContent(panel.state) else BubbleContent(panel.state)
                }
            }
        }
        composeView = view
        attachDrag(view)

        bubbleAttached = false
        runCatching { wm?.addView(view, lp) }
            .onSuccess { bubbleAttached = true }
            .onFailure { EventLog.add("overlay", "compose bubble add failed: ${it.message}") }
        lifecycleOwner.start()
    }

    /**
     * Drag to move, tap to toggle the card, long-press to arm the hide target.
     *
     * Positions are raw screen coordinates measured from where the finger went
     * down. Deriving them from the Compose pointer position plus
     * `getLocationOnScreen` fed the window's own movement back into the next
     * delta (the view location lags a layout pass), so a long press with a
     * little finger jitter made the bubble jitter and run away.
     */
    private fun attachDrag(view: ComposeView) {
        view.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartLpX = lp.x
                    dragStartLpY = lp.y
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragMoved = false
                    longPressed = false
                    main.removeCallbacks(longPressRunnable)
                    main.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()
                    if (!longPressed && !dragMoved &&
                        (abs(dx) > dp(DRAG_SLOP_DP) || abs(dy) > dp(DRAG_SLOP_DP))
                    ) {
                        main.removeCallbacks(longPressRunnable)
                        dragMoved = true
                        // The card is a peek; moving the bubble dismisses it.
                        removeCard()
                    }
                    lp.x = dragStartLpX + dx
                    lp.y = dragStartLpY + dy
                    clampBubble(lp)
                    runCatching { wm?.updateViewLayout(view, lp) }
                    if (hideTarget != null) highlightHideTarget(isOverHideTarget(lp))
                    true
                }

                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPressRunnable)
                    when {
                        longPressed -> decideDrop(lp)
                        dragMoved -> snapBubble(lp)
                        else -> toggleCard()
                    }
                    runCatching { wm?.updateViewLayout(view, lp) }
                    dragMoved = false
                    longPressed = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPressRunnable)
                    removeHideTarget()
                    snapBubble(lp)
                    runCatching { wm?.updateViewLayout(view, lp) }
                    dragMoved = false
                    longPressed = false
                    true
                }

                else -> false
            }
        }
    }

    private val longPressRunnable = Runnable {
        if (!dragMoved && !longPressed) {
            longPressed = true
            onLongPress()
        }
    }

    private fun decideDrop(lp: WindowManager.LayoutParams) {
        if (hideTarget == null) {
            snapBubble(lp)
            return
        }
        val hide = isOverHideTarget(lp)
        removeHideTarget()
        if (hide) hideBubbleByUser() else snapBubble(lp)
    }

    private fun reclampBubble() {
        val lp = params ?: return
        val view = composeView ?: return
        clampBubble(lp)
        runCatching { wm?.updateViewLayout(view, lp) }
    }

    /**
     * Usable area in window-layout coordinates. `lp.x/y` are offsets into the
     * parent frame, which starts below the status bar, so the insets are
     * subtracted here (same measurement as the legacy overlay).
     */
    private fun bubbleArea(): android.graphics.Rect {
        val manager = wm ?: run {
            val m = context.resources.displayMetrics
            return android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = manager.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                return android.graphics.Rect(
                    0,
                    0,
                    (bounds.width() - insets.left - insets.right)
                        .coerceAtLeast(dp(BUBBLE_DP)),
                    (bounds.height() - insets.top - insets.bottom)
                        .coerceAtLeast(dp(BUBBLE_DP)),
                )
            }.onFailure {
                EventLog.add("overlay", "compose: window metrics failed: ${it.message}")
            }
        }
        val m = context.resources.displayMetrics
        return android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    private fun clampBubble(lp: WindowManager.LayoutParams) {
        val width = if (lp.width > 1) lp.width else dp(BUBBLE_DP)
        val height = if (lp.height > 1) lp.height else dp(BUBBLE_DP)
        val area = bubbleArea()
        lp.x = lp.x.coerceIn(area.left, (area.right - width).coerceAtLeast(area.left))
        lp.y = lp.y.coerceIn(area.top, (area.bottom - height).coerceAtLeast(area.top))
    }

    private fun snapBubble(lp: WindowManager.LayoutParams) {
        val width = if (lp.width > 1) lp.width else dp(BUBBLE_DP)
        val height = if (lp.height > 1) lp.height else dp(BUBBLE_DP)
        val area = bubbleArea()
        val centerX = lp.x + width / 2
        val centerY = lp.y + height / 2
        val toLeft = centerX - area.left
        val toRight = area.right - centerX
        val toTop = centerY - area.top
        val toBottom = area.bottom - centerY
        when (minOf(toLeft, toRight, toTop, toBottom)) {
            toLeft -> lp.x = area.left
            toRight -> lp.x = area.right - width
            toTop -> lp.y = area.top
            else -> lp.y = area.bottom - height
        }
        clampBubble(lp)
    }

    // --- card ---------------------------------------------------------------

    private fun toggleCard() {
        // Posted: addCard() detaches the bubble window, and doing that from
        // inside the bubble's own ACTION_UP dispatch is asking for trouble.
        main.post { if (cardVisible) removeCard() else addCard() }
    }

    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun addCard() {
        if (cardView != null) return
        // One surface at a time: the bubble goes away while the panel is up.
        setBubbleAttached(false)
        addScrim()
        val metrics = context.resources.displayMetrics
        val landscape = isLandscape()
        val cardWidth = (metrics.widthPixels * 0.36f).toInt()
        // The rail's job is to be unmissable in the driver's peripheral vision,
        // so in landscape it takes the 15-20 % of the panel width the design
        // asked for instead of a fixed hairline. In portrait the panel is nearly
        // the full screen wide, where the same percentage would be a slab and
        // steal the speed column, so the physical width is reused instead.
        val railWidthDp = if (landscape) {
            (cardWidth / metrics.density * RAIL_LAND_FRACTION).toInt().coerceIn(40, 64)
        } else {
            RAIL_PORTRAIT_DP
        }
        val availableHeight = availableHeightPx() - dp(CARD_MARGIN_DP * 2)
        val availableHeightDp = (availableHeight / metrics.density).toInt()
        val lp = if (landscape) {
            WindowManager.LayoutParams(
                cardWidth,
                // Entire available height, not the content's: the panel is a
                // cockpit column, and a card that grew with its text would move
                // every time a value changed width.
                availableHeight,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or keepScreenOnFlag(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = dp(10)
            }
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or keepScreenOnFlag(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                // Overlay windows are laid out over the whole display, so a
                // bottom-gravity card sits under the 3-button navigation bar
                // unless its height is added back. Recomputed on every build of
                // the card, which is also what the rotation rebuild goes through.
                y = dp(CARD_BOTTOM_DP) + navBarHeight()
            }
        }
        cardParams = lp

        val lifecycleOwner = OverlayLifecycleOwner().also { it.create() }
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                val trip by SpikeService.trip.collectAsState()
                val mode by AssistantHost.mode.collectAsState()
                CompositionLocalProvider(LocalTokens provides tokens.value) {
                    TelemetryPanel(
                        ui = panel.state,
                        barThr1 = { panel.barThr1 },
                        trip = trip,
                        landscape = landscape,
                        // Full height only in landscape: the portrait sheet
                        // still sizes itself to its content and hugs the bottom
                        // edge.
                        fullHeight = landscape,
                        availableHeightDp = availableHeightDp,
                        railWidthDp = railWidthDp,
                        unit = unitLabel(),
                        // Read once, when the card is built: calibration cannot
                        // change while the panel is up (Calib leaves the panel
                        // first), and reading prefs per recomposition would be
                        // four reads a second.
                        throttleIdleRaw = settings.throttleIdleRaw,
                        throttleFullRaw = settings.throttleFullRaw,
                        // Any action opens another app: the panel has done its
                        // job, and the bubble comes back on top of that app.
                        onMaps = {
                            removeCard()
                            OverlayLauncher.openMaps(context)
                        },
                        onMusic = {
                            removeCard()
                            OverlayLauncher.openMusic(context, settings)
                        },
                        onCalib = {
                            removeCard()
                            OverlayLauncher.openCalibration(context)
                        },
                        mode = mode,
                        onCycleMode = {
                            val next = mode.next()
                            AssistantHost.setMode(next, settings)
                            EventLog.add(
                                "overlay",
                                "mode -> ${next.name.lowercase()} (panel chip)"
                            )
                        },
                        onHide = { removeCard() },
                    )
                }
            }
        }
        cardOwner = lifecycleOwner
        cardView = view
        runCatching { wm?.addView(view, lp) }
            .onFailure { EventLog.add("overlay", "compose card add failed: ${it.message}") }
        lifecycleOwner.start()
        cardVisible = true
        EventLog.add(
            "overlay",
            "compose card shown (${if (landscape) "landscape/right" else "portrait/bottom"})"
        )
    }

    /**
     * Adds the "tap anywhere outside to dismiss" layer. It is added before the
     * card so the card window stacks above it and keeps its own taps.
     */
    private fun addScrim() {
        if (scrimView != null) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val lifecycleOwner = OverlayLifecycleOwner().also { it.create() }
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                CompositionLocalProvider(LocalTokens provides tokens.value) {
                    ScrimContent { removeCard() }
                }
            }
        }
        scrimOwner = lifecycleOwner
        scrimView = view
        runCatching { wm?.addView(view, lp) }
            .onFailure { EventLog.add("overlay", "compose scrim add failed: ${it.message}") }
        lifecycleOwner.start()
    }

    private fun removeScrim() {
        scrimView?.let { runCatching { wm?.removeView(it) } }
        scrimView = null
        scrimOwner?.destroy()
        scrimOwner = null
    }

    /**
     * Removes the panel and its scrim. By default the bubble comes back, because
     * an overlay with neither surface would look like it had crashed. Callers
     * that are tearing the whole overlay down pass [reattachBubble] = false.
     */
    private fun removeCard(reattachBubble: Boolean = true) {
        if (cardView == null && scrimView == null) {
            if (reattachBubble) setBubbleAttached(true)
            return
        }
        removeScrim()
        cardView?.let { runCatching { wm?.removeView(it) } }
        cardView = null
        cardParams = null
        cardOwner?.destroy()
        cardOwner = null
        cardVisible = false
        if (reattachBubble) setBubbleAttached(true)
        EventLog.add(
            "overlay",
            "compose card hidden (${if (reattachBubble) "bubble back" else "overlay closing"})"
        )
    }

    /** Attach/detach the bubble window. */
    private fun setBubbleAttached(attached: Boolean) {
        if (attached == bubbleAttached) return
        if (!attached) {
            val view = composeView ?: return
            runCatching { wm?.removeView(view) }
            bubbleAttached = false
            return
        }
        // Returning from the card rebuilds instead of re-adding the detached
        // view: that view keeps the composition (and the recomposer) it had
        // when the card opened, so it could come back frozen on the values from
        // before the panel was ever opened (see [rebuildBubble]).
        rebuildBubble()
    }

    /**
     * Replace the bubble window with a freshly built one, keeping its position.
     *
     * A `ComposeView` that leaves its window keeps its composition; re-adding
     * it re-attaches the layout but nothing guarantees the old recomposer is
     * still alive. That produced a bubble stuck on "NO LINK" - dashed ring and
     * all - while the panel next to it showed live values, and the only cure
     * was toggling the overlay off and on to force a rebuild (measured
     * 2026-11-16). Building a new view always starts from the current
     * [PanelFeed] snapshot, which is what the panel already shows.
     *
     * [resetPosition] is for a layout change: the old coordinates were chosen
     * for the other shape, so the new surface starts at its own resting place.
     */
    private fun rebuildBubble(resetPosition: Boolean = false) {
        val old = composeView ?: return
        val lp = params
        val x = if (resetPosition) null else lp?.x
        val y = if (resetPosition) null else lp?.y
        runCatching { wm?.removeView(old) }
        composeView = null
        owner?.destroy()
        owner = null
        bubbleAttached = false
        addBubble()
        val fresh = params ?: return
        if (x != null && y != null) {
            fresh.x = x
            fresh.y = y
            clampBubble(fresh)
            composeView?.let { runCatching { wm?.updateViewLayout(it, fresh) } }
        }
    }

    // --- hide target --------------------------------------------------------

    private fun onLongPress() {
        composeView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!canHideNow()) {
            EventLog.add(
                "overlay",
                "compose hide gated: moving ${panel.state.speedKph?.toInt() ?: "-"} km/h"
            )
            return
        }
        showHideTarget()
    }

    /**
     * Hiding the bubble is only allowed when it cannot be a driving aid being
     * switched off by accident: parked/very slow, or already disconnected.
     */
    private fun canHideNow(): Boolean {
        val speed = panel.state.speedKph
        return panel.state.linkLost || speed == null || speed < HIDE_MAX_SPEED_KPH
    }

    private fun showHideTarget() {
        if (hideTarget != null) return
        val size = dp(64)
        val area = bubbleArea()
        val x = area.centerX() - size / 2
        val y = (area.bottom - size - dp(28)).coerceAtLeast(area.top)
        hideTargetBounds.set(x, y, x + size, y + size)

        val theme = tokens.value
        val target = TextView(context).apply {
            text = "✕"
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(theme.ink.toArgb())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.card.copy(alpha = 0.88f).toArgb())
                setStroke(dp(2), theme.overRev.toArgb())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        target.alpha = 0f
        runCatching { wm?.addView(target, lp) }
            .onFailure { EventLog.add("overlay", "compose hide target add failed: ${it.message}") }
        target.animate().alpha(1f).setDuration(120).start()
        hideTarget = target
        EventLog.add("overlay", "compose hide target shown")
    }

    private fun highlightHideTarget(over: Boolean) {
        hideTarget?.animate()?.scaleX(if (over) 1.18f else 1f)?.scaleY(if (over) 1.18f else 1f)
            ?.setDuration(90)?.start()
    }

    private fun removeHideTarget() {
        val target = hideTarget ?: return
        hideTarget = null
        hideTargetBounds.setEmpty()
        runCatching { wm?.removeView(target) }
    }

    private fun bubbleCenter(lp: WindowManager.LayoutParams): Pair<Int, Int> {
        val width = if (lp.width > 1) lp.width else dp(BUBBLE_DP)
        val height = if (lp.height > 1) lp.height else dp(BUBBLE_DP)
        return (lp.x + width / 2) to (lp.y + height / 2)
    }

    private fun isOverHideTarget(lp: WindowManager.LayoutParams): Boolean {
        if (hideTarget == null) return false
        val (cx, cy) = bubbleCenter(lp)
        return hideTargetBounds.contains(cx, cy)
    }

    /** Removes every window and remembers that the bubble stays off. */
    private fun hideBubbleByUser() {
        onUserDismiss?.invoke()
        EventLog.add("overlay", "compose bubble hidden by long-press drop")
        removeHideTarget()
        removeCard(reattachBubble = false)
        removeBorder()
        setBubbleAttached(false)
        composeView = null
        owner?.destroy()
        owner = null
        params = null
        shown = false
        panel.reset()
    }

    private fun unitLabel(): String =
        if (settings.units == Units.IMPERIAL) "mph" else "km/h"

    private fun initialiseAudio(): AudioCue =
        audio ?: AudioCue(context).also { audio = it }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * Height of the navigation bar in pixels, 0 when the framework does not
     * expose one (gesture navigation, or the dimen is missing).
     *
     * The overlay has no Activity, so there is no insets controller to ask; the
     * framework dimen is the only handle. On a 3-button device the bar is large
     * enough that the card's action row would otherwise be unreachable.
     */
    private fun navBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id == 0) return 0
        return runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0)
    }

    /**
     * Display height minus the system bars, in pixels: the area a full-height
     * overlay window can occupy without covering the clock or the navigation
     * handle. Measured through [WindowManager.currentWindowMetrics] so it follows
     * rotation and cutouts; the dimen fallback is for API 29 and below.
     */
    private fun availableHeightPx(): Int {
        val manager = wm ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = manager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val height = metrics.bounds.height() - insets.top - insets.bottom
                if (height > 0) return height.coerceAtLeast(dp(240))
            }.onFailure {
                EventLog.add("overlay", "compose: card metrics failed: ${it.message}")
            }
        }
        val bar = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (bar == 0) {
            0
        } else {
            runCatching { context.resources.getDimensionPixelSize(bar) }.getOrDefault(0)
        }
        val display = context.resources.displayMetrics.heightPixels
        return (display - statusBar - navBarHeight()).coerceAtLeast(dp(240))
    }

    // --- Compose content -----------------------------------------------------

    @Composable
    private fun ScrimContent(onOutsideTap: () -> Unit) {
        // Touchable and lightly dimmed: a tap here means "give me the app back",
        // so the panel closes and the bubble returns. The card is a separate
        // window added after this one, so taps on the card never reach the scrim.
        //
        // The dim is what makes the panel read as the thing in front. Without it
        // the card floated over a bright app (the launcher made this obvious)
        // and looked like a rendering accident rather than a modal. The light
        // theme dims less: 38 % black over a white app reads as a broken screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalTokens.current.scrim)
                .pointerInput(onOutsideTap) {
                    detectTapGestures { onOutsideTap() }
                }
        )
    }

    @Composable
    private fun BubbleContent(state: PanelUi) {
        val overRev = !state.linkLost && !state.canSilent && state.cue == Cue.OVER_REV
        val ring = ringColor(state)
        // Dashed means "this window is not showing live data": a quiet bus and a
        // dead link look the same to a driver at a glance, so both are dashed and
        // the sub-label carries the difference.
        val dashed = state.canSilent || state.linkLost
        val gear = state.gearShown
        // Precedence is fixed: a broken surface outranks a gear estimate, because
        // a digit under "NO CAN" would claim data the bubble does not have.
        val status = when {
            state.linkLost -> "NO LINK"
            state.canSilent -> "NO CAN"
            else -> null
        }
        // Stroke follows the state: the quieter the state the thinner the line,
        // and over-rev is the only one thick enough to read at a glance.
        val strokeWidthDp = ringStrokeDp(state)

        // Over-rev is the one cue that has to be unmissable; every other state
        // is a steady ring so it does not compete with the digits.
        val pulse by rememberInfiniteTransition(label = "over-rev").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(PULSE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ring-alpha",
        )

        val tokens = LocalTokens.current
        Box(
            modifier = Modifier
                .size(BUBBLE_DP.dp)
                .drawBehind {
                    // Inset from the actual stroke so the outer edge of the ring
                    // lands on the bubble edge instead of being clipped by it.
                    val stroke = strokeWidthDp.dp.toPx()
                    val inset = stroke / 2f
                    val radius = CornerRadius(CORNER_DP.dp.toPx())
                    drawRoundRect(color = tokens.raised, cornerRadius = radius)
                    // On white, a white bubble over a white app would lose its
                    // edge; the light theme adds a hairline. The ring still
                    // carries the state.
                    if (!tokens.dark) {
                        drawRoundRect(
                            color = tokens.border,
                            cornerRadius = radius,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                    drawRoundRect(
                        color = ring.copy(alpha = if (overRev) pulse else 1f),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius((CORNER_DP.dp.toPx() - inset).coerceAtLeast(0f)),
                        style = Stroke(
                            width = stroke,
                            pathEffect = if (dashed) {
                                PathEffect.dashPathEffect(
                                    floatArrayOf(5.dp.toPx(), 3.dp.toPx()),
                                )
                            } else {
                                null
                            },
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = rpmLabel(state.rpm),
                    style = RPM_STYLE,
                )
                // The 18 dp slot is always laid out, even when it is empty: a
                // slot that comes and goes would move the rpm number, and the
                // bubble sits in the driver's peripheral vision.
                Box(
                    modifier = Modifier.height(SUB_LABEL_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        status != null -> BasicText(
                            text = status,
                            style = STATUS_STYLE.copy(color = ring),
                        )

                        gear != null -> BasicText(
                            text = gear.toString(),
                            style = GEAR_STYLE,
                        )

                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * The navigation HUD: a card on the left edge while a navigation app is in
     * front. Same data as the panel, laid out small enough that the maneuver
     * card, the route and the ETA bar all stay visible - the reason the HUD
     * exists instead of the modal panel.
     *
     * The values are the panel's own snapshots ([PanelUi]); this composable
     * only decides where they go. The cue rail is the panel's [CueRail], so the
     * chevrons, the over-rev pulse and the "no cue" hairline cannot drift apart
     * between the two surfaces.
     */
    @Composable
    private fun HudContent(state: PanelUi) {
        val tokens = LocalTokens.current
        val imperial = settings.units == Units.IMPERIAL
        val fraction = Throttle.normalized(
            state.thr1,
            settings.throttleIdleRaw,
            settings.throttleFullRaw,
        )?.toFloat()?.coerceIn(0f, 1f)
        val offline = state.linkLost || state.canSilent
        val cue = if (offline) null else state.cue

        val labelStyle = LABEL_STYLE.copy(fontSize = 8.sp, letterSpacing = 0.6.sp)
        val smallLabel = LABEL_STYLE.copy(fontSize = 7.sp, letterSpacing = 0.5.sp)
        val valueStyle = VALUE_STYLE.copy(fontSize = 14.sp)

        Box(
            modifier = Modifier
                .size(HUD_W_DP.dp, HUD_H_DP.dp)
                .drawBehind {
                    val radius = CornerRadius(HUD_CORNER_DP.dp.toPx())
                    drawRoundRect(color = tokens.raised, cornerRadius = radius)
                    // The HUD floats over a live map, so it carries its edge in
                    // both palettes: a map is never reliably light or dark.
                    drawRoundRect(
                        color = tokens.border,
                        cornerRadius = radius,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, top = 8.dp, end = 5.dp, bottom = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BasicText(text = "SPEED", style = labelStyle)
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BasicText(
                            text = speedLabel(state.speedKph, imperial),
                            style = SPEED_STYLE_PORT.copy(fontSize = 36.sp),
                        )
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BasicText(text = if (imperial) "MPH" else "KM/H", style = smallLabel)
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            BasicText(text = "RPM", style = smallLabel)
                            BasicText(
                                text = state.rpm?.let { String.format("%.0f", it) } ?: "--",
                                style = valueStyle,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            BasicText(text = "GEAR", style = smallLabel)
                            // Same rule as the panel: unknown is a dash, never
                            // a "G" that reads as a gear.
                            BasicText(text = state.gearShown?.toString() ?: "–", style = valueStyle)
                        }
                    }
                    // The throttle sits on the bottom edge of the card, so the
                    // gap between the rows stays fixed while the numbers change.
                    Spacer(Modifier.weight(1f))
                    BasicText(text = "THROTTLE", style = smallLabel)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, end = 6.dp)
                            .height(6.dp)
                            .drawBehind {
                                val bar = CornerRadius(3.dp.toPx())
                                drawRoundRect(color = tokens.track, cornerRadius = bar)
                                if (fraction != null && fraction > 0f) {
                                    drawRoundRect(
                                        color = tokens.up,
                                        size = Size(size.width * fraction, size.height),
                                        cornerRadius = bar,
                                    )
                                }
                            },
                    )
                }
                Spacer(Modifier.width(4.dp))
                CueRail(cue = cue, fresh = state.cueFresh, widthDp = HUD_RAIL_DP)
            }
        }
    }

    @Composable
    private fun ringColor(state: PanelUi): Color = when {
        state.linkLost -> COLOR_LINK_LOST
        state.canSilent -> COLOR_NO_CAN
        state.cue == Cue.UP -> COLOR_UP
        state.cue == Cue.DOWN -> COLOR_DOWN
        state.cue == Cue.OVER_REV -> COLOR_OVER_REV
        else -> COLOR_IDLE
    }
    /**
     * Ring width per state, in dp. Idle and both failure states get a hairline so
     * the bubble stays quiet; the shift cues get enough weight to be seen
     * without looking, and over-rev gets the thick line on top of its pulse.
     */
    private fun ringStrokeDp(state: PanelUi): Float = when {
        state.linkLost -> 1.5f
        state.canSilent -> 1.5f
        state.cue == Cue.UP -> 2.5f
        state.cue == Cue.DOWN -> 2.5f
        state.cue == Cue.OVER_REV -> 4.0f
        else -> 1.5f
    }

    /**
     * "3.3k", not "3313": four digits crowd a 72 dp bubble. Tabular numerals
     * keep the width steady at 4 Hz; the suffix is half-size so it keeps the
     * baseline. Below 1000 rpm there is no suffix.
     */
    private fun rpmLabel(rpm: Float?) = buildAnnotatedString {
        if (rpm == null) {
            append("--")
            return@buildAnnotatedString
        }
        if (rpm < 1000f) {
            append(String.format("%.0f", rpm))
        } else {
            append(String.format("%.1f", rpm / 1000f))
            withStyle(SpanStyle(fontSize = 9.5.sp)) { append("k") }
        }
    }



    companion object {
        private const val BUBBLE_DP = 72
        private const val CORNER_DP = 22
        private const val SUB_LABEL_DP = 18

        /**
         * The navigation HUD: the approved 2026-11-15 geometry. One card, no
         * scrim, left edge. 132x146 leaves room for three digits of speed, the
         * rpm/gear row and the throttle bar without covering the maneuver card
         * or the ETA bar.
         */
        private const val HUD_W_DP = 132
        private const val HUD_H_DP = 146
        private const val HUD_CORNER_DP = 18
        private const val HUD_RAIL_DP = 20
        /** Landscape rail width as a fraction of the panel: the design's 15-20 %. */
        private const val RAIL_LAND_FRACTION = 0.18f

        /** Portrait rail: a physical rail, not 16 % of a nearly full-width sheet. */
        private const val RAIL_PORTRAIT_DP = 24

        /** Same 10 s the service uses before it clears the live values. */
        private const val STREAM_LOST_MS = 10_000L
        private const val LONG_PRESS_MS = 450L
        private const val FADE_MS = 150L
        private const val HIDE_MAX_SPEED_KPH = 5f
        private const val DRAG_SLOP_DP = 10

        /**
         * Gap between the card and the bottom edge, before the navigation bar is
         * added. Kept as a named value because the inset is added to it, not
         * instead of it.
         */
        private const val CARD_BOTTOM_DP = 18

        /**
         * Gap kept between the full-height landscape panel and the available
         * area at the top and bottom. At exactly the available height the bottom
         * border and its 20 dp corners landed on the last pixel row and were
         * invisible on the device.
         */
        private const val CARD_MARGIN_DP = 6

    }
}
