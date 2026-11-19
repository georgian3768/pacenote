package com.pocketmechanic.spike

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.pocketmechanic.shift.CalibrationWrites
import com.pocketmechanic.shift.GuidedResults
import com.pocketmechanic.shift.GuidedSample
import com.pocketmechanic.shift.GuidedState
import com.pocketmechanic.shift.GuidedStep
import com.pocketmechanic.shift.GuidedWizard
import com.pocketmechanic.shift.SettingWrite
import com.pocketmechanic.shift.SpeedScale
import com.pocketmechanic.shift.StepKind
import com.pocketmechanic.shift.StepStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Guided calibration: one walk-through that replaces the three manual wizards.
 *
 * The screen answers three questions at every moment, which is what the old
 * record/save buttons could not do:
 *  1. what to do now  - the current step's instruction;
 *  2. is it recording - live thr1/brake/speed/ratio, a 20 s rpm trace and a bar;
 *  3. did it work     - each row turns ✓ captured (with the value) or ✗ failed
 *                       (with the reason), and the value is saved immediately.
 *
 * A run is resumable: steps whose numbers are already in settings are shown as
 * saved and stepped over, so calibrating 3rd today and 4th next week works.
 * Tapping a row jumps back to that step to measure it again.
 *
 * The app is listen-only: nothing here writes to the vehicle. The one write this
 * screen performs is to the phone's own settings.
 */
class CalibrationActivity : Activity() {

    companion object {
        /** A GPS fix older than this is not used for the speed comparison. */
        private const val GPS_MAX_AGE_MS = 5000L

        /** Matches the service: after this much silence the live values are dead. */
        private const val STREAM_LOST_MS = 10_000L

        /** The rpm trace samples at ~10 Hz; the frame stream may be faster. */
        private const val SPARK_INTERVAL_MS = 100L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: AppSettings

    /**
     * Resolved once: a system light/dark switch recreates the activity. The
     * wizard rebuilds its whole view tree, so there is no live repaint path to
     * keep in step.
     */
    private val tokens by lazy { themeTokens() }

    private var wizard: GuidedWizard? = null
    /** True while the scripted bench demo runs: its numbers are not the car's. */
    private var demoRun = false
    private var lastFrameAt = 0L
    /** Last frame seen, for the advisory engine-running check. */
    private var lastFrame: Telemetry? = null
    private var lastGps: Location? = null

    // Header (fixed) ----------------------------------------------------------
    private lateinit var streamStatus: TextView

    // Scroll area -------------------------------------------------------------
    private lateinit var linkCheck: TextView
    private lateinit var engineCheck: TextView
    private lateinit var gpsCheck: TextView
    private lateinit var sparkline: RpmSparkline
    private lateinit var instruction: TextView
    private lateinit var rpmText: TextView
    private lateinit var liveLine: TextView
    private lateinit var progressText: TextView
    private lateinit var hintText: TextView
    private lateinit var savedText: TextView
    private val rows = LinkedHashMap<GuidedStep, TextView>()
    /** Gear selector: gear -> chip. Tapping one starts recording that gear. */
    private val gearChips = LinkedHashMap<Int, Button>()
    private lateinit var gearHint: TextView
    private lateinit var redlineNote: TextView
    private lateinit var redlineButton: Button

    // Footer (fixed) ----------------------------------------------------------
    private lateinit var startButton: Button

    // Reverse capture ---------------------------------------------------------
    private val capture by lazy { ReverseCapture(scope) }
    private lateinit var captureStatus: TextView
    private lateinit var captureStart: Button
    private lateinit var captureReverse: Button
    private lateinit var captureStop: Button

    // Sparkline feed ----------------------------------------------------------
    /** Newest rpm waiting to be plotted; consumed by the ~10 Hz ticker. */
    private var pendingSparkRpm: Int? = null
    private var sparkTicker: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        applySystemBarAppearance(tokens)
        setContentView(buildUi())
        SpikeService.start(this)
        startGps()
        subscribeFrames()
        startTicker()
        startSparkTicker()
    }

    override fun onDestroy() {
        sparkTicker?.cancel()
        sparkTicker = null
        if (::sparkline.isInitialized) sparkline.clear()
        // A capture is a socket in this activity's scope; it must not outlive
        // the screen. Nothing is saved without the explicit Stop tap.
        capture.abandon()
        scope.cancel()
        super.onDestroy()
    }

    // --- stream ---------------------------------------------------------------

