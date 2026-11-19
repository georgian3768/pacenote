package com.pocketmechanic.spike

import android.os.SystemClock
import com.pocketmechanic.shift.AssistantBank
import com.pocketmechanic.shift.AssistantMode
import com.pocketmechanic.shift.Config
import com.pocketmechanic.shift.GearEstimator
import com.pocketmechanic.shift.Output
import com.pocketmechanic.shift.Sample
import com.pocketmechanic.shift.SpeedScale
import com.pocketmechanic.shift.cueRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges telemetry to the pure shift-assistant module.
 *
 * The module (Gradle `:shiftassist`) is deliberately free of Android code and
 * covered by table-driven JUnit tests; this class is the only place that knows
 * about [Telemetry], [AppSettings] and the process lifetime. Both the dashboard
 * bridge and the overlay read [output], so the two surfaces can never disagree
 * about the current gear or cue.
 *
 * This is also where the frame-level cue log gets its clocks and its file: the
 * shift module decides, this class timestamps (monotonic first, wall clock
 * second) and appends one row per profile per frame on the stream thread.
 */
object AssistantHost {

    /**
     * Fallback only: the ratios measured by the gear wizard replace these. The
     * wizard's 2026-11-09 capture is 147.223 / 79.300 / 53.279 / 39.775 /
     * 32.329 rpm per km/h at gpsSpeedScale 0.893; these are the same values
     * rounded to 3 significant figures, so an install with no stored ladder
     * still estimates gears (and the replay harness has a stable input).
     */
    private val DEFAULT_RATIOS = listOf(147.2, 79.3, 53.3, 39.8, 32.3)

    @Volatile
    var output: Output? = null
        private set

    private var bank: AssistantBank? = null
    private var key: String = ""

    /**
     * True while the assistant is in SHADOW: all three profiles run and the cue
     * log fills, but nothing may be drawn or played.
     */
    @Volatile
    var shadow: Boolean = false
        private set

    private val _mode = MutableStateFlow(AssistantMode.DEFAULT)

    /**
     * The selected mode as the UI sees it.
     *
     * The panel chip used to keep its own `remember` copy of the setting, which
     * meant a mode changed anywhere else (the dev intent, the landing screen, a
     * future settings row) left the chip showing a profile the assistant was no
     * longer running. Compose reads this flow instead, and [setMode] is the only
     * writer, so every surface agrees with the bank that produces the cues.
     */
    val mode: StateFlow<AssistantMode> = _mode.asStateFlow()

    /**
     * Rebuilds the assistant when the profile changes. Cheap enough to call on
     * every frame: it compares a small key and returns immediately.
     */
    /**
     * Adopt the stored mode for the UI without rebuilding the bank.
     *
     * The chip reads [mode], and a fresh process has no frame to run
     * [configure] for a while - on the bench there may never be one - so the
     * surface would show NORMAL while the car is set to ZOOM. Both hosts call
     * this before their first composition.
     */
    fun syncMode(settings: AppSettings) {
        _mode.value = settings.assistantMode
        shadow = settings.assistantMode.shadow
    }

