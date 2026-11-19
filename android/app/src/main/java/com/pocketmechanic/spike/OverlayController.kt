package com.pocketmechanic.spike

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.pocketmechanic.shift.Cue
import com.pocketmechanic.shift.Output
import kotlin.math.abs

/**
 * Driving overlays, kept deliberately small.
 *
 * Design decision (2026-10-31, after the docked panel was rejected for covering
 * the bottom of Maps and YouTube Music):
 *  - one always-visible **rounded-square bubble** with rpm + a coloured arrow
 *    (green up, red down, amber idle). A squircle fits "arrow + number" far
 *    better than a circle without looking crude;
 *  - a **card** that only appears on tap and hides itself after a few seconds:
 *    a bottom sheet in portrait, a right-side column in landscape, so the part
 *    of the screen a map or a player actually uses stays clear;
 *  - a click-through **border flash** and a short **beep** for cues, because
 *    those are the only signals that survive a fully occupied screen.
 *
 * All WindowManager work happens on the main thread.
 */
class OverlayController(private val context: Context) : TelemetryOverlay {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val settings by lazy { AppSettings(context) }
    private val audio by lazy { AudioCue(context) }

    /**
     * Re-resolved on every configuration change: the legacy overlay is a View
     * tree, so unlike the Compose one it has to repaint itself when the system
     * switches light/dark.
     */
    private var tokens = context.themeTokens()

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var card: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var border: OverlayBorderView? = null
    /** "Drop here to hide" target, shown by long-pressing the bubble. */
    private var hideTarget: View? = null
    private val hideTargetBounds = Rect()

    // Bubble content. The cue is carried by the ring colour alone (user
    // decision 2026-10-31: no arrow glyph, the number gets the space).
    private var bubbleRpm: TextView? = null
    private var bubbleRing: GradientDrawable? = null

    // Card content
    private var speedText: TextView? = null
    private var rpmText: TextView? = null
    private var gearText: TextView? = null
    private var cueText: TextView? = null
    private var statusText: TextView? = null

    private var shown = false
    private var cardVisible = false
    private var lastCue: Cue? = null

    /**
     * No frames for a while: stop showing the last values as if the car were
     * still reporting. The bubble keeps its place but drops to "--" and a neutral
     * ring; the card (if open) shows "--" instead of a frozen rpm/gear.
     */
    override fun onStreamLost(silentMs: Long) {
        main.post {
            if (!shown) return@post
            bubbleRpm?.text = "--"
            bubbleRing?.setStroke(dp(3), tokens.idle.toArgb())
            pendingCue = null
            previewCue = null
            pendingFromGear = null
            if (cardVisible) {
                speedText?.text = "--"
                rpmText?.text = "--"
                gearText?.text = "–"
                cueText?.text = ""
                statusText?.text = "no data for ${silentMs / 1000} s"
            }
            EventLog.add("overlay", "stream lost for ${silentMs / 1000}s - values cleared")
        }
    }

    /**
     * Text refresh throttle. The stream arrives every 20-50 ms and rpm moves
     * hundreds of rpm per second, so repainting on every frame makes the digits
     * flicker unreadably (measured 2026-10-31: the decimal changed every ~250 ms
     * on the synthetic ramp). Numbers refresh at 4 Hz; the ring colour, which is
     * the actual cue, still updates immediately.
     */
    private var lastTextMs = 0L

    /**
     * Dev preview override. The live stream repaints the bubble every ~30 ms, so
     * a one-shot repaint is erased before it can be photographed (measured
     * 2026-10-31: green ring vanished within one frame).
     */
    private var previewCue: Cue? = null
    private var previewUntilMs = 0L

    /**
     * A cue stays on screen until the gear actually changes. "Shift up" is only
     * useful while the driver is still in the lower gear, so it must not vanish
     * after a fixed delay (user requirement, 2026-10-31).
     */
    private var pendingCue: Cue? = null
    private var pendingFromGear: Int? = null
    private var pendingSinceMs = 0L

    private val autoHide = Runnable { removeCard() }

    override var onUserDismiss: (() -> Unit)? = null

    override fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    override fun isShown(): Boolean = shown

