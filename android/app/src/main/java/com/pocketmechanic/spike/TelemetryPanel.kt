package com.pocketmechanic.spike

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketmechanic.shift.AssistantMode
import com.pocketmechanic.shift.Cue
import com.pocketmechanic.shift.Throttle

/**
 * The telemetry panel: the numbers, the throttle bar, the cue rail and the
 * action row.
 *
 * It was written inside [ComposeOverlay] as the modal card. The landing screen
 * shows the same panel pinned to the bottom of the app, so it lives here now and
 * both hosts render it: the overlay in its own window, the landing in a
 * `ComposeView` under the menu. One implementation means the two surfaces cannot
 * drift on a colour, a cue rule or the mode chip.
 *
 * The panel is pure UI over [PanelUi]; it reads no clock, no prefs and no
 * service. [PanelFeed] derives the state, the hosts decide where it goes.
 */

/**
 * One rendered snapshot. The ring/digits change on different clocks, which is
 * why the cue and the numbers are separate fields rather than one frame.
 */
data class PanelUi(
    val rpm: Float? = null,
    /** GPS-corrected km/h; the panel converts to the user's unit. */
    val speedKph: Float? = null,
    val gear: Int? = null,
    /**
     * The gear the panel may show: [gear] with the short hold from
     * the GearDisplay hold applied, so a clutch-in does not blink the digit off.
     */
    val gearShown: Int? = null,
    val cue: Cue? = null,
    /** True while the cue is fresh enough to be drawn at full opacity. */
    val cueFresh: Boolean = false,
    /**
     * A diagnostics cue preview is on screen. It is a screenshot path, so the
     * link watchdog must not suppress it: on the bench there is never a frame,
     * and the preview would otherwise be invisible.
     */
    val previewing: Boolean = false,
    val canSilent: Boolean = false,
    val linkLost: Boolean = false,
    val silentMs: Long = 0,
    /** Raw throttle channel 1 for the numeric readout. Not a percentage. */
    val thr1: Int? = null,
    val brake: Boolean? = null,
)

/**
 * The panel.
 *
 * @param fullHeight fill the host's height (overlay landscape). False wraps the
 *   content and hugs the bottom, which is the portrait sheet and the landing.
 * @param mode the selected shift mode; the chip cycles it through the host, so
 *   the panel never keeps a second copy that could disagree with the assistant.
 */