    private fun subscribeFrames() {
        scope.launch {
            SpikeService.frames.collect { frame ->
                lastFrameAt = SystemClock.elapsedRealtime()
                lastFrame = frame
                pendingSparkRpm = frame.rpm?.toInt()
                // Corrected speed, exactly as the assistant sees it: a ratio
                // measured here must match the units the estimator compares.
                val speed = SpeedScale.applyFloat(frame.speedKph, settings.gpsSpeedScale)

                wizard?.let { w ->
                    val before = w.snapshot()
                    val state = w.update(
                        GuidedSample(
                            tMs = SystemClock.elapsedRealtime(),
                            rpm = frame.rpm,
                            throttle = frame.thr1,
                            brake = frame.brake,
                            speedKph = speed?.toDouble(),
                            gpsKph = freshGpsKph(),
                        )
                    )
                    val applied = persist(before.results, state.results)
                    render(state)
                    announce(before, state, applied)
                }
                updateLive(frame, speed)
            }
        }
    }

    /**
     * Stream freshness. "The wizard is broken" and "there is no data at all"
     * look identical on a frozen screen, so the screen says which one it is.
     */
    private fun startTicker() {
        scope.launch {
            while (true) {
                val age = if (lastFrameAt == 0L) null else
                    (SystemClock.elapsedRealtime() - lastFrameAt) / 1000.0
                val (text, colour) = when {
                    age == null -> "⚠ no frames yet - is the ESP powered and the app streaming?" to
                        tokens.down.toArgb()
                    age > 2.0 -> "⚠ no frames for ${fmt1(age)} s - ESP off or stream stopped" to
                        tokens.overRev.toArgb()
                    else -> "stream live · last frame ${fmt1(age)} s ago" to
                        tokens.up.toArgb()
                }
                streamStatus.text = text
                streamStatus.setTextColor(colour)
                updateHealth(age)
                renderCapture()
                if (age != null && age * 1000 >= STREAM_LOST_MS) {
                    // Stale must look stale: never leave a frozen last value
                    // sitting where a live number belongs.
                    rpmText.text = "rpm --"
                    rpmText.setTextColor(tokens.faint.toArgb())
                    liveLine.text = "thr1 -- · brake -- · speed -- km/h · " +
                        "no data for ${age.toInt()} s"
                }
                delay(1000)
            }
        }
    }

    /**
     * Feeds the rpm trace at ~10 Hz from the frame stream, which may be faster.
     * Kept separate from the stream so a burst of frames cannot flood the view.
     */
    private fun startSparkTicker() {
        sparkTicker = scope.launch {
            while (true) {
                delay(SPARK_INTERVAL_MS)
                val rpm = pendingSparkRpm
                pendingSparkRpm = null
                if (rpm != null) sparkline.add(rpm)
            }
        }
    }

