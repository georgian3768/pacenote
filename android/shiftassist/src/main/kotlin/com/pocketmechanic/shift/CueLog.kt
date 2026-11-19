package com.pocketmechanic.shift

/**
 * Why a frame did not produce a cue. [NONE] means either "a cue was emitted" or
 * "no rule applied to this frame" - the two are distinguished by `emitted`.
 *
 * [COAST] is part of the agreed vocabulary but is never produced: downshift
 * cues are allowed while coasting (and while braking), so the current rule has
 * no coast suppression to report. It is kept so a log parser can accept the
 * token without a version bump.
 */
enum class SuppressedBy(val wire: String) {
    NONE("none"),
    GAP("gap"),
    ON_TIME("on_time"),
    GEAR_NULL("gear_null"),
    BRAKE("brake"),
    NOT_ACCELERATING("not_accelerating"),
    ACCELERATING("accelerating"),
    COAST("coast"),
    OVER_REV("over_rev"),
    BLANK("blank"),
}

/**
 * One row of the frame-level cue log: the decision [ShiftAssistant] took for one
 * profile on one frame, with the inputs it took it from.
 *
 * This is a *decision* log, not an event log: it is written for every frame so a
 * missing cue can be explained after the drive. It deliberately does not go
 * through the app's ring-buffer EventLog, which drops the oldest lines and
 * cannot be reconstructed.
 *
 * Clock order is fixed: monotonic first ([tMonoMs], `SystemClock.elapsedRealtime`
 * in the app), wall clock second ([tWallMs]). The monotonic value is the one to
 * use for intervals; the wall clock is only for matching a drive to a time of
 * day.
 */
data class CueRow(
    val tMonoMs: Long,
    val tWallMs: Long,
    val frameTMs: Long,
    val mode: DriveMode,
    val gearRaw: Int?,
    val gearSmoothed: Int?,
    val rpm: Int?,
    val speedTrueKph: Double?,
    val brake: Boolean?,
    val accelMps2: Double?,
    val rpmRise: Double?,
    val upThr: Int?,
    val downThr: Int?,
    val emitted: Boolean,
    val suppressedBy: SuppressedBy,
    val lastCueAgeMs: Long?,
    val visible: Boolean,
    val audio: Boolean,
) {
    fun csv(): String = listOf(
        tMonoMs.toString(),
        tWallMs.toString(),
        frameTMs.toString(),
        mode.name.lowercase(),
        gearRaw?.toString() ?: "",
        gearSmoothed?.toString() ?: "",
        rpm?.toString() ?: "",
        speedTrueKph?.let { "%.2f".format(it) } ?: "",
        brake?.let { if (it) "1" else "0" } ?: "",
        accelMps2?.let { "%.3f".format(it) } ?: "",
        rpmRise?.let { "%.1f".format(it) } ?: "",
        upThr?.toString() ?: "",
        downThr?.toString() ?: "",
        if (emitted) "1" else "0",
        suppressedBy.wire,
        lastCueAgeMs?.toString() ?: "",
        if (visible) "1" else "0",
        if (audio) "1" else "0",
    ).joinToString(",")

    companion object {
        const val HEADER =
            "t_mono_ms,t_wall_ms,frame_t_ms,mode,gear_raw,gear_smoothed,rpm," +
                "speed_true_kph,brake,accel_mps2,rpm_rise,up_thr,down_thr,emitted," +
                "suppressed_by,last_cue_age_ms,visible,audio"
    }
}

/**
 * Builds the row for one profile from the frame it saw and the [Output] it
 * produced. Visible rows are the selected profile (never in SHADOW); audio rows
 * are visible rows whose cue the app was asked to play.
 */
fun cueRow(
    tMonoMs: Long,
    tWallMs: Long,
    frameTMs: Long,
    output: Output,
    mode: DriveMode,
    lastCueAgeMs: Long?,
    visible: Boolean,
    audioEnabled: Boolean,
): CueRow = CueRow(
    tMonoMs = tMonoMs,
    tWallMs = tWallMs,
    frameTMs = frameTMs,
    mode = mode,
    gearRaw = output.rawGear,
    gearSmoothed = output.gear,
    rpm = output.rpm,
    speedTrueKph = output.speedKph,
    brake = output.braking,
    accelMps2 = output.accelMps2,
    rpmRise = output.rpmRise,
    upThr = output.upRpm,
    downThr = output.downRpm,
    emitted = output.cue != null,
    suppressedBy = output.suppressedBy,
    lastCueAgeMs = lastCueAgeMs,
    visible = visible,
    audio = visible && audioEnabled && output.cue != null,
)