@Composable
fun TelemetryPanel(
    ui: PanelUi,
    /**
     * Raw throttle channel sampled on every frame, for the bar. Passed as a
     * lambda on purpose: reading it here would make the whole panel recompose at
     * the stream's ~10 Hz rate, which is exactly what the 4 Hz digit clock
     * exists to avoid.
     */
    barThr1: () -> Int?,
    trip: TripUi,
    landscape: Boolean,
    fullHeight: Boolean,
    availableHeightDp: Int,
    railWidthDp: Int,
    unit: String,
    // Calibration captured when the panel was built: it cannot change while the
    // panel is up, because the Calib action leaves the panel first.
    throttleIdleRaw: Int,
    throttleFullRaw: Int,
    mode: AssistantMode,
    onCycleMode: () -> Unit,
    onMaps: () -> Unit,
    onMusic: () -> Unit,
    onCalib: () -> Unit,
    onHide: () -> Unit,
) {

    val cue = ui.cue
    val cueColor = when (cue) {
        Cue.UP -> COLOR_UP
        Cue.DOWN -> COLOR_DOWN
        Cue.OVER_REV -> COLOR_OVER_REV
        null -> COLOR_LABEL
    }
    // Fixed failure vocabulary, the same two strings the bubble uses: NO CAN
    // (no frames on the bus) and NO LINK (no frames from the service).
    val status = when {
        ui.linkLost -> "NO LINK"
        ui.canSilent -> "NO CAN"
        else -> "CAN live"
    }
    // Appended to the footer status line rather than given a row of its own:
    // it is a garage number, not something to look at while driving.
    val service = trip.serviceRemainingKm?.let {
        "service in ${String.format("%.0f", it)} km"
    }

    val calibrated = throttleIdleRaw > 0 && throttleFullRaw > throttleIdleRaw
    val throttleFraction = if (calibrated) {
        // The shift module's own normalisation, so the bar and the cue logic
        // cannot disagree about what 40 % means.
        Throttle.normalized(ui.thr1, throttleIdleRaw, throttleFullRaw)?.toFloat()
    } else {
        // Uncalibrated: 0..255 is the channel's own range, not pedal travel.
        ui.thr1?.let { (it / 255f).coerceIn(0f, 1f) }
    }
    val throttleLabel = if (calibrated) "throttle" else "throttle (thr1 raw)"

    // The landscape panel is a cockpit column with room to spare, so the
    // telemetry scales up instead of leaving a blank middle: the same card
    // read at a glance from a windscreen mount, not a phone held at arm's
    // length. Portrait keeps the compact sizes because it hugs the bottom.
    val labelStyle = if (landscape) LABEL_STYLE_LAND else LABEL_STYLE
    val valueStyle = if (landscape) VALUE_STYLE_LAND else VALUE_STYLE
    // Fixed value slot so the third row of the grid (unit, mode chip, brake)
    // lines up even though the speed digit is nearly twice the rpm digit.
    // Without it the three columns drifted apart by one line height.
    val valueSlot = if (landscape) 56.dp else 50.dp
    val throttleValue = when {
        !calibrated -> ui.thr1?.toString() ?: "--"
        throttleFraction == null -> "--"
        else -> String.format("%.0f%%", throttleFraction * 100f)
    }

    // Same precedence as the bubble: a dead link or a quiet bus outranks
    // any cue, because a green arrow over frozen values is the most
    // dangerous thing this screen could show.
    val railCue = if (!ui.previewing && (ui.linkLost || ui.canSilent)) null else cue
    // Under ~280 dp the full-height column cannot spread its sections, so it
    // scrolls instead of clipping the action row off the bottom.
    val scrollable = fullHeight && availableHeightDp < 280

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (fullHeight) {
                    Modifier.fillMaxHeight()
                } else {
                    // Portrait sizes to content, so the rail has to follow the
                    // column's measured height: fillMaxHeight against a
                    // wrap-content Row would stretch it to the window instead.
                    Modifier.height(IntrinsicSize.Min)
                }
            )
            .clip(RoundedCornerShape(20.dp))
            .background(COLOR_CARD)
            .border(1.dp, COLOR_CARD_EDGE, RoundedCornerShape(20.dp)),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (fullHeight) Modifier.fillMaxHeight() else Modifier)
                .then(
                    if (scrollable) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trip.recording) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(COLOR_REC)
                    )
                }
                BasicText(
                    text = tripHeader(trip),
                    style = STATUS_STYLE.copy(
                        fontSize = 10.sp,
                        color = if (trip.recording) COLOR_LABEL else COLOR_LABEL_DIM,
                        textAlign = TextAlign.Start,
                    ),
                    modifier = Modifier.padding(start = if (trip.recording) 6.dp else 0.dp),
                )
            }

            // Equal flex above and below the telemetry cluster: the numbers,
            // the throttle bar and the cue sit in the optical centre of the
            // panel instead of hanging under the header, and the actions stay
            // pinned to the bottom edge. In the wrap-content portrait sheet a
            // weighted spacer measures to nothing, so the sheet is unchanged.
            Spacer(modifier = Modifier.weight(1f))

            // Six blocks in one group, three columns twice: speed over its
            // unit, rpm over the mode chip, gear over the brake state. Keeping
            // them adjacent is the point - they are one glance, and spreading
            // them across the panel was what left holes in the middle.
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(text = "SPEED", style = labelStyle)
                    Box(
                        modifier = Modifier.height(valueSlot),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = speedLabel(ui.speedKph, unit == "mph"),
                            style = if (landscape) SPEED_STYLE_LAND else SPEED_STYLE_PORT,
                        )
                    }
                    BasicText(text = unit.uppercase(), style = labelStyle)
                }
                Column(modifier = Modifier.weight(1.1f).padding(start = 12.dp)) {
                    BasicText(text = "RPM", style = labelStyle)
                    Box(
                        modifier = Modifier.height(valueSlot),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicText(
                            text = ui.rpm?.let { String.format("%.0f", it) } ?: "--",
                            style = valueStyle,
                        )
                    }
                    ModeChip(mode = mode, onCycle = onCycleMode)
                }
                Column(modifier = Modifier.weight(0.9f).padding(start = 12.dp)) {
                    BasicText(text = "GEAR", style = labelStyle)
                    Box(
                        modifier = Modifier.height(valueSlot),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // The panel reads the same held gear as the bubble;
                        // the raw estimate drops out at every clutch-in.
                        // Unknown is a dash, never a "G" that reads as a gear.
                        BasicText(text = ui.gearShown?.toString() ?: "–", style = valueStyle)
                    }
                    BasicText(
                        text = when (ui.brake) {
                            true -> "brake on"
                            false -> "brake off"
                            // The car did not say, which is not "not braking".
                            null -> "brake --"
                        },
                        style = STATUS_STYLE.copy(
                            fontSize = 9.sp,
                            color = if (ui.brake == true) COLOR_OVER_REV else COLOR_LABEL_DIM,
                        ),
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(text = throttleLabel, style = labelStyle)
                    BasicText(
                        text = throttleValue,
                        style = labelStyle.copy(color = COLOR_LABEL_DIM),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                ThrottleBar(
                    raw = barThr1,
                    calibrated = calibrated,
                    idleRaw = throttleIdleRaw,
                    fullRaw = throttleFullRaw,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .height(7.dp),
                )
        }

            if (cue != null) {
                BasicText(
                    text = when (cue) {
                        Cue.UP -> "SHIFT UP"
                        Cue.DOWN -> "SHIFT DOWN"
                        Cue.OVER_REV -> "OVER-REV"
                    },
                    style = CUE_STYLE.copy(
                        color = if (ui.cueFresh) cueColor else cueColor.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CardAction("Maps", onMaps)
                CardAction("Music", onMusic)
                CardAction("Calib", onCalib)
                CardAction("Hide", onHide)
            }

            BasicText(
                text = "${modeLabel(mode)} · $unit · $status",
                style = STATUS_STYLE.copy(fontSize = 10.sp, color = COLOR_LABEL_DIM),
                modifier = Modifier.padding(top = 4.dp),
            )
            // Service is a garage number, not a live one, so it gets its own
            // line: sharing the status line wrapped "486" away from "km".
            service?.let {
                BasicText(
                    text = it,
                    style = STATUS_STYLE.copy(fontSize = 10.sp, color = COLOR_LABEL_DIM),
                )
            }

        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(COLOR_CARD_EDGE)
        )

        CueRail(cue = railCue, fresh = ui.cueFresh, widthDp = railWidthDp)
    }
}

/**
 * Trip header: "01:23 · 4.6 km · ~78" while recording, "not recording"
 * otherwise.
 *
 * Distance stays in km. Speed is converted in [speedLabel] because km/h to
 * mph is a one-line factor for a number that is displayed every second; there
 * is no distance converter to reuse here, and inventing one would let the
 * card disagree with the saved trip row on the same screen.
 *
 * The eco score is a live estimate (see [TripUi]): it is normalised per moving
 * second, so it swings early in a trip and is not the saved value. The "~"
 * says exactly that.
 */
private fun tripHeader(trip: TripUi): String {
    if (!trip.recording) return "not recording"
    val parts = mutableListOf(
        elapsedLabel(trip.elapsedMs),
        String.format("%.1f km", trip.distanceKm),
    )
    trip.ecoScore?.let { parts.add("~$it") }
    return parts.joinToString(" · ")
}

/** mm:ss, promoted to h:mm:ss past an hour so the line never grows a field. */
private fun elapsedLabel(elapsedMs: Long): String {
    val total = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

/**
 * The shift-assist rail: the full-height block on the panel's right edge.
 *
 * Shape carries the state as well as colour, so a driver who cannot separate
 * red from green still reads the cue: a single chevron up, a single chevron
 * down, a double chevron for over-rev, and a thin dim line when there is
 * nothing to say. Everything is drawn, so there are no icon assets and no
 * vector dependency.
 *
 * The wash and the chevrons sit inside a small inset so they never collide
 * with the card's 20 dp corners.
 */
@Composable
internal fun CueRail(cue: Cue?, fresh: Boolean, widthDp: Int) {
    // Over-rev is the one state that pulses: the visual equivalent of the
    // beep, and the reason this rail exists rather than a line of text.
    val pulse by rememberInfiniteTransition(label = "cue-rail").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rail-alpha",
    )
    // A cue older than CUE_FRESH_MS dims exactly like its text does: the
    // advice has expired, the evidence that it fired has not.
    val fade = if (fresh) 1f else 0.4f
    val tint = when (cue) {
        Cue.UP -> COLOR_UP
        Cue.DOWN -> COLOR_DOWN
        Cue.OVER_REV -> COLOR_OVER_REV
        null -> COLOR_RAIL_IDLE
    }

    Box(
        modifier = Modifier
            .width(widthDp.dp)
            // Fills the row: the fixed height in landscape, the measured
            // content height in the portrait sheet.
            .fillMaxHeight()
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .drawBehind {
                val alive = if (cue == Cue.OVER_REV) pulse else 1f
                val wash = when (cue) {
                    null -> 0f
                    Cue.OVER_REV -> 0.32f
                    else -> 0.22f
                }
                if (wash > 0f) {
                    drawRoundRect(
                        color = tint.copy(alpha = wash * fade * alive),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                }

                val cx = size.width / 2f
                val cy = size.height / 2f
                if (cue == null) {
                    // Nothing to say, but the rail keeps its place so the
                    // panel's width does not jump the moment a cue appears.
                    drawLine(
                        color = tint.copy(alpha = 0.6f * fade),
                        start = Offset(cx, size.height * 0.30f),
                        end = Offset(cx, size.height * 0.70f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    return@drawBehind
                }

                val ink = tint.copy(alpha = fade * alive)
                val stroke = (if (cue == Cue.OVER_REV) 4.dp else 3.dp).toPx()
                val armX = (size.width * 0.30f).coerceAtMost(10.dp.toPx())
                val armY = 7.dp.toPx()
                val up = cue != Cue.DOWN

                fun chevron(centerY: Float) {
                    val apex = centerY + if (up) -armY else armY
                    val base = centerY + if (up) armY else -armY
                    drawLine(
                        color = ink,
                        start = Offset(cx - armX, base),
                        end = Offset(cx, apex),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = ink,
                        start = Offset(cx + armX, base),
                        end = Offset(cx, apex),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }

                if (cue == Cue.OVER_REV) {
                    // Two chevrons: the same direction, twice the weight.
                    chevron(cy - armY)
                    chevron(cy + armY)
                } else {
                    chevron(cy)
                }
            }
    )
}

/**
 * ECO / NORMAL / ZOOM chip: the panel's only control besides the action row,
 * and the only way to change the shift profile without opening the menu.
 *
 * It is a real button, not a label: the whole chip is tappable, it carries a
 * border so it reads as one, and the colour matches the profile (green for
 * mileage, amber for the highway shift points, quiet for normal and shadow).
 */
@Composable
private fun ModeChip(mode: AssistantMode, onCycle: () -> Unit) {
    val tint = when (mode) {
        AssistantMode.ECO -> COLOR_UP
        AssistantMode.SPORT -> COLOR_DOWN
        AssistantMode.NORMAL -> COLOR_LABEL
        // SHADOW is a measurement mode: it must look inactive, not like a
        // fourth driving profile.
        AssistantMode.SHADOW -> COLOR_LABEL_DIM
    }
    BasicText(
        text = modeLabel(mode).uppercase(),
        style = STATUS_STYLE.copy(fontSize = 9.sp, color = tint),
        modifier = Modifier
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
            .clickable { onCycle() }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * One spelling for the drive mode in the chip and the footer. SPORT reads as
 * "zoom" because that is the highway profile the chip selects; its shift
 * points are SPORT's own, which is why nothing new is invented here. SHADOW is
 * named as such so a dev cannot mistake it for NORMAL.
 */
private fun modeLabel(mode: AssistantMode): String = when (mode) {
    AssistantMode.ECO -> "eco"
    AssistantMode.SPORT -> "zoom"
    AssistantMode.NORMAL -> "normal"
    AssistantMode.SHADOW -> "shadow"
}

@Composable
private fun CardAction(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = ACTION_STYLE,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

internal fun speedLabel(kph: Float?, imperial: Boolean): String {
    if (kph == null) return "--"
    val value = if (imperial) kph * 0.621371f else kph
    return String.format("%.0f", value)
}

// --- palette and type ------------------------------------------------------
//
// Shared with the bubble in ComposeOverlay: the bubble is the same instrument at
// a smaller size, so a second copy of these values would let the two surfaces
// disagree about what "up" means.
//
// The values now live in Theme.kt. These are composable getters rather than
// plain vals so every existing call site resolves the active theme without
// carrying a `tokens` argument through the whole panel; anything that reads
// them from a draw lambda has to hoist the value first (a draw scope is not a
// composition).

internal val COLOR_BG: Color
    @Composable get() = LocalTokens.current.raised
internal val COLOR_UP: Color
    @Composable get() = LocalTokens.current.up
internal val COLOR_DOWN: Color
    @Composable get() = LocalTokens.current.down
internal val COLOR_OVER_REV: Color
    @Composable get() = LocalTokens.current.overRev
internal val COLOR_NO_CAN: Color
    @Composable get() = LocalTokens.current.noCan
internal val COLOR_LINK_LOST: Color
    @Composable get() = LocalTokens.current.linkLost
internal val COLOR_IDLE: Color
    @Composable get() = LocalTokens.current.idle

/** Rail with no cue: present, quiet, and not a cue colour. */
internal val COLOR_RAIL_IDLE: Color
    @Composable get() = LocalTokens.current.railIdle
internal val COLOR_CARD: Color
    @Composable get() = LocalTokens.current.card
internal val COLOR_CARD_EDGE: Color
    @Composable get() = LocalTokens.current.border
internal val COLOR_LABEL: Color
    @Composable get() = LocalTokens.current.label
internal val COLOR_LABEL_DIM: Color
    @Composable get() = LocalTokens.current.labelDim

/** Recording dot in the trip header: the one red thing that is not a cue. */
internal val COLOR_REC: Color
    @Composable get() = LocalTokens.current.overRev

// Throttle bar. The track is deliberately darker than the card so an empty bar
// still reads as a control rather than as blank space.
internal val COLOR_TRACK: Color
    @Composable get() = LocalTokens.current.track
internal val COLOR_BAR: Color
    @Composable get() = LocalTokens.current.up
internal val COLOR_TICK: Color
    @Composable get() = LocalTokens.current.tick

internal val RPM_STYLE: TextStyle
    @Composable get() = TextStyle(
        color = LocalTokens.current.ink,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
    )

internal val STATUS_STYLE: TextStyle
    @Composable get() = TextStyle(
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        textAlign = TextAlign.Center,
    )

internal val LABEL_STYLE: TextStyle
    @Composable get() = TextStyle(
        color = COLOR_LABEL,
        fontSize = 9.sp,
        letterSpacing = 0.8.sp,
    )

/**
 * The single gear digit. One digit only: the estimator returns 1..N, and
 * "3rd" does not fit the 60 dp inner width at a legible size.
 */
internal val GEAR_STYLE: TextStyle
    @Composable get() = TextStyle(
        color = LocalTokens.current.ink,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
    )

internal val VALUE_STYLE: TextStyle
    @Composable get() = TextStyle(
        color = LocalTokens.current.ink,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )

internal val SPEED_STYLE_PORT: TextStyle
    @Composable get() = TextStyle(
        color = LocalTokens.current.ink,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )

internal val SPEED_STYLE_LAND: TextStyle
    @Composable get() = TextStyle(
        color = LocalTokens.current.ink,
        // 44 sp, not 34: the full-height panel has the room, and this is the
        // number a driver glances at. Not larger: three columns have to fit
        // "101" and a four-digit rpm inside 212 dp of content.
        fontSize = 44.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )

/** Larger readouts for the landscape column; portrait keeps [VALUE_STYLE]. */
internal val VALUE_STYLE_LAND: TextStyle
    @Composable get() = VALUE_STYLE.copy(fontSize = 26.sp)

internal val LABEL_STYLE_LAND: TextStyle
    @Composable get() = LABEL_STYLE.copy(fontSize = 10.sp)

internal val CUE_STYLE: TextStyle
    @Composable get() = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = COLOR_LABEL,
    )

internal val ACTION_STYLE: TextStyle
    @Composable get() = TextStyle(
        fontSize = 12.sp,
        color = LocalTokens.current.action,
    )

/** Over-rev pulse period, shared by the bubble ring and the cue rail. */
internal const val PULSE_MS = 420

/** A cue older than this dims but is still shown: it fired, it just expired. */
internal const val CUE_FRESH_MS = 2_500L

/**
 * How long the bar takes to reach a new sample.
 *
 * The stream sends about ten samples a second (100 ms apart), so a slightly
 * longer tween keeps the bar in motion continuously instead of arriving early
 * and sitting still between samples. It is a display tween, not a filter: the
 * cue logic never sees it.
 */
private const val BAR_TWEEN_MS = 120

/**
 * The throttle bar.
 *
 * Ten samples a second is not enough to look smooth on a 60-120 Hz screen, so
 * the bar tweens between samples and the animation state is read inside the
 * draw scope. Each display frame then invalidates drawing only — no
 * recomposition, no layout — which puts the bar at the screen's refresh rate
 * while the source stays at its honest 10 Hz. The digits beside it remain on
 * the 4 Hz clock in [PanelFeed].
 */
@Composable
private fun ThrottleBar(
    raw: () -> Int?,
    calibrated: Boolean,
    idleRaw: Int,
    fullRaw: Int,
    modifier: Modifier = Modifier,
) {
    // Draw scopes are not compositions: resolve the theme here and capture the
    // values, so the lambdas below never read a composable getter.
    val tokens = LocalTokens.current
    val target = raw()?.let { value ->
        if (calibrated) {
            // The shift module's own normalisation, so the bar and the cue
            // logic cannot disagree about what 40 % means.
            Throttle.normalized(value, idleRaw, fullRaw)?.toFloat()
        } else {
            // Uncalibrated: 0..255 is the channel's own range, not pedal travel.
            (value / 255f).coerceIn(0f, 1f)
        }
    } ?: 0f

    // Keep the State object rather than reading it here: `fraction.value` is
    // read inside drawBehind below, which is what keeps the animation off the
    // composition and layout paths.
    val fraction = animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = BAR_TWEEN_MS, easing = LinearEasing),
        label = "throttle-bar",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .drawBehind {
                drawRect(color = tokens.track)
                val filled = fraction.value
                if (filled > 0f) {
                    drawRect(
                        color = tokens.up,
                        size = Size(size.width * filled, size.height),
                    )
                }
                // Idle/WOT marks only exist once the wizard has measured both
                // ends; without them a tick would mark a value nobody chose.
                if (calibrated) {
                    val tick = 1.dp.toPx()
                    val maxX = (size.width - tick).coerceAtLeast(0f)
                    val idleX = (size.width * (idleRaw / 255f) - tick / 2f)
                        .coerceIn(0f, maxX)
                    val fullX = (size.width * (fullRaw / 255f) - tick / 2f)
                        .coerceIn(0f, maxX)
                    drawRect(
                        color = tokens.tick,
                        topLeft = Offset(idleX, 0f),
                        size = Size(tick, size.height),
                    )
                    drawRect(
                        color = tokens.tick,
                        topLeft = Offset(fullX, 0f),
                        size = Size(tick, size.height),
                    )
                }
            },
    )
}