    private fun startGps() {
        if (!gpsGranted()) {
            EventLog.add("calib", "GPS permission missing - the GPS scale step cannot run")
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, { loc -> lastGps = loc }, mainLooper,
            )
        }.onFailure { EventLog.add("calib", "GPS updates failed: ${it.message}") }
    }

    /** The single permission check: the request path and the health line share it. */
    private fun gpsGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** GPS speed in km/h, but only from a fix young enough to trust. */
    private fun freshGpsKph(): Double? {
        val loc = lastGps ?: return null
        if (!loc.hasSpeed()) return null
        if (System.currentTimeMillis() - loc.time > GPS_MAX_AGE_MS) return null
        return loc.speed * 3.6
    }

    // --- advisory pre-check ---------------------------------------------------

    /**
     * Colours three facts and gates nothing. Which step actually needs what:
     *  - idle / accel / release need engine running + the ESP link;
     *  - brake needs the ESP link only;
     *  - gears need the ESP link + a CAN speed;
     *  - GPS scale needs CAN speed + the GPS permission (and a fix);
     *  - redline confirmation needs nothing - it is a stored number.
     * Only the step that needs a condition may refuse to start, and it says why
     * in its own hint; there is no global gate in front of the wizard.
     */
    private fun updateHealth(ageSec: Double?) {
        if (!::linkCheck.isInitialized) return

        // 1. ESP link: frames arriving within the last STREAM_LOST_MS.
        when {
            lastFrameAt == 0L -> health(linkCheck, "·", "ESP link", "no frames yet", tokens.faint.toArgb())
            ageSec != null && ageSec * 1000 < STREAM_LOST_MS ->
                health(linkCheck, "●", "ESP link", "ok · last frame ${fmt1(ageSec)} s ago", tokens.up.toArgb())
            else -> health(
                linkCheck, "▲", "ESP link",
                "no frames for ${ageSec?.toInt() ?: 0} s", tokens.down.toArgb(),
            )
        }

        // 2. Engine running: the car's own bit, or an rpm that cannot be cranking.
        val frame = lastFrame
        when {
            frame == null ->
                health(engineCheck, "·", "engine running", "unknown - no frame yet", tokens.faint.toArgb())
            frame.engineRunning == null && frame.rpm == null ->
                health(engineCheck, "·", "engine running", "unknown - no rpm or engine bit", tokens.faint.toArgb())
            frame.engineRunning == true || (frame.rpm ?: 0f) > 500f ->
                health(
                    engineCheck, "●", "engine running",
                    "yes · rpm ${frame.rpm?.toInt() ?: 0}", tokens.up.toArgb(),
                )
            else -> health(
                engineCheck, "▲", "engine running", "no - start the engine first", tokens.down.toArgb(),
            )
        }

        // 3. GPS permission: same check startGps() uses, no second request path.
        if (!gpsGranted()) {
            health(gpsCheck, "▲", "GPS permission", "not granted - the GPS step cannot run", tokens.down.toArgb())
        } else {
            val fix = freshGpsKph()
            health(
                gpsCheck, "●", "GPS permission",
                if (fix != null) "granted · fix ${fix.toInt()} km/h" else "granted · waiting for a fix",
                tokens.up.toArgb(),
            )
        }
    }

    private fun health(view: TextView, mark: String, label: String, detail: String, colour: Int) {
        view.text = "$mark $label · $detail"
        view.setTextColor(colour)
    }

    // --- guided run -----------------------------------------------------------

    private fun startGuided() {
        val w = GuidedWizard(
            gearCount = settings.car.gearCount,
            savedRatios = settings.gearRatios,
        )
        wizard = w
        demoRun = false
        EventLog.add(
            "calib",
            "guided start: ${w.steps.size} steps, saved ratios=${settings.gearRatios}",
        )
        render(w.snapshot())
        toast("guided check started")
    }

    private fun startSyntheticDemo() {
        val svc = SpikeService.instance
        if (svc == null) {
            toast("service not running - open the connection screen first")
            return
        }
        startGuided()
        demoRun = true
        EventLog.add("calib", "guided demo: synthetic drive, no car (values not saved)")
        svc.startSynthetic(35)
        demoDriverGears()
        toast("demo started: watch the steps tick off")
    }

    // --- reverse capture ------------------------------------------------------

    private fun startCapture() {
        val why = capture.start()
        if (why != null) {
            toast(why)
        } else {
            toast("capture started - neutral phase")
        }
        renderCapture()
    }

    private fun markReverse() {
        if (capture.markReverse()) {
            toast("reverse marker written")
        } else {
            toast("not recording")
        }
        renderCapture()
    }

    private fun stopCapture() {
        captureStop.isEnabled = false
        scope.launch {
            val result = capture.stop()
            renderCapture()
            if (result.frames == 0) {
                toast("capture is empty - nothing saved")
                return@launch
            }
            runCatching {
                val uri = withContext(Dispatchers.IO) {
                    Exports.writeCsv(this@CalibrationActivity, result.name, result.csv)
                }
                EventLog.add(
                    "capture",
                    "wrote Downloads/Pacenote/${result.name} (${result.csv.length} bytes, " +
                        "${result.frames} frames, reverseMarked=${result.reverseMarked}, " +
                        "dropped=${result.dropped})"
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, result.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share ${result.name}"))
            }.onFailure {
                EventLog.add("capture", "export failed: ${it.javaClass.simpleName}: ${it.message}")
                toast("could not write the capture: ${it.message}")
            }
        }
    }

    /** One place for the capture's text and button states; called at 1 Hz. */
    private fun renderCapture() {
        if (!::captureStatus.isInitialized) return
        val status = capture.status
        val now = SystemClock.elapsedRealtime()

        captureStart.isEnabled = status == ReverseCapture.Status.IDLE ||
            status == ReverseCapture.Status.STOPPED ||
            status == ReverseCapture.Status.ERROR
        captureReverse.isEnabled =
            status == ReverseCapture.Status.STREAMING && capture.phase == "N"
        captureStop.isEnabled = status == ReverseCapture.Status.CONNECTING ||
            status == ReverseCapture.Status.STREAMING

        captureStatus.text = when (status) {
            ReverseCapture.Status.IDLE ->
                "idle - needs the ESP's Wi-Fi network and the engine running"
            ReverseCapture.Status.CONNECTING ->
                "connecting to ${WifiController.ESP_HOST}:23 ..."
            ReverseCapture.Status.STREAMING -> {
                val last = capture.lastFrameElapsed
                val age = if (last < 0) "no frames yet"
                else "last frame ${fmt1((now - last) / 1000.0)} s ago"
                val phase = if (capture.phase == "N") "N (neutral)" else "R (reverse)"
                "recording · ${capture.frames} frames · phase $phase · $age"
            }
            ReverseCapture.Status.STOPPED ->
                "stopped - ${capture.frames} frames captured"
            ReverseCapture.Status.ERROR ->
                "failed: ${capture.error ?: "unknown error"}"
        }
        captureStatus.setTextColor(
            when (status) {
                ReverseCapture.Status.STREAMING -> tokens.up.toArgb()
                ReverseCapture.Status.ERROR -> tokens.overRev.toArgb()
                else -> tokens.dim.toArgb()
            }
        )
    }

    /**
     * The demo has no driver to tap gear chips, so it taps them itself: select
     * gear 1 as soon as the gear stage is reached, wait for its capture, then
     * select the next one. It exercises the same selection path as a real run.
     */
    private fun demoDriverGears() {
        val w = wizard ?: return
        val maxGear = settings.car.gearCount
        scope.launch {
            var gear = 1
            var elapsed = 0
            while (demoRun && elapsed < 28_000) {
                val state = w.snapshot()
                if (state.current.kind == StepKind.GPS) break
                if (state.current.kind == StepKind.GEAR && state.awaitingGearChoice) {
                    if (gear > maxGear) break
                    w.selectGear(gear)
                    render(w.snapshot())
                    var waited = 0
                    while (demoRun && waited < 8_000) {
                        delay(200)
                        waited += 200
                        if (w.snapshot().results.gearRatios[gear] != null) break
                    }
                    if (w.snapshot().results.gearRatios[gear] == null) break
                    gear++
                } else {
                    delay(200)
                    elapsed += 200
                }
            }
            if (demoRun && wizard != null) {
                render(w.continuePastGears())
            }
        }
    }

    private fun skipStep() {
        val w = wizard ?: return
        val before = w.snapshot()
        val state = w.skip()
        EventLog.add("calib", "guided skipped ${before.current.label}")
        render(state)
    }

    private fun stopGuided() {
        val w = wizard ?: return
        EventLog.add("calib", "guided stopped at ${w.snapshot().current.label}")
        wizard = null
        demoRun = false
        render(w.snapshot().copy(hint = "Stopped. Start again to continue where you left off."))
    }

    private fun jumpTo(step: GuidedStep) {
        val w = wizard ?: return
        if (step.kind == StepKind.DONE) return
        val state = w.jumpTo(step)
        EventLog.add("calib", "guided jumped to ${step.label}")
        render(state)
    }

    /** Gear chip: record the gear the driver is actually in. */
    private fun selectGear(gear: Int) {
        val w = wizard
        if (w == null) {
            toast("press Start guided check first")
            return
        }
        val state = w.selectGear(gear)
        EventLog.add("calib", "guided selected gear $gear")
        render(state)
    }

    /** Ends the gear stage without pretending the unrecorded gears were done. */
    private fun continuePastGears() {
        val w = wizard ?: return
        val state = w.continuePastGears()
        EventLog.add("calib", "guided continued past gears")
        render(state)
    }

    // --- saving ---------------------------------------------------------------

    /**
     * Writes each newly captured number as soon as its step completes, so a run
     * can be stopped at any point without losing what already worked. The diff
     * itself lives in the tested `CalibrationWrites` module; this only applies it.
     */
    private fun persist(before: GuidedResults, after: GuidedResults): List<String> {
        if (demoRun) return emptyList()
        val apply = CalibrationWrites.diff(before, after, settings.throttleIdleRaw)
        apply.refusals.forEach { EventLog.add("calib", "guided: $it") }

        val applied = mutableListOf<String>()
        apply.writes.forEach { write ->
            when (write) {
                is SettingWrite.ThrottleIdle -> {
                    settings.throttleIdleRaw = write.raw
                    applied += "idle raw ${write.raw}"
                }
                is SettingWrite.ThrottleFull -> {
                    settings.throttleFullRaw = write.raw
                    applied += "full raw ${write.raw}"
                }
                is SettingWrite.GearRatio -> {
                    storeGearRatio(write.gear, write.ratio)
                    applied += "gear ${write.gear} ${fmt1(write.ratio)}"
                }
                is SettingWrite.GpsScale -> {
                    settings.gpsSpeedScale = write.scale
                    applied += "GPS scale ${fmt2(write.scale)}"
                }
            }
        }
        if (applied.isNotEmpty()) {
            EventLog.add("calib", "guided saved: ${applied.joinToString(", ")}")
            refreshSaved()
        }
        return applied
    }

    private fun storeGearRatio(gear: Int, value: Double) {
        val list = settings.gearRatios.toMutableList()
        while (list.size < settings.car.gearCount) list.add(0.0)
        list[gear - 1] = value
        settings.gearRatios = list
    }

    // --- rendering ------------------------------------------------------------

    private fun render(state: GuidedState) {
        val running = wizard != null
        val current = state.current

        instruction.text = when {
            !running -> "Press Start, then follow the prompts one at a time."
            state.finished && demoRun ->
                "Demo complete - these values are synthetic and were not saved."
            state.finished -> "All steps finished. Values were saved as each step completed."
            state.awaitingGearChoice ->
                "Tap the gear you are actually in below, then hold 40-70 km/h."
            state.selectedGear != null && current.kind == StepKind.GEAR ->
                "Recording gear ${state.selectedGear} - hold a steady speed."
            // A hint from this car's own capture, not a gate: the wizard still
            // accepts any steady idle it can measure.
            current.kind == StepKind.IDLE ->
                "${current.instruction} Warm idle on this car: 674-924 rpm."
            else -> current.instruction
        }

        state.views.filter { it.step.kind == StepKind.GEAR }.forEach { view ->
            val chip = gearChips[view.step.gear] ?: return@forEach
            val value = state.results.gearRatios[view.step.gear]
            chip.text = if (value != null) {
                "${view.step.gear} · ${fmt1(value)}"
            } else {
                "${view.step.gear}"
            }
            val colour = when (view.status) {
                StepStatus.CAPTURED -> tokens.up.toArgb()
                StepStatus.SAVED -> tokens.info.toArgb()
                StepStatus.ACTIVE -> tokens.down.toArgb()
                StepStatus.FAILED -> tokens.overRev.toArgb()
                StepStatus.SKIPPED -> tokens.faint.toArgb()
                StepStatus.PENDING -> tokens.dim.toArgb()
            }
            chip.setTextColor(colour)
        }
        if (::gearHint.isInitialized) {
            gearHint.text = when {
                !running -> "Tap a gear while driving in it; each is saved on its own."
                state.current.kind != StepKind.GEAR ->
                    "Gears are optional - record each one when you are in it."
                state.awaitingGearChoice ->
                    "Tap 1-${settings.car.gearCount}: only the gear you are in is recorded."
                else -> "Recording gear ${state.selectedGear}..."
            }
        }

        state.views.filter { it.step.kind != StepKind.GEAR }.forEach { view ->
            val row = rows[view.step] ?: return@forEach
            val (mark, colour) = when (view.status) {
                StepStatus.CAPTURED -> "✓" to tokens.up.toArgb()
                StepStatus.SAVED -> "✓" to tokens.info.toArgb()
                StepStatus.ACTIVE -> "▶" to tokens.down.toArgb()
                StepStatus.SKIPPED -> "–" to tokens.faint.toArgb()
                StepStatus.FAILED -> "✗" to tokens.overRev.toArgb()
                StepStatus.PENDING -> "·" to tokens.faint.toArgb()
            }
            val suffix = view.detail.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
            row.text = "$mark ${view.step.label}$suffix"
            row.setTextColor(colour)
        }

        progressText.text = when {
            !running -> ""
            state.finished -> ""
            state.awaitingGearChoice -> ""
            else -> {
                val pct = (state.progress * 100).toInt().coerceIn(0, 100)
                val secs = state.elapsedMs / 1000
                val bar = "█".repeat(pct / 10) + "░".repeat(10 - pct / 10)
                "step ${secs}s · $bar $pct%"
            }
        }
        hintText.text = when {
            !running -> if (lastFrameAt == 0L) {
                "no frames yet - check the ESP is powered"
            } else {
                "ready"
            }
            state.finished -> ""
            else -> state.hint
        }
        startButton.text = if (running) "Restart check" else "Start guided check"
        refreshSaved()
    }

    private fun announce(
        before: GuidedState,
        after: GuidedState,
        applied: List<String>,
    ) {
        after.views.filter { it.step.kind != StepKind.DONE }.forEach { view ->
            val was = before.views.firstOrNull { it.step == view.step }?.status
            if (view.status == StepStatus.CAPTURED && was != StepStatus.CAPTURED) {
                val saved = if (demoRun) " (demo, not saved)" else ""
                toast("✓ ${view.step.label}: ${view.detail}$saved")
                EventLog.add("calib", "guided captured ${view.step.label} (${view.detail})$saved")
            } else if (view.status == StepStatus.FAILED && was != StepStatus.FAILED) {
                toast("✗ ${view.step.label}: ${view.detail}")
                EventLog.add("calib", "guided failed ${view.step.label} (${view.detail})")
            }
        }
        if (applied.isNotEmpty()) {
            toast("saved ${applied.joinToString(", ")}")
        }
    }

    private fun updateLive(frame: Telemetry, corrected: Float?) {
        val rpm = frame.rpm
        rpmText.text = if (rpm != null) "rpm ${rpm.toInt()}" else "rpm --"
        rpmText.setTextColor(
            if (rpm != null && rpm > 300f) tokens.ink.toArgb() else tokens.faint.toArgb()
        )
        val ratio = if (rpm != null && corrected != null && corrected > 5f) {
            " · ratio ${fmt1((rpm / corrected).toDouble())}"
        } else ""
        val gear = AssistantHost.output?.gear?.let { " · gear $it" } ?: " · gear G"
        val gps = freshGpsKph()?.let { " · GPS ${it.toInt()} km/h" } ?: ""
        // thr1 is the channel the capture uses; whether thr1 or thr2 is the real
        // accelerator position is unresolved, so the raw channel is named.
        liveLine.text = "thr1 ${frame.thr1 ?: "--"} · brake " +
            (frame.brake?.let { if (it) "ON" else "off" } ?: "--") +
            " · speed ${corrected?.toInt() ?: "--"} km/h" + ratio + gear + gps
    }

    private fun refreshSaved() {
        val ratios = settings.gearRatios
            .mapIndexedNotNull { i, v -> if (v > 0.0) "${i + 1}:${fmt1(v)}" else null }
        savedText.text = "throttle idle ${settings.throttleIdleRaw} / full " +
            "${settings.throttleFullRaw} · gears " +
            (if (ratios.isEmpty()) "none yet" else ratios.joinToString(", ")) +
            " · GPS scale ${fmt3(settings.gpsSpeedScale)}"
    }

    // --- UI -------------------------------------------------------------------

    /**
     * Fixed header (title + stream status), a weighted scroll area holding the
     * pre-check, the live readouts, the four step sections and the redline
     * confirmation, then a fixed footer with the primary action so it can never
     * scroll out of reach.
     */
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Stands in for fitsSystemWindows: keep every edge clear of the
            // status bar and the gesture area without insets APIs.
            setPadding(40, 48, 40, 40)
            // Same page black as the dashboard and the menu: three near-blacks
            // are shared across every surface, not one each.
            setBackgroundColor(tokens.page.toArgb())
        }

        // --- fixed header ----------------------------------------------------
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(title("Sensor calibration"))
        header.addView(
            note("Listen-only: this app never writes to the vehicle.")
                .apply { setTextColor(tokens.info.toArgb()) }
        )
        header.addView(
            note(
                "One guided run: it measures throttle, every gear ratio and the GPS " +
                    "speed scale, and saves each value the moment its step turns ✓."
            )
        )
        streamStatus = note("⚠ no frames yet - is the ESP powered and the app streaming?")
        header.addView(streamStatus)
        root.addView(header)

        // --- scroll area -----------------------------------------------------
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Pre-check: advisory only, in the order the driver will need it.
        content.addView(section("pre-check"))
        content.addView(
            note("A step only refuses to start when it needs a condition that is missing.")
        )
        linkCheck = healthLine().also { content.addView(it) }
        engineCheck = healthLine().also { content.addView(it) }
        gpsCheck = healthLine().also { content.addView(it) }

        // 20 s rpm trace, then the numeric readouts.
        sparkline = RpmSparkline(this, tokens)
        content.addView(
            sparkline,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(104),
            ),
        )
        rpmText = TextView(this).apply {
            text = "rpm --"
            textSize = 38f
            // Monospace keeps the digits from shifting the layout twice a second.
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(tokens.faint.toArgb())
            setPadding(0, 2, 0, 2)
        }
        content.addView(rpmText)
        liveLine = note("thr1 -- · brake -- · speed -- km/h").apply {
            typeface = Typeface.MONOSPACE
        }
        content.addView(liveLine)
        content.addView(note("thr1 is the raw CAN throttle channel the capture uses."))

        // Current step, progress and the wizard's own hint.
        instruction = TextView(this).apply {
            text = "Press Start, then follow the prompts one at a time."
            textSize = 17f
            setTextColor(tokens.ink.toArgb())
            setPadding(0, 8, 0, 4)
        }
        content.addView(instruction)
        progressText = note("").also { content.addView(it) }
        hintText = note("ready").also { content.addView(it) }

        // Step rows, grouped under four labels. The labels are grouping only:
        // they do not gate, reorder or renumber the wizard's own sequence.
        val preview = wizard?.snapshot()
        val steps = preview?.views?.map { it.step }
            ?: (listOf(
                GuidedStep(StepKind.IDLE),
                GuidedStep(StepKind.ACCEL),
                GuidedStep(StepKind.RELEASE),
                GuidedStep(StepKind.BRAKE),
            ) + (1..settings.car.gearCount).map { GuidedStep(StepKind.GEAR, gear = it) } +
                listOf(GuidedStep(StepKind.GPS)))
        var shownSection = ""
        var gearSectionAdded = false
        steps.forEach { step ->
            val groupLabel = sectionFor(step)
            if (groupLabel != shownSection) {
                shownSection = groupLabel
                content.addView(section(groupLabel))
            }
            if (step.kind == StepKind.GEAR) {
                if (gearSectionAdded) return@forEach
                gearSectionAdded = true
                gearHint = note("Tap a gear while driving in it; each is saved on its own.")
                content.addView(gearHint)
                val chips = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                (1..settings.car.gearCount).forEach { gear ->
                    val chip = button("$gear") { selectGear(gear) }
                    chips.addView(
                        chip,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    gearChips[gear] = chip
                }
                content.addView(chips)
                content.addView(button("Continue past gears - GPS scale") { continuePastGears() })
                return@forEach
            }
            if (step.kind == StepKind.DONE) return@forEach
            val row = TextView(this).apply {
                text = "· ${step.label}"
                textSize = 13f
                setTextColor(tokens.faint.toArgb())
                setPadding(0, 6, 0, 6)
                setOnClickListener { jumpTo(step) }
            }
            rows[step] = row
            content.addView(row)
        }
        content.addView(note("Tap any step to measure it again."))

        // Redline confirmation: still the gate for over-rev, now its own group.
        content.addView(section("redline confirmation"))
        redlineNote = note("").also { content.addView(it) }
        redlineButton = button("") { confirmRedline() }.also { content.addView(it) }
        renderRedline()

        content.addView(section("stored values"))
        savedText = note("").also { content.addView(it) }
        refreshSaved()
        content.addView(
            note(
                "Steps already saved are ticked blue and skipped on the next run. " +
                    "Gear ratios and the GPS scale need driving; skip them now and " +
                    "come back when you can."
            )
        )

        // Bench demo: a scripted 35 s drive (idle -> stomp -> brake -> gears -> GPS)
        // so the whole wizard can be watched without the car or the ESP.
        content.addView(section("bench demo"))
        content.addView(button("Try it: synthetic demo (35 s)") { startSyntheticDemo() })

        // Reverse capture: a raw dump, not part of the guided run. It answers a
        // question the wizard cannot (which frame reports reverse) and the
        // answer does not exist yet, so it stays a separate, explicit action.
        content.addView(section("reverse capture"))
        content.addView(
            note(
                "For finding the reverse signal. Park with the engine running in " +
                    "neutral, start the capture, select reverse when asked, then stop " +
                    "and save. The file is the ESP32's raw text GVRET stream."
            )
        )
        content.addView(
            note(
                "Listen-only: the capture sends one keepalive so the ESP starts its " +
                    "telnet stream, never a CAN frame. Close SavvyCAN's Wi-Fi " +
                    "connection first - the ESP serves one telnet client."
            )
        )
        captureStatus = note("idle").apply { typeface = Typeface.MONOSPACE }
        content.addView(captureStatus)
        captureStart = button("Start reverse capture") { startCapture() }
        content.addView(captureStart)
        captureReverse = button("Reverse is selected now") { markReverse() }
        captureReverse.isEnabled = false
        content.addView(captureReverse)
        captureStop = button("Stop and save capture") { stopCapture() }
        captureStop.isEnabled = false
        content.addView(captureStop)
        renderCapture()

        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // --- fixed footer ----------------------------------------------------
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 0)
        }
        footer.addView(
            View(this).apply { setBackgroundColor(tokens.border.toArgb()) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )
        startButton = button("Start guided check") { startGuided() }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        actions.addView(
            startButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f),
        )
        actions.addView(button("Skip step") { skipStep() })
        actions.addView(button("Stop") { stopGuided() })
        footer.addView(actions)
        root.addView(footer)

        updateHealth(if (lastFrameAt == 0L) null else
            (SystemClock.elapsedRealtime() - lastFrameAt) / 1000.0)
        return root
    }

    /** Grouping label for a step. Labels only; the step order is untouched. */
    private fun sectionFor(step: GuidedStep): String = when (step.kind) {
        StepKind.IDLE -> "1 · engine idle (baseline)"
        StepKind.ACCEL, StepKind.RELEASE, StepKind.BRAKE -> "2 · throttle and pedals"
        StepKind.GEAR -> "3 · gear ratios - pick the gear you are in"
        StepKind.GPS, StepKind.DONE -> "4 · speed and GPS"
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 26f
        setTextColor(tokens.ink.toArgb())
        setPadding(0, 0, 0, 12)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(tokens.dim.toArgb())
        setPadding(0, 6, 0, 10)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11f
        letterSpacing = 0.1f
        setTextColor(tokens.up.toArgb())
        setPadding(0, 22, 0, 8)
    }

    private fun healthLine() = TextView(this).apply {
        text = "· checking..."
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(tokens.faint.toArgb())
        setPadding(0, 4, 0, 4)
    }

    /**
     * Flat dark button with a hairline border, built in code because this screen
     * uses the platform theme: the default Material button is a grey filled slab
     * that looks nothing like the rest of the app.
     */
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(tokens.action.toArgb())
        letterSpacing = 0.04f
        minHeight = dp(44)
        // Not focusable-looking: the pressed state is the ripple, not a focus box.
        stateListAnimator = null
        background = rippleBackground()
        setOnClickListener { onClick() }
    }

    private fun rippleBackground(): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(tokens.card.toArgb())
            setStroke(dp(1), tokens.border.toArgb())
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(56, 46, 194, 126)),
            shape,
            shape.constantState?.newDrawable()?.mutate(),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- sparkline ------------------------------------------------------------

    /**
     * Last ~20 s of rpm as a scrolling line. Frames arrive faster than the eye
     * (or the trace) needs, so the activity feeds it at ~10 Hz. Nothing is
     * allocated in onDraw: the ring buffer, the Path and the coordinate arrays
     * are reused, and an empty buffer just draws the baseline.
     */
    private class RpmSparkline(context: Context, tokens: Tokens) : View(context) {

        companion object {
            /** 200 slots at 10 Hz = 20 s. */
            private const val CAPACITY = 200
            private const val MIN_TOP_RPM = 1000
        }

        private val values = IntArray(CAPACITY)
        private var count = 0
        private var head = 0

        private val path = Path()
        private val xs = FloatArray(CAPACITY)
        private val ys = FloatArray(CAPACITY)

        /** Faint line drawn at the car's redline, when one is known. */
        var redlineRpm: Int = 0

        private val gridPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = tokens.border.toArgb()
            isAntiAlias = true
        }

        private val linePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = tokens.info.toArgb()
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val dotPaint = Paint().apply {
            style = Paint.Style.FILL
            color = tokens.info.toArgb()
            isAntiAlias = true
        }

        private val redlinePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = tokens.overRev.toArgb()
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }

        fun add(rpm: Int) {
            values[head] = rpm.coerceAtLeast(0)
            head = (head + 1) % CAPACITY
            if (count < CAPACITY) count++
            invalidate()
        }

        fun clear() {
            count = 0
            head = 0
            invalidate()
        }

        private fun valueAt(i: Int): Int {
            val oldest = if (count < CAPACITY) 0 else head
            return values[(oldest + i) % CAPACITY]
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val padL = 4f
            val padR = 4f
            val padT = 6f
            val padB = 6f
            val plotW = w - padL - padR
            val plotH = h - padT - padB
            if (plotW <= 0f || plotH <= 0f) return

            // Baseline first, so an empty trace still reads as "chart, no data".
            val baseY = padT + plotH
            canvas.drawLine(padL, baseY, padL + plotW, baseY, gridPaint)
            if (count == 0) return

            var peak = redlineRpm
            var i = 0
            while (i < count) {
                val v = valueAt(i)
                if (v > peak) peak = v
                i++
            }
            if (peak < MIN_TOP_RPM) peak = MIN_TOP_RPM
            val top = peak * 1.1f

            if (redlineRpm > 0) {
                val y = padT + plotH * (1f - redlineRpm / top)
                canvas.drawLine(padL, y, padL + plotW, y, redlinePaint)
            }

            val slot = plotW / (CAPACITY - 1)
            i = 0
            while (i < count) {
                xs[i] = padL + plotW - (count - 1 - i) * slot
                ys[i] = padT + plotH * (1f - valueAt(i) / top)
                i++
            }
            if (count == 1) {
                canvas.drawCircle(xs[0], ys[0], 3f, dotPaint)
                return
            }
            path.reset()
            path.moveTo(xs[0], ys[0])
            i = 1
            while (i < count) {
                path.lineTo(xs[i], ys[i])
                i++
            }
            canvas.drawPath(path, linePaint)
        }
    }

    // --- redline --------------------------------------------------------------

    /**
     * The redline is a placeholder until the driver confirms it, because it is
     * not in the CAN capture and the Celerio's is not published. Confirming
     * stores the number itself, so editing it later asks again.
     */
    private fun confirmRedline() {
        val rpm = settings.car.redlineRpm
        settings.confirmedRedlineRpm = rpm
        toast("redline confirmed: $rpm rpm")
        renderRedline()
        // Rebuild the assistant now instead of on the next frame, so a cue that
        // was held back by the gate can fire immediately.
        AssistantHost.configure(settings)
    }

    private fun renderRedline() {
        val car = settings.car
        val confirmed = settings.redlineTrusted
        if (::sparkline.isInitialized) sparkline.redlineRpm = car.redlineRpm
        redlineNote.text = if (confirmed) {
            "Over-rev and the eco score use ${car.redlineRpm} rpm. Change it in the car " +
                "profile and it has to be confirmed again."
        } else {
            "The redline is still the ${car.redlineRpm} rpm placeholder. Until you confirm " +
                "it, over-rev stays silent and every trip score is marked provisional."
        }
        redlineButton.text = if (confirmed) {
            "Redline ${car.redlineRpm} rpm confirmed ✓"
        } else {
            "Confirm ${car.redlineRpm} rpm as the redline"
        }
        redlineButton.isEnabled = !confirmed
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        EventLog.add("calib", message)
    }

    private fun fmt1(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun fmt2(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun fmt3(value: Double): String = String.format(Locale.US, "%.3f", value)
}
