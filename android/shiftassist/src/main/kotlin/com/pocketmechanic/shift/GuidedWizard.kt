package com.pocketmechanic.shift

import kotlin.math.max
import kotlin.math.min

/**
 * One step of the guided calibration. A step is either a measurement that ends
 * in a number worth saving (throttle idle/full, one gear ratio, GPS scale) or a
 * verification that the car's signal decodes (release, brake).
 */
enum class StepKind { IDLE, ACCEL, RELEASE, BRAKE, GEAR, GPS, DONE }

data class GuidedStep(val kind: StepKind, val gear: Int = 0) {

    val label: String
        get() = when (kind) {
            StepKind.IDLE -> "engine idle"
            StepKind.ACCEL -> "accelerator to the floor"
            StepKind.RELEASE -> "release the accelerator"
            StepKind.BRAKE -> "press the brake"
            StepKind.GEAR -> "gear $gear ratio"
            StepKind.GPS -> "GPS speed scale"
            StepKind.DONE -> "done"
        }

    val instruction: String
        get() = when (kind) {
            StepKind.IDLE -> "Let the engine idle: foot off the pedal, out of gear."
            StepKind.ACCEL -> "Press the accelerator all the way down and hold it."
            StepKind.RELEASE -> "Release the accelerator."
            StepKind.BRAKE -> "Press the brake pedal and hold it."
            StepKind.GEAR -> "Pick the gear you are driving in, then hold a steady 40-70 km/h."
            StepKind.GPS -> "Keep driving above 20 km/h so GPS and CAN speed can be compared."
            StepKind.DONE -> "All steps finished."
        }

    /** True for steps whose number gets written to settings. */
    val savesValue: Boolean
        get() = kind == StepKind.IDLE || kind == StepKind.ACCEL ||
            kind == StepKind.GEAR || kind == StepKind.GPS
}

enum class StepStatus { PENDING, ACTIVE, CAPTURED, SAVED, SKIPPED, FAILED }

/** One row of the wizard, ready to be rendered. */
data class StepView(val step: GuidedStep, val status: StepStatus, val detail: String = "")

/** Everything the wizard measured, in the units the settings expect. */
data class GuidedResults(
    val idleThrottle: Int? = null,
    val fullThrottle: Int? = null,
    val idleRpm: Int? = null,
    val fullRpm: Int? = null,
    val releaseThrottle: Int? = null,
    val brakeSeen: Boolean = false,
    /** gear -> rpm per km/h, only the gears measured in this run. */
    val gearRatios: Map<Int, Double> = emptyMap(),
    val gpsScale: Double? = null,
    val gpsPairs: Int = 0,
)

data class GuidedState(
    val current: GuidedStep = GuidedStep(StepKind.IDLE),
    val views: List<StepView> = emptyList(),
    val progress: Float = 0f,
    val elapsedMs: Long = 0L,
    val hint: String = GuidedStep(StepKind.IDLE).instruction,
    /** True when the step has been waiting far longer than it should. */
    val stalled: Boolean = false,
    val finished: Boolean = false,
    val results: GuidedResults = GuidedResults(),
    /** True while the gear step waits for the driver to tap a gear. */
    val awaitingGearChoice: Boolean = false,
    /** The gear currently being recorded, null while choosing. */
    val selectedGear: Int? = null,
) {
    fun statusOf(step: GuidedStep): StepStatus =
        views.firstOrNull { it.step == step }?.status ?: StepStatus.PENDING
}

/** A live frame plus the GPS speed when one is fresh, in the assistant's units. */
data class GuidedSample(
    val tMs: Long,
    val rpm: Float?,
    val throttle: Int?,
    val brake: Boolean?,
    val speedKph: Double?,
    val gpsKph: Double? = null,
)

/**
 * The guided calibration state machine.
 *
 * It measures, it does not save: the activity watches [GuidedState.results] and
 * writes each captured number to settings the moment its step turns CAPTURED,
 * so a run can be stopped and resumed without losing what already worked.
 *
 * Steps that already have a saved value (gear ratios from an earlier run) start
 * as [StepStatus.SAVED] and are stepped over, which is what makes "calibrate 3rd
 * today, 4th next week" possible. [jumpTo] lets the driver redo any single step
 * by tapping its row.
 */