    override fun show() {
        main.post {
            if (shown) return@post
            if (!canDraw()) {
                EventLog.add("overlay", "not shown: overlay permission missing")
                return@post
            }
            try {
                removeHideTarget()
                addBorder()
                addBubble()
                shown = true
                EventLog.add("overlay", "bubble + border added")
            } catch (t: Throwable) {
                EventLog.add("overlay", "add failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    override fun hide() {
        main.post {
            removeHideTarget()
            removeCard()
            bubble?.let { runCatching { wm?.removeView(it) } }
            border?.let { runCatching { wm?.removeView(it) } }
            bubble = null
            bubbleParams = null
            border = null
            shown = false
            audio.release()
            EventLog.add("overlay", "overlays removed")
        }
    }

    /** Orientation changed: the card is a bottom sheet or a side column. */
    override fun onOrientationChanged() {
        main.post {
            // A configuration change is also how a system light/dark switch
            // arrives at a service window.
            tokens = context.themeTokens()
            bubbleRing?.setColor(tokens.raised.toArgb())
            bubbleRpm?.setTextColor(tokens.ink.toArgb())
            reclampBubble()
            if (cardVisible) {
                // The card is built from tokens, so rebuilding is the repaint.
                removeCard()
                addCard()
            }
        }
    }

    override fun onKeepScreenOnChanged() {
        main.post {
            val flag = keepScreenOnFlag()
            fun patch(lp: WindowManager.LayoutParams?) {
                if (lp == null) return
                lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()) or flag
            }
            bubbleParams?.let {
                patch(it)
                bubble?.let { v -> runCatching { wm?.updateViewLayout(v, it) } }
            }
            cardParams?.let {
                patch(it)
                card?.let { v -> runCatching { wm?.updateViewLayout(v, it) } }
            }
        }
    }

    /** FLAG_KEEP_SCREEN_ON when the setting is on, 0 otherwise. */
    private fun keepScreenOnFlag(): Int =
        if (settings.keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0

    override fun onFrame(frame: Telemetry, out: Output?) {
        val cue = out?.cue
        main.post {
            // Hidden by the long-press drop: the service keeps streaming, but
            // there is no view to paint until the dev screen turns it back on.
            if (!shown) return@post
            // --- bubble: always visible, one glance -------------------------
            val now = SystemClock.elapsedRealtime()
            val previewing = now < previewUntilMs
            val repaintText = now - lastTextMs >= TEXT_REFRESH_MS
            if (repaintText) {
                lastTextMs = now
                bubbleRpm?.text = rpmLabel(frame.rpm)
            }
            paintBubble(if (previewing) previewCue else (cue ?: pendingCue))

            // --- card: only while open --------------------------------------
            if (cardVisible && repaintText) {
                // Same correction as AssistantHost, so the card and the gear
                // estimate can never disagree about how fast the car is going.
                speedText?.text = com.pocketmechanic.shift.SpeedScale
                    .applyFloat(frame.speedKph, settings.gpsSpeedScale)
                    ?.let { String.format("%.0f", it) } ?: "--"
                rpmText?.text = frame.rpm?.let { String.format("%.0f", it) } ?: "--"
                gearText?.text = out?.gear?.toString() ?: "–"
                statusText?.text = if (frame.canActive) "CAN live" else "CAN silent"
            }

            // --- cue bookkeeping --------------------------------------------
            if (cue != null) {
                pendingCue = cue
                pendingFromGear = out?.gear
                pendingSinceMs = now
                if (settings.audioCues) audio.play(cue)
            } else pendingCue?.let { pending ->
                val from = pendingFromGear
                val gear = out?.gear
                // Any gear change resolves the cue, in either direction: a
                // pending "shift up" is moot if the driver downshifted instead,
                // and a pending "shift down" is moot if they upshifted (observed
                // 2026-10-31: a down cue was still showing after a 3->4 shift).
                val resolved = when (pending) {
                    Cue.UP, Cue.DOWN -> from != null && gear != null && gear != from
                    Cue.OVER_REV -> true
                }
                // Safety net: with no gear estimate (clutch, stopped) a pending
                // cue would otherwise stay on screen forever.
                if (resolved || now - pendingSinceMs > 15000) {
                    pendingCue = null
                    pendingFromGear = null
                }
            }

            if (cardVisible) {
                val shownCue = pendingCue
                cueText?.text = when (shownCue) {
                    Cue.UP -> "SHIFT UP"
                    Cue.DOWN -> "SHIFT DOWN"
                    Cue.OVER_REV -> "OVER-REV"
                    null -> ""
                }
                val base = when (shownCue) {
                    Cue.UP -> tokens.up.toArgb()
                    Cue.DOWN, Cue.OVER_REV -> tokens.overRev.toArgb()
                    null -> tokens.dim.toArgb()
                }
                // Collapse the cue line entirely when there is nothing to say,
                // instead of reserving a blank row inside a small card.
                cueText?.visibility = if (shownCue == null) View.GONE else View.VISIBLE
                val fresh = now - pendingSinceMs < 2500
                cueText?.setTextColor(if (fresh) base else (base and 0x00FFFFFF) or 0x66000000)
            }

            if (cue != lastCue) {
                lastCue = cue
                flash(cue)
            }
        }
    }

    /**
     * Dev action: render a cue straight onto the bubble, without the assistant.
     * The synthetic drive never reaches the up threshold, so this is the only
     * way to photograph the green arrow; the cue logic itself is unit tested in
     * the :shiftassist module.
     */
    override fun previewCue(cue: Cue?) {
        main.post {
            previewCue = cue
            previewUntilMs = if (cue == null) 0L else SystemClock.elapsedRealtime() + 5_000L
            paintBubble(cue)
        }
    }

    private fun paintBubble(state: Cue?) {
        val color = when (state) {
            Cue.UP -> tokens.up.toArgb()
            Cue.DOWN, Cue.OVER_REV -> tokens.overRev.toArgb()
            null -> tokens.idle.toArgb()
        }
        bubbleRing?.setStroke(dp(3), color)
    }

    /**
     * "3.3k", not "3313": four digits crowd a 72 dp bubble. The suffix is
     * scaled with RelativeSizeSpan so it keeps the baseline instead of needing a
     * second view, and below 1000 rpm there is no suffix at all.
     */
    private fun rpmLabel(rpm: Float?): CharSequence {
        if (rpm == null) return "--"
        if (rpm < 1000f) return String.format("%.0f", rpm)
        val text = String.format("%.1f", rpm / 1000f)
        return SpannableString(text + "k").apply {
            setSpan(RelativeSizeSpan(0.5f), text.length, text.length + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Dev action: play the over-rev tone once. */
    override fun testOverRevBeep() {
        main.post { audio.play(Cue.OVER_REV) }
    }

    /** Dev action: green then red, so both colours can be photographed. */
    override fun flashTest() {
        main.post {
            flash(Cue.UP)
            main.postDelayed({ flash(Cue.DOWN) }, 1200)
            main.postDelayed({ flash(null) }, 2400)
        }
    }

    private fun flash(cue: Cue?) {
        val view = border ?: return
        val color = when (cue) {
            Cue.UP -> tokens.up.toArgb()
            Cue.DOWN, Cue.OVER_REV -> tokens.overRev.toArgb()
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

    // --- bubble --------------------------------------------------------------

    /**
     * Rounded square, not a circle: the content is a glyph above a number, which
     * a circle clips or crowds. 72 dp with 22 dp corners keeps the "app icon"
     * shape and leaves room for four digits.
     */
    private fun addBubble() {
        val size = dp(72)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(tokens.raised.toArgb())
            setStroke(dp(3), tokens.idle.toArgb())
        }
        bubbleRing = ring

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ring
        }
        bubbleRpm = TextView(context).apply {
            text = "--"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tokens.ink.toArgb())
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        content.addView(bubbleRpm)

        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                keepScreenOnFlag(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(200)
        }
        bubbleParams = lp
        attachDrag(content, lp)
        runCatching { wm?.addView(content, lp) }
            .onFailure { EventLog.add("overlay", "bubble add failed: ${it.message}") }
        bubble = content
    }

    /**
     * Drag to move, tap to open/close the card.
     *
     * The window is created with FLAG_LAYOUT_NO_LIMITS, which lets it be dragged
     * past the screen edge and stay there (the bubble could be parked half
     * off-screen and was then unreachable). Every drag position is therefore
     * clamped to the usable area, and on release the bubble docks to the nearest
     * edge so it always sits flush against a border.
     */
    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        var longPressed = false

        // Long-press, then drag onto the ✕ at the bottom to hide the bubble.
        // Deliberate gesture rather than a menu: it has to work without looking
        // and without pausing Maps.
        val longPress = Runnable {
            if (!dragged && !longPressed) {
                longPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showHideTarget()
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    longPressed = false
                    main.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!longPressed && (abs(dx) > dp(10) || abs(dy) > dp(10))) {
                        main.removeCallbacks(longPress)
                        dragged = true
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    clampBubble(lp)
                    runCatching { wm?.updateViewLayout(view, lp) }
                    if (hideTarget != null) highlightHideTarget(isOverHideTarget(lp))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    when {
                        longPressed -> {
                            val hide = isOverHideTarget(lp)
                            removeHideTarget()
                            if (hide) {
                                hideBubbleByUser()
                            } else {
                                snapBubble(lp)
                                runCatching { wm?.updateViewLayout(view, lp) }
                            }
                        }
                        dragged -> {
                            snapBubble(lp)
                            runCatching { wm?.updateViewLayout(view, lp) }
                        }
                        else -> toggleCard()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    removeHideTarget()
                    true
                }
                else -> false
            }
        }
    }

    private fun bubbleCenter(lp: WindowManager.LayoutParams): Pair<Int, Int> {
        val size = if (lp.width > 1) lp.width else dp(72)
        return (lp.x + size / 2) to (lp.y + size / 2)
    }

    private fun isOverHideTarget(lp: WindowManager.LayoutParams): Boolean {
        if (hideTarget == null) return false
        val (cx, cy) = bubbleCenter(lp)
        return hideTargetBounds.contains(cx, cy)
    }

    private fun highlightHideTarget(over: Boolean) {
        hideTarget?.animate()?.scaleX(if (over) 1.18f else 1f)?.scaleY(if (over) 1.18f else 1f)
            ?.setDuration(90)?.start()
    }

    /**
     * Circular ✕ target at the bottom of the screen, only while the driver holds
     * the bubble. Shown as an overlay so it sits above whatever is open.
     */
    private fun showHideTarget() {
        if (hideTarget != null) return
        val size = dp(64)
        val area = bubbleArea()
        val x = area.centerX() - size / 2
        val y = (area.bottom - size - dp(28)).coerceAtLeast(area.top)
        hideTargetBounds.set(x, y, x + size, y + size)

        val target = TextView(context).apply {
            text = "✕"
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tokens.ink.toArgb())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tokens.card.copy(alpha = 0.88f).toArgb())
                setStroke(dp(2), tokens.overRev.toArgb())
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
            .onFailure { EventLog.add("overlay", "hide target add failed: ${it.message}") }
        target.animate().alpha(1f).setDuration(120).start()
        hideTarget = target
        EventLog.add("overlay", "hide target shown")
    }

    private fun removeHideTarget() {
        val target = hideTarget ?: return
        hideTarget = null
        hideTargetBounds.setEmpty()
        runCatching { wm?.removeView(target) }
    }

    /** Removes the bubble (and card/border) and remembers that it stays off. */
    private fun hideBubbleByUser() {
        onUserDismiss?.invoke()
        EventLog.add("overlay", "bubble hidden by long-press drop")
        removeCard()
        bubble?.let { runCatching { wm?.removeView(it) } }
        border?.let { runCatching { wm?.removeView(it) } }
        bubble = null
        bubbleParams = null
        border = null
        shown = false
    }

    /**
     * Usable area **in window-layout coordinates**.
     *
     * `lp.x/lp.y` of a TOP|START overlay are offsets into the parent frame, which
     * starts below the status bar (measured: lp.y=550 → frame top 634 on a
     * 1080x2400 phone). Returning the area already reduced by the insets keeps
     * the clamp, the snap and the drop-target hit test in the same coordinate
     * space as the layout params.
     */
    private fun bubbleArea(): Rect {
        val manager = wm ?: run {
            val m = context.resources.displayMetrics
            return Rect(0, 0, m.widthPixels, m.heightPixels)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = manager.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                return Rect(
                    0,
                    0,
                    (bounds.width() - insets.left - insets.right).coerceAtLeast(dp(72)),
                    (bounds.height() - insets.top - insets.bottom).coerceAtLeast(dp(72)),
                )
            }.onFailure { EventLog.add("overlay", "window metrics failed: ${it.message}") }
        }
        val m = context.resources.displayMetrics
        return Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    /** Keeps the whole bubble inside [bubbleArea]. */
    private fun clampBubble(lp: WindowManager.LayoutParams) {
        val size = if (lp.width > 1) lp.width else dp(72)
        val area = bubbleArea()
        lp.x = lp.x.coerceIn(area.left, (area.right - size).coerceAtLeast(area.left))
        lp.y = lp.y.coerceIn(area.top, (area.bottom - size).coerceAtLeast(area.top))
    }

    /** Docks to the nearest edge using the bubble's centre, then re-clamps. */
    private fun snapBubble(lp: WindowManager.LayoutParams) {
        val size = if (lp.width > 1) lp.width else dp(72)
        val area = bubbleArea()
        val centerX = lp.x + size / 2
        val centerY = lp.y + size / 2
        val toLeft = centerX - area.left
        val toRight = area.right - centerX
        val toTop = centerY - area.top
        val toBottom = area.bottom - centerY
        when (minOf(toLeft, toRight, toTop, toBottom)) {
            toLeft -> lp.x = area.left
            toRight -> lp.x = area.right - size
            toTop -> lp.y = area.top
            else -> lp.y = area.bottom - size
        }
        clampBubble(lp)
    }

    /** Rotation can make a previously valid position off-screen; clamp it back. */
    private fun reclampBubble() {
        val view = bubble ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        clampBubble(lp)
        runCatching { wm?.updateViewLayout(view, lp) }
    }

    // --- card ----------------------------------------------------------------

    private fun toggleCard() {
        if (cardVisible) removeCard() else addCard()
    }

    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun addCard() {
        val metrics = context.resources.displayMetrics
        val landscape = isLandscape()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(tokens.page.toArgb())
                setStroke(dp(1), tokens.border.toArgb())
            }
            setOnClickListener { scheduleAutoHide() }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speedCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("SPEED"))
            speedText = big("--", if (landscape) 34f else 40f).also { addView(it) }
        }
        val rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, 0)
            addView(label("RPM"))
            rpmText = big("--", 19f).also { addView(it) }
            addView(label("GEAR"))
            gearText = big("G", 19f).also { addView(it) }
        }
        row.addView(speedCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(rightCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        cueText = TextView(context).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tokens.dim.toArgb())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(cueText)

        // No spacer: both cards wrap their content. A full-height landscape
        // column and a 35 % tall portrait sheet were mostly empty space (measured
        // 2026-10-31), and empty space is map or player that the card hides.

        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(small("Maps") { OverlayLauncher.openMaps(context) })
                addView(small("Music") { OverlayLauncher.openMusic(context, settings) })
                addView(small("Calib") { OverlayLauncher.openCalibration(context) })
                addView(small("Hide") { removeCard() })
            }
        )
        statusText = TextView(context).apply {
            textSize = 10f
            setTextColor(tokens.faint.toArgb())
            text = "Pacenote"
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(statusText)

        // Portrait: bottom sheet (35 % tall). Landscape: right column (36 %
        // wide), because a bottom band would sit on the map's ETA sheet and the
        // player's transport controls.
        val lp = if (landscape) {
            WindowManager.LayoutParams(
                (metrics.widthPixels * 0.36f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or keepScreenOnFlag(),
                PixelFormat.TRANSLUCENT
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
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = dp(18)
            }
        }

        runCatching { wm?.addView(root, lp) }
            .onFailure { EventLog.add("overlay", "card add failed: ${it.message}") }
        card = root
        cardParams = lp
        cardVisible = true
        scheduleAutoHide()
        EventLog.add("overlay", "card shown (${if (landscape) "landscape/right" else "portrait/bottom"})")
    }

    /**
     * The card is a peek, not a dashboard: it hides itself so the app underneath
     * becomes fully usable again without the driver touching anything.
     */
    private fun scheduleAutoHide() {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun removeCard() {
        main.removeCallbacks(autoHide)
        card?.let { runCatching { wm?.removeView(it) } }
        card = null
        cardParams = null
        cardVisible = false
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
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm?.addView(view, lp) }
            .onFailure { EventLog.add("overlay", "border add failed: ${it.message}") }
        view.alpha = 0f
        border = view
    }

    // --- shortcuts -----------------------------------------------------------

    // --- helpers -------------------------------------------------------------

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 9f
        setTextColor(tokens.dim.toArgb())
        letterSpacing = 0.12f
    }

    private fun big(text: String, size: Float) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(tokens.ink.toArgb())
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun small(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(tokens.action.toArgb())
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        const val FADE_MS = 150L
        /** Hold the bubble this long to reveal the hide target. */
        const val LONG_PRESS_MS = 450L
        const val AUTO_HIDE_MS = 6_000L
        const val TEXT_REFRESH_MS = 250L
    }
}
