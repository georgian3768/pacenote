package com.pocketmechanic.spike

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

/**
 * The one place a colour is written down.
 *
 * Before this file every surface kept its own literals: the Compose panel, the
 * bubble, the legacy overlay, the WebView CSS and six activities. A light theme
 * built that way would drift within a week, so all of them now read a semantic
 * token set. The names say what the colour is for, never what it looks like:
 * `page`, `ink`, `up`, `overRev`.
 *
 * Light is **not** an inversion. The cue colours are darkened until they clear
 * 4.5:1 on white; the raw dark colours do not (`#2EC27E` on white is ~2.2:1).
 * The scrim stays black in both themes because its job is to dim whatever is
 * behind the panel, and a white scrim over a white app is invisible.
 *
 * The theme follows the system. There is deliberately no in-app switch: the
 * phone already has one, and two sources of truth would disagree.
 */
@Immutable
data class Tokens(
    val dark: Boolean,
    /** Screen and page background. */
    val page: Color,
    /** Cards, menu rows, the telemetry panel. */
    val card: Color,
    /** Bubble fill and nested blocks. */
    val raised: Color,
    /** 1 dp separators and card edges. */
    val border: Color,
    /** Primary text and the large readouts. */
    val ink: Color,
    /** Small uppercase labels. */
    val label: Color,
    /** Secondary numeric values and status lines. */
    val labelDim: Color,
    /** Descriptions and secondary text. */
    val dim: Color,
    /** Tertiary text that still has to clear 4.5:1. */
    val faint: Color,
    /** Idle ring and quiet state. */
    val idle: Color,
    /** NO LINK. */
    val linkLost: Color,
    /** Shift up, eco, the throttle fill. */
    val up: Color,
    /** Shift down and warnings. */
    val down: Color,
    /** Over-rev and the recording dot. */
    val overRev: Color,
    /** Dashed NO CAN ring. */
    val noCan: Color,
    /** Calibration "saved" state and the cold side of the temperature trace. */
    val info: Color,
    /** Empty cue rail. */
    val railIdle: Color,
    /** Throttle bar track. */
    val track: Color,
    /** Calibration ticks: a graphic mark, not text. */
    val tick: Color,
    /** Action-row text inside the panel. */
    val action: Color,
    /** Modal dim behind the panel. */
    val scrim: Color,
) {
    /** The value a Canvas or a Paint wants. */
    fun argb(color: Color): Int = color.toArgb()
}

val DarkTokens = Tokens(
    dark = true,
    page = Color(0xFF0A0C10),
    card = Color(0xFF0A0C10),
    raised = Color(0xFF0C0E12),
    border = Color(0xFF2C3442),
    ink = Color(0xFFE9EDF5),
    label = Color(0xFF8C96A5),
    // Slightly below `dim`: the dark theme already used #788291 for the
    // numeric secondary row and it reads better than a second #8892A6.
    labelDim = Color(0xFF788291),
    dim = Color(0xFF8892A6),
    faint = Color(0xFF5E6878),
    idle = Color(0xFF6E7887),
    linkLost = Color(0xFF8892A6),
    up = Color(0xFF2EC27E),
    down = Color(0xFFF5A524),
    overRev = Color(0xFFE5484D),
    noCan = Color(0xFF2DD4BF),
    info = Color(0xFF78BEE6),
    railIdle = Color(0xFF3A4250),
    track = Color(0xFF1F2733),
    tick = Color(0xFF6B7686),
    action = Color(0xFFC8D0DC),
    scrim = Color(0x61000000),
)

/**
 * Light palette. Contrast on the white card is in the comments next to the
 * tokens that carry text; the values are the approved 2026-11-13 set.
 */
val LightTokens = Tokens(
    dark = false,
    page = Color(0xFFF2F4F7),
    card = Color(0xFFFFFFFF),
    raised = Color(0xFFFFFFFF),
    border = Color(0xFFD6DCE6),
    ink = Color(0xFF131820),          // 17.8:1
    label = Color(0xFF5B6676),        // 5.8:1
    labelDim = Color(0xFF5B6676),     // 5.8:1
    dim = Color(0xFF4C5868),          // 7.2:1
    faint = Color(0xFF66717F),        // 5.0:1
    idle = Color(0xFF6B7787),         // 4.6:1
    linkLost = Color(0xFF4C5868),
    up = Color(0xFF0E7A4A),           // 5.4:1
    down = Color(0xFFB45309),         // 5.0:1
    overRev = Color(0xFFC62828),      // 5.6:1
    noCan = Color(0xFF0F766E),        // 5.5:1
    info = Color(0xFF0B5E8A),         // 6.4:1
    railIdle = Color(0xFFC2CAD6),
    track = Color(0xFFE4E8EF),
    tick = Color(0xFF8793A3),
    action = Color(0xFF131820),
    // 28 %, not 38 %: a white app dimmed by a heavy black scrim looks broken,
    // not modal.
    scrim = Color(0x47000000),
)

/** Compose surfaces read this; the service supplies the explicit value. */
val LocalTokens = staticCompositionLocalOf { DarkTokens }

fun tokensFor(dark: Boolean): Tokens = if (dark) DarkTokens else LightTokens

/** True when the phone is in dark mode right now. */
fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/** View code has no composition local; resolve from the activity's config. */
fun Context.themeTokens(): Tokens = tokensFor(isNightMode())

/**
 * Status and navigation bar background plus icons.
 *
 * The XML theme carries the same colours for the first frame, but it is a
 * static resource and some OEM builds ignore it for windows that draw their own
 * bar backgrounds; setting it here makes the bar follow the tokens no matter
 * which activity is on top. Without the icon flag the bar keeps light icons in
 * the light theme and they vanish against the pale background.
 */
fun Activity.applySystemBarAppearance(tokens: Tokens) {
    @Suppress("DEPRECATION")
    window.statusBarColor = tokens.page.toArgb()
    @Suppress("DEPRECATION")
    window.navigationBarColor = tokens.page.toArgb()
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !tokens.dark
        isAppearanceLightNavigationBars = !tokens.dark
    }
}