class GuidedWizard(
    gearCount: Int = 5,
    private val savedRatios: List<Double> = emptyList(),
    private val idleMinRpm: Float = 300f,
    private val idleMaxRpm: Float = 2500f,
    private val idleHoldMs: Long = 1200,
    private val idleMinSamples: Int = 8,
    private val idleThrottleSpread: Int = 20,
    private val idleRpmSpread: Float = 250f,
    private val accelRiseRaw: Int = 50,
    private val accelRiseRpm: Int = 700,
    private val accelHoldMs: Long = 700,
    private val releaseSlackRaw: Int = 25,
    private val releaseHoldMs: Long = 600,
    private val ratioMinSpeedKph: Double = 20.0,
    private val ratioMinSamples: Int = 10,
    private val ratioHoldMs: Long = 2000,
    private val gpsMinSpeedKph: Double = 20.0,
    private val gpsMinPairs: Int = 5,
    private val gpsHoldMs: Long = 3000,
    private val gpsMinScale: Double = 0.5,
    private val gpsMaxScale: Double = 2.0,
) {

    val steps: List<GuidedStep> =
        listOf(
            GuidedStep(StepKind.IDLE),
            GuidedStep(StepKind.ACCEL),
            GuidedStep(StepKind.RELEASE),
            GuidedStep(StepKind.BRAKE),
        ) + (1..gearCount).map { GuidedStep(StepKind.GEAR, gear = it) } +
            listOf(GuidedStep(StepKind.GPS), GuidedStep(StepKind.DONE))

    private var views: MutableList<StepView> = steps.map { step ->
        val saved = savedRatioFor(step)
        if (saved != null) {
            StepView(step, StepStatus.SAVED, "saved ${fmt1(saved)} rpm/(km/h)")
        } else {
            StepView(step, StepStatus.PENDING)
        }
    }.toMutableList()

    private var results = GuidedResults(
        gearRatios = steps.filter { it.kind == StepKind.GEAR }
            .mapNotNull { step -> savedRatioFor(step)?.let { step.gear to it } }
            .toMap(),
    )

    private var index = firstPendingIndex()
    private var stepStartedMs = 0L
    private var lastMs = 0L
    private var finished = false
    // Gear ratios are recorded per chosen gear, not 1..5 in sequence: the driver
    // taps the gear they are actually in, so a trip that only visits 3rd and 4th
    // can record exactly those. Null while waiting for that choice.
    private var armedGear: Int? = null
    private var awaitingGear = false

    private val idleThr = mutableListOf<Int>()
    private val idleRpms = mutableListOf<Float>()
    private var idleStartMs = 0L

    private var accelTriggerMs: Long? = null
    private var accelMaxThr = 0
    private var accelMaxRpm = 0f

    private var releaseStartMs: Long? = null
    private val releaseSamples = mutableListOf<Int>()

    private var ratioStartMs = 0L
    private val ratioSamples = mutableListOf<Double>()

    private var gpsStartMs = 0L
    private val gpsPairs = mutableListOf<Pair<Double, Double>>()

    init {
        if (currentStep().kind == StepKind.DONE) {
            finished = true
            setStatus(currentStep(), StepStatus.CAPTURED, "nothing left to measure")
        } else {
            awaitingGear = currentStep().kind == StepKind.GEAR
            if (!awaitingGear) setStatus(currentStep(), StepStatus.ACTIVE)
        }
    }

    private var lastHint: String = GuidedStep(StepKind.IDLE).instruction

    fun snapshot(): GuidedState = buildState(lastHint)

    /** Abandons the number this step was measuring (it stays unsaved). */
    fun skip(): GuidedState {
        if (finished) return snapshot()
        val step = currentStep()
        if (step.kind == StepKind.GEAR) {
            return if (armedGear != null) {
                // Cancel just this recording; stay on the picker so another gear
                // (or the same one again) can be chosen without losing the run.
                // A value measured earlier is kept, not thrown away by a cancel.
                val existing = results.gearRatios[step.gear] ?: savedRatioFor(step)
                setStatus(
                    step,
                    when {
                        results.gearRatios[step.gear] != null -> StepStatus.CAPTURED
                        existing != null -> StepStatus.SAVED
                        else -> StepStatus.PENDING
                    },
                    existing?.let { "${fmt1(it)} rpm/(km/h)" } ?: "",
                )
                armedGear = null
                awaitingGear = true
                resetBuffers()
                stepStartedMs = lastMs
                lastHint = "Gear ${step.gear} not recorded - pick another gear, or continue."
                snapshot()
            } else {
                // No gear armed: skipping means skipping the gear stage entirely.
                steps.filter { it.kind == StepKind.GEAR && it.gear !in results.gearRatios }
                    .forEach { setStatus(it, StepStatus.SKIPPED, "not recorded") }
                continuePastGears()
            }
        }
        setStatus(step, StepStatus.SKIPPED, "skipped")
        // A skipped save-step must not leave a half-measured number behind.
        results = when (step.kind) {
            StepKind.IDLE -> results.copy(idleThrottle = null, idleRpm = null)
            StepKind.ACCEL -> results.copy(fullThrottle = null, fullRpm = null)
            StepKind.GPS -> results.copy(gpsScale = null, gpsPairs = 0)
            else -> results
        }
        advance(lastMs)
        return snapshot()
    }

    /** Jumps to any step, e.g. to redo a value that turned out wrong. */
    fun jumpTo(step: GuidedStep): GuidedState {
        if (step.kind == StepKind.GEAR) return selectGear(step.gear)
        val target = steps.indexOf(step)
        if (target < 0) return snapshot()
        finished = false
        index = target
        armedGear = null
        awaitingGear = false
        resetBuffers()
        setStatus(step, StepStatus.ACTIVE)
        stepStartedMs = lastMs
        return snapshot()
    }

    /**
     * Arms the gear the driver is actually in. Nothing is measured until a gear
     * is chosen, which is what replaced the old fixed 1 -> 5 sequence.
     */
    fun selectGear(gear: Int): GuidedState {
        val step = steps.firstOrNull { it.kind == StepKind.GEAR && it.gear == gear }
            ?: return snapshot()
        if (finished) return snapshot()
        index = steps.indexOf(step)
        armedGear = gear
        awaitingGear = false
        resetBuffers()
        val saved = savedRatioFor(step) ?: results.gearRatios[gear]
        setStatus(
            step,
            StepStatus.ACTIVE,
            if (saved != null) "saved ${fmt1(saved)} - measuring again" else "recording...",
        )
        stepStartedMs = lastMs
        lastHint = "Gear $gear selected - hold a steady speed above " +
            "${ratioMinSpeedKph.toInt()} km/h."
        return snapshot()
    }

    /** Leaves the gear stage for the GPS step (gears are optional/resumable). */
    fun continuePastGears(): GuidedState {
        if (currentStep().kind != StepKind.GEAR) return snapshot()
        val lastGear = steps.indexOfLast { it.kind == StepKind.GEAR }
        if (lastGear >= 0) index = lastGear
        armedGear = null
        awaitingGear = false
        resetBuffers()
        advance(lastMs)
        return snapshot()
    }

    fun update(sample: GuidedSample): GuidedState {
        lastMs = sample.tMs
        // Timestamps are elapsedRealtime (device uptime), which can be days; the
        // first frame anchors the step clock instead of counting uptime as
        // elapsed time. Without this every step timed out on its first frame
        // (caught on the phone, 2026-11-06: "no steady idle after 370207s").
        if (stepStartedMs == 0L) stepStartedMs = sample.tMs
        if (finished) return snapshot()
        val step = currentStep()
        if (step.kind == StepKind.GEAR && armedGear == null) {
            // Waiting for the driver to pick a gear is not a failure and has no
            // deadline: they may spend minutes getting into 4th.
            awaitingGear = true
            lastHint = "Pick the gear you are in (1-${steps.count { it.kind == StepKind.GEAR }}). " +
                "Tap a gear to record it."
            return buildState(lastHint)
        }
        val hint = when (step.kind) {
            StepKind.IDLE -> stepIdle(sample)
            StepKind.ACCEL -> stepAccel(sample)
            StepKind.RELEASE -> stepRelease(sample)
            StepKind.BRAKE -> stepBrake(sample)
            StepKind.GEAR -> stepGear(sample, step.gear)
            StepKind.GPS -> stepGps(sample)
            StepKind.DONE -> ""
        }
        lastHint = hint
        val elapsed = max(0L, sample.tMs - stepStartedMs)
        if (!finished && currentStep() == step) {
            val limit = timeoutMs(step.kind)
            if (elapsed > limit) {
                failCurrent(step, timeoutReason(step, elapsed))
            }
        }
        return buildState(hint)
    }

    // --- steps ---------------------------------------------------------------

    private fun stepIdle(s: GuidedSample): String {
        val rpm = s.rpm
        val thr = s.throttle
        if (rpm == null || thr == null) {
            idleThr.clear()
            idleRpms.clear()
            return "No rpm or throttle on the wire yet - check the ESP is powered."
        }
        if (rpm !in idleMinRpm..idleMaxRpm) {
            idleThr.clear()
            idleRpms.clear()
            return "Waiting for idle: rpm is ${rpm.toInt()} (need $idleMinRpm-$idleMaxRpm)."
        }
        if (idleThr.isEmpty()) idleStartMs = s.tMs
        idleThr.add(thr)
        idleRpms.add(rpm)
        if (idleThr.size > 400) {
            idleThr.removeAt(0)
            idleRpms.removeAt(0)
        }
        val span = s.tMs - idleStartMs
        val spread = (idleThr.maxOrNull() ?: thr) - (idleThr.minOrNull() ?: thr)
        val rpmSpread = (idleRpms.maxOrNull() ?: rpm) - (idleRpms.minOrNull() ?: rpm)
        // A rev restarts the window instead of leaving the spread above the limit
        // forever, which would make the step sit at "capturing" and never finish.
        if (spread > idleThrottleSpread || rpmSpread > idleRpmSpread) {
            idleThr.clear()
            idleRpms.clear()
            idleStartMs = s.tMs
            return if (spread > idleThrottleSpread) {
                "Hold still: throttle is moving (${thr}, spread $spread)."
            } else {
                "Hold still: rpm is moving (${rpm.toInt()}, spread ${rpmSpread.toInt()})."
            }
        }
        setProgress(span.toFloat() / idleHoldMs)
        if (span >= idleHoldMs && idleThr.size >= idleMinSamples) {
            val thrValue = medianInt(idleThr) ?: thr
            val rpmValue = medianFloat(idleRpms).toInt()
            results = results.copy(idleThrottle = thrValue, idleRpm = rpmValue)
            captureCurrent("throttle $thrValue, rpm $rpmValue")
            advance(s.tMs)
            return currentStep().instruction
        }
        return "Idle looks steady (rpm ${rpm.toInt()}, throttle $thr) - " +
            "capturing ${span}/${idleHoldMs} ms."
    }

    private fun stepAccel(s: GuidedSample): String {
        val baseThr = results.idleThrottle ?: 0
        val baseRpm = results.idleRpm ?: 700
        if (accelTriggerMs == null) {
            val pressed = (s.throttle != null && s.throttle >= baseThr + accelRiseRaw) ||
                (s.rpm != null && s.rpm >= baseRpm + accelRiseRpm)
            if (!pressed) {
                return "Waiting: throttle ${s.throttle ?: "-"} is below ${baseThr + accelRiseRaw}" +
                    " (idle was $baseThr)."
            }
            accelTriggerMs = s.tMs
            accelMaxThr = s.throttle ?: 0
            accelMaxRpm = s.rpm ?: 0f
        }
        if (s.throttle != null) accelMaxThr = max(accelMaxThr, s.throttle)
        if (s.rpm != null) accelMaxRpm = max(accelMaxRpm, s.rpm)
        val held = s.tMs - (accelTriggerMs ?: s.tMs)
        setProgress(held.toFloat() / accelHoldMs)
        if (held >= accelHoldMs) {
            results = results.copy(fullThrottle = accelMaxThr, fullRpm = accelMaxRpm.toInt())
            captureCurrent("throttle $accelMaxThr, rpm ${accelMaxRpm.toInt()}")
            advance(s.tMs)
            return currentStep().instruction
        }
        return "Pedal seen: throttle $accelMaxThr, rpm ${accelMaxRpm.toInt()} - " +
            "hold ${held}/${accelHoldMs} ms."
    }

    private fun stepRelease(s: GuidedSample): String {
        val target = (results.idleThrottle ?: 0) + releaseSlackRaw
        val thr = s.throttle
        if (thr == null) {
            releaseStartMs = null
            releaseSamples.clear()
            return "No throttle on the wire yet."
        }
        if (thr > target) {
            releaseStartMs = null
            releaseSamples.clear()
            return "Waiting: throttle $thr - release the pedal below $target."
        }
        if (releaseStartMs == null) releaseStartMs = s.tMs
        releaseSamples.add(thr)
        val held = s.tMs - (releaseStartMs ?: s.tMs)
        setProgress(held.toFloat() / releaseHoldMs)
        if (held >= releaseHoldMs) {
            val value = medianInt(releaseSamples) ?: thr
            results = results.copy(releaseThrottle = value)
            captureCurrent("throttle $value (back to idle)")
            advance(s.tMs)
            return currentStep().instruction
        }
        return "Pedal released (throttle $thr) - confirming ${held}/${releaseHoldMs} ms."
    }

    private fun stepBrake(s: GuidedSample): String {
        if (s.brake == true) {
            results = results.copy(brakeSeen = true)
            captureCurrent("brake bit seen")
            advance(s.tMs)
            return currentStep().instruction
        }
        return if (s.brake == null) "No brake signal on the wire yet." else
            "Waiting for the brake bit to go true."
    }

    private fun stepGear(s: GuidedSample, gear: Int): String {
        val rpm = s.rpm
        val speed = s.speedKph
        if (rpm == null || speed == null) return "No speed/rpm on the wire yet (ratios need both)."
        if (speed < ratioMinSpeedKph || rpm <= 500f) {
            return "Gear $gear: need speed above ${ratioMinSpeedKph.toInt()} km/h" +
                " (now ${fmt1(speed)} km/h, rpm ${rpm.toInt()})."
        }
        if (ratioSamples.isEmpty()) ratioStartMs = s.tMs
        val ratio = rpm / speed
        ratioSamples.add(ratio)
        if (ratioSamples.size > 4000) ratioSamples.removeAt(0)
        val span = s.tMs - ratioStartMs
        setProgress(
            min(
                1f,
                min(
                    ratioSamples.size.toFloat() / ratioMinSamples,
                    span.toFloat() / ratioHoldMs,
                ),
            )
        )
        if (ratioSamples.size >= ratioMinSamples && span >= ratioHoldMs) {
            val value = medianDouble(ratioSamples) ?: ratio
            // Gears are steadier than frames: reject a window that wandered, it
            // means the driver was shifting or the speed signal was jumping.
            val spread = (ratioSamples.maxOrNull() ?: value) - (ratioSamples.minOrNull() ?: value)
            if (spread > value * 0.25) {
                ratioSamples.clear()
                ratioStartMs = s.tMs
                return "Gear $gear: ratios wandered ${fmt1(spread)} - hold a steady speed."
            }
            results = results.copy(gearRatios = results.gearRatios + (gear to value))
            captureCurrent("${fmt1(value)} rpm/(km/h)")
            // Stay on the gear stage: the next gear is chosen by the driver, not
            // assumed to be gear+1.
            armedGear = null
            awaitingGear = true
            resetBuffers()
            stepStartedMs = s.tMs
            lastHint = "Gear $gear saved (${fmt1(value)}). Pick another gear, or continue to GPS."
            return lastHint
        }
        return "Gear $gear: ratio ${fmt1(ratio)} rpm/(km/h) - " +
            "${ratioSamples.size}/$ratioMinSamples samples, ${span}/${ratioHoldMs} ms."
    }

    private fun stepGps(s: GuidedSample): String {
        val can = s.speedKph
        val gps = s.gpsKph
        if (gps == null) return "Waiting for a GPS fix (needs the phone's GNSS)."
        if (can == null) return "No CAN speed on the wire yet."
        if (can < gpsMinSpeedKph || gps < 15.0) {
            return "Drive above ${gpsMinSpeedKph.toInt()} km/h" +
                " (CAN ${fmt1(can)}, GPS ${fmt1(gps)})."
        }
        if (gpsPairs.isEmpty()) gpsStartMs = s.tMs
        gpsPairs.add(can to gps)
        if (gpsPairs.size > 4000) gpsPairs.removeAt(0)
        val span = s.tMs - gpsStartMs
        setProgress(
            min(
                1f,
                min(
                    gpsPairs.size.toFloat() / gpsMinPairs,
                    span.toFloat() / gpsHoldMs,
                ),
            )
        )
        if (gpsPairs.size >= gpsMinPairs && span >= gpsHoldMs) {
            val scale = medianDouble(gpsPairs.map { it.second / it.first })
            if (scale == null || scale < gpsMinScale || scale > gpsMaxScale) {
                failCurrent(
                    currentStep(),
                    "scale ${scale?.let { fmt1(it) } ?: "-"} is outside " +
                        "$gpsMinScale-$gpsMaxScale - is CAN speed plausible?",
                )
                return currentStep().instruction
            }
            results = results.copy(gpsScale = scale, gpsPairs = gpsPairs.size)
            captureCurrent("${fmt2(scale)} (CAN ${fmt1(can)} vs GPS ${fmt1(gps)} km/h)")
            advance(s.tMs)
            return currentStep().instruction
        }
        return "GPS ${fmt1(gps)} vs CAN ${fmt1(can)} km/h - " +
            "${gpsPairs.size}/$gpsMinPairs pairs, ${span}/$gpsHoldMs ms."
    }

    // --- bookkeeping ---------------------------------------------------------

    private fun currentStep(): GuidedStep = steps[index]

    private fun firstPendingIndex(): Int =
        views.indexOfFirst { it.status == StepStatus.PENDING }.let { if (it < 0) steps.size - 1 else it }

    private fun savedRatioFor(step: GuidedStep): Double? {
        if (step.kind != StepKind.GEAR) return null
        val value = savedRatios.getOrNull(step.gear - 1) ?: return null
        return value.takeIf { it > 0.0 }
    }

    private fun setStatus(step: GuidedStep, status: StepStatus, detail: String = "") {
        val i = steps.indexOf(step)
        if (i < 0) return
        views[i] = StepView(step, status, detail)
    }

    private fun captureCurrent(detail: String) {
        setStatus(currentStep(), StepStatus.CAPTURED, detail)
    }

    private fun failCurrent(step: GuidedStep, reason: String) {
        setStatus(step, StepStatus.FAILED, reason)
        advance(lastMs)
    }

    /** Moves to the next step that is not already saved, or DONE. */
    private fun advance(atMs: Long) {
        resetBuffers()
        var next = index + 1
        while (next < steps.size - 1 && views[next].status == StepStatus.SAVED) next++
        index = next.coerceAtMost(steps.size - 1)
        stepStartedMs = atMs
        if (currentStep().kind == StepKind.DONE) {
            finished = true
            setStatus(currentStep(), StepStatus.CAPTURED, "")
        } else if (currentStep().kind == StepKind.GEAR) {
            // The gear stage waits for a tap; no chip is highlighted until one
            // is chosen, and a timed-out gear must not stay armed.
            awaitingGear = true
            armedGear = null
        } else {
            awaitingGear = false
            armedGear = null
            setStatus(currentStep(), StepStatus.ACTIVE)
        }
    }

    private fun resetBuffers() {
        progress = 0f
        idleThr.clear()
        idleRpms.clear()
        idleStartMs = 0L
        accelTriggerMs = null
        accelMaxThr = 0
        accelMaxRpm = 0f
        releaseStartMs = null
        releaseSamples.clear()
        ratioSamples.clear()
        ratioStartMs = 0L
        gpsPairs.clear()
        gpsStartMs = 0L
    }

    private fun setProgress(value: Float) {
        // Progress is read through buildState; keep it on the wizard so the last
        // measured value survives until the next frame.
        progress = value.coerceIn(0f, 1f)
    }

    private var progress = 0f

    private fun buildState(hint: String): GuidedState {
        val current = if (finished) GuidedStep(StepKind.DONE) else currentStep()
        val elapsed = max(0L, lastMs - stepStartedMs)
        val choosingGear = current.kind == StepKind.GEAR && armedGear == null
        return GuidedState(
            current = current,
            views = views.toList(),
            progress = progress,
            elapsedMs = elapsed,
            hint = hint,
            stalled = !finished && !choosingGear && elapsed > timeoutMs(current.kind),
            finished = finished,
            results = results,
            awaitingGearChoice = !finished && choosingGear,
            selectedGear = armedGear,
        )
    }

    private fun timeoutMs(kind: StepKind): Long = when (kind) {
        StepKind.IDLE -> 30_000
        StepKind.ACCEL -> 45_000
        StepKind.RELEASE -> 45_000
        StepKind.BRAKE -> 60_000
        StepKind.GEAR -> 180_000
        StepKind.GPS -> 180_000
        StepKind.DONE -> 0
    }

    private fun timeoutReason(step: GuidedStep, elapsedMs: Long): String = when (step.kind) {
        StepKind.IDLE -> "no steady idle after ${elapsedMs / 1000}s - is the engine running?"
        StepKind.ACCEL -> "no pedal press after ${elapsedMs / 1000}s"
        StepKind.RELEASE -> "the pedal never returned to idle (${elapsedMs / 1000}s)"
        StepKind.BRAKE -> "no brake bit after ${elapsedMs / 1000}s - press the pedal again"
        StepKind.GEAR -> "speed never reached 20 km/h in gear ${step.gear}"
        StepKind.GPS -> "no GPS/CAN pairs after ${elapsedMs / 1000}s"
        StepKind.DONE -> ""
    }

    private fun medianInt(values: List<Int>): Int? =
        values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    private fun medianFloat(values: List<Float>): Float =
        values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] } ?: 0f

    private fun medianDouble(values: List<Double>): Double? =
        values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    private fun fmt1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private fun fmt2(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