    fun configure(settings: AppSettings) {
        // The UI's copy of the mode follows the setting on every frame, so a
        // change made anywhere (dev intent, landing row) reaches the chip even
        // if that writer never touched this object. StateFlow ignores a write
        // of the same value, so this is free.
        _mode.value = settings.assistantMode
        val car = settings.car
        val ratios = settings.gearRatios
        val k = listOf(
            car.redlineRpm, car.idleRpm, car.gearCount, settings.assistantMode.name,
            ratios.joinToString(","), settings.throttleFullRaw, settings.throttleIdleRaw,
            settings.minSpeedKph,
            settings.gpsSpeedScale,
            settings.redlineTrusted
        ).joinToString("|")
        if (k == key && bank != null) return
        key = k
        val cfg = Config(
            redlineRpm = car.redlineRpm,
            redlineConfirmed = settings.redlineTrusted,
            idleRpm = car.idleRpm,
            gearCount = car.gearCount,
            minSpeedKph = settings.minSpeedKph,
            throttleFullRaw = settings.throttleFullRaw,
            throttleIdleRaw = settings.throttleIdleRaw,
        )
        val usable = if (ratios.size >= 3) ratios else DEFAULT_RATIOS.take(car.gearCount)
        bank = AssistantBank(cfg, GearEstimator(usable), settings.assistantMode)
        shadow = settings.assistantMode.shadow
        output = null
        EventLog.add(
            "shift",
            "assistant configured: gears=${car.gearCount} redline=${car.redlineRpm} " +
                "confirmed=${settings.redlineTrusted} " +
                "mode=${settings.assistantMode} ratios=${usable.joinToString(",")} " +
                "throttle=${settings.throttleIdleRaw}/${settings.throttleFullRaw} " +
                "gpsScale=${settings.gpsSpeedScale}"
        )
    }

    /**
     * Feeds one frame. `warm` is always true for now: coolant temperature is
     * captured raw and uncalibrated (0x124 D4-D5), so a warm-up gate would be
     * guesswork. TODO-tune once the temperature scale is confirmed on a drive.
     */
    fun onFrame(frame: Telemetry, settings: AppSettings): Output? {
        configure(settings)
        val a = bank ?: return null
        // Clock order is part of the log format: monotonic first, wall second.
        val tMono = SystemClock.elapsedRealtime()
        val tWall = System.currentTimeMillis()
        // The GPS wizard's promise is "this multiplies CAN speed"; until
        // 2026-11-05 the value was stored and never read, so calibrating it
        // changed nothing (found while diagnosing "gears not updating").
        val speedKph = SpeedScale.apply(frame.speedKph, settings.gpsSpeedScale)
        val decision = a.update(
            Sample(
                tMs = frame.tMs,
                rpm = frame.rpm?.toInt(),
                speedKph = speedKph,
                brake = frame.brake,
                throttle = frame.thr1,
                canActive = frame.canActive,
                engineRunning = frame.engineRunning,
            ),
            warm = true
        )

        val visibleMode = decision.mode.profile
        for ((mode, out) in decision.outputs) {
            val age = a.assistantFor(mode)?.lastCueAtMs?.let { frame.tMs - it }
            CueLogFile.write(
                cueRow(
                    tMonoMs = tMono,
                    tWallMs = tWall,
                    frameTMs = frame.tMs,
                    output = out,
                    mode = mode,
                    lastCueAgeMs = age,
                    visible = decision.visible != null && mode == visibleMode,
                    audioEnabled = settings.audioCues,
                )
            )
        }

        val visible = decision.visible
        output = visible
        visible?.cue?.let { cue ->
            // One structured line per cue so the drive log can be checked
            // afterwards without guessing which threshold fired.
            EventLog.add(
                "cue",
                "[cue] ${cue.name.lowercase()} gear=${visible.gear ?: "G"} " +
                    "rpm=${frame.rpm?.toInt() ?: "-"} speed=${speedKph?.let { "%.1f".format(it) } ?: "-"} " +
                    "up=${visible.upRpm ?: "-"} down=${visible.downRpm ?: "-"} " +
                    "accel=${visible.accelMps2?.let { "%.2f".format(it) } ?: "-"} " +
                    "mode=${visible.mode.name.lowercase()} ${visible.reason}"
            )
        }
        return visible
    }

    fun reset() {
        bank?.reset()
        output = null
    }

    /** Dev action: switch profiles at runtime, including SHADOW. */
    fun setMode(mode: AssistantMode, settings: AppSettings) {
        settings.assistantMode = mode
        _mode.value = mode
        shadow = mode.shadow
        output = null
        EventLog.add("shift", "assistant mode -> ${mode.name.lowercase()}")
    }
}
