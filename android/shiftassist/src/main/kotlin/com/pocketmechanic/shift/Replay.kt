package com.pocketmechanic.shift

import java.io.File
import kotlin.system.exitProcess

/**
 * Offline replay harness: reads telemetry JSONL (schema v1, one record per
 * 100 ms bucket, as produced by tools/savvycan_to_jsonl.py) and runs it through
 * [ShiftAssistant] and [TripAccountant] exactly as the app would.
 *
 * Usage: gradle :shiftassist:replay -Pjsonl=/path/to/replay_b.jsonl
 *
 * Flags (all optional):
 *   --mode eco|normal|sport   drive mode for the cue thresholds
 *   --shadow                  run all three profiles and render none
 *   --cues-out FILE           write one cue-log row per profile per frame
 *   --blank MS                gear-change blank (default 1000; 0 disables)
 *   --scale 0.893             GPS speed scale, applied once like TripRecorder
 *   --redline 6100            redline the trip is scored/cued against
 *   --confirmed false         treat the redline as unconfirmed (no over-rev)
 *   --idle 31 --full 219      calibrated throttle raw values
 */

/**
 * Rounded rpm-per-km/h ladder from the 2026-11-09 calibration (g1 147.2,
 * g2 79.3, g3 53.3, g4 39.8, g5 32.3), captured with gpsSpeedScale 0.893. The
 * handset stores the six-figure capture (147.223 / 79.300 / 53.279 / 39.775 /
 * 32.329); this constant is the same ladder rounded, kept so the harness uses
 * one fixed input for every run, old and new. Cross-checked against the owner's
 * manual's downshift maxima (2->1 40, 3->2 80, 4->3 120, 5->4 155 km/h), which
 * agree to ~1%.
 */
val CELERIO_5_SPEED_RATIOS = listOf(147.2, 79.3, 53.3, 39.8, 32.3)

private fun field(line: String, key: String): String? {
    val needle = "\"$key\":"
    val i = line.indexOf(needle)
    if (i < 0) return null
    val start = i + needle.length
    var end = start
    while (end < line.length && line[end] != ',' && line[end] != '}') end++
    return line.substring(start, end).trim()
}

private fun numOrNull(v: String?): Double? =
    if (v == null || v == "null") null else v.toDoubleOrNull()

private fun intOrNull(v: String?): Int? = numOrNull(v)?.toInt()

private fun longOrNull(v: String?): Long? = numOrNull(v)?.toLong()

private fun boolOrNull(v: String?): Boolean? = when (v) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun opt(args: Array<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

fun main(args: Array<String>) {
    val path = args.firstOrNull { !it.startsWith("--") } ?: run {
        System.err.println("usage: Replay <telemetry.jsonl> [--mode eco|normal|sport] [--shadow]")
        exitProcess(2)
    }
    val mode = DriveMode.of(opt(args, "--mode"))
    val shadow = args.contains("--shadow")
    val cuesOut = opt(args, "--cues-out")
    val blankMs = opt(args, "--blank")?.toLongOrNull() ?: 1000L
    val scale = opt(args, "--scale")?.toDoubleOrNull() ?: 1.0
    val redline = opt(args, "--redline")?.toIntOrNull() ?: 6400
    val confirmed = opt(args, "--confirmed")?.toBooleanStrictOrNull() ?: true
    val idle = opt(args, "--idle")?.toIntOrNull() ?: -1
    val full = opt(args, "--full")?.toIntOrNull() ?: 255

    val file = File(path)
    if (!file.isFile) {
        System.err.println("no such file: $path")
        exitProcess(2)
    }

    val cfg = Config(
        redlineRpm = redline,
        redlineConfirmed = confirmed,
        throttleIdleRaw = idle,
        throttleFullRaw = full,
        gearChangeBlankMs = blankMs,
    )
    val bank = AssistantBank(
        cfg,
        GearEstimator(CELERIO_5_SPEED_RATIOS),
        if (shadow) AssistantMode.SHADOW else AssistantMode.of(mode.name),
    )
    // In shadow nothing is visible, so the trip/gear statistics are reported
    // for NORMAL - the profile the app would otherwise be running.
    val reportProfile = if (shadow) DriveMode.NORMAL else mode
    val accountant = TripAccountant(redline)

    var records = 0
    var firstMs: Long? = null
    var lastMs = 0L
    var lastCanActiveMs = 0L
    var lastMeaningfulMs: Long? = null
    var meaningfulFrames = 0

    // up / down / over_rev, one row per profile that ran.
    val cues = DriveMode.entries.associateWith { IntArray(3) }
    val lastCue = HashMap<DriveMode, Cue?>()
    var overRevSamples = 0
    var gearKnown = 0
    val gearHistogram = IntArray(CELERIO_5_SPEED_RATIOS.size + 1)
    val upPerGear = IntArray(CELERIO_5_SPEED_RATIOS.size + 1)
    val examples = mutableListOf<String>()
    val cueFile = cuesOut?.let { File(it).bufferedWriter() }
    cueFile?.apply {
        write(CueRow.HEADER)
        newLine()
    }

    file.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        records++
        val tMs = longOrNull(field(line, "t_ms")) ?: 0L
        val rawSpeed = numOrNull(field(line, "speed_kph"))
        val speed = SpeedScale.apply(rawSpeed?.toFloat(), scale)?.toDouble()
        val rpm = intOrNull(field(line, "rpm"))
        val brake = boolOrNull(field(line, "brake"))
        val throttle = intOrNull(field(line, "thr1"))
        val canActive = boolOrNull(field(line, "can_active")) ?: true
        val engine = boolOrNull(field(line, "engine_running"))

        if (firstMs == null) firstMs = tMs
        lastMs = tMs
        if (canActive) lastCanActiveMs = tMs
        // TripRecorder.isMeaningful: CAN live, engine not off, rpm above zero.
        if (canActive && engine != false && (rpm ?: 0) > 0) {
            lastMeaningfulMs = tMs
            meaningfulFrames++
        }

        accountant.update(
            TripFrame(
                tMs = tMs,
                speedKph = speed,
                rpm = rpm,
                canActive = canActive,
                engineRunning = engine,
                distRaw = longOrNull(field(line, "dist_raw")),
                brake = brake,
            )
        )

        val decision = bank.update(
            Sample(
                tMs = tMs,
                rpm = rpm,
                speedKph = speed,
                brake = brake,
                throttle = throttle,
                canActive = canActive,
                engineRunning = engine,
            )
        )
        val visible = decision.visible
        val stats = decision.outputs[reportProfile] ?: visible
        stats?.let {
            if (it.gear != null) {
                gearKnown++
                gearHistogram[it.gear]++
            }
            if (it.overRev) overRevSamples++
        }

        for ((profile, out) in decision.outputs) {
            if (out.cue != null && out.cue != lastCue[profile]) {
                val slot = when (out.cue) {
                    Cue.UP -> 0
                    Cue.DOWN -> 1
                    Cue.OVER_REV -> 2
                }
                cues.getValue(profile)[slot]++
                if (out.cue == Cue.UP && out.gear != null && profile == reportProfile) {
                    upPerGear[out.gear]++
                }
                if (profile == reportProfile && examples.size < 12) {
                    val t = ((tMs - (firstMs ?: tMs)) / 1000.0)
                    examples += "  t=%7.1fs gear=%s rpm=%-5s speed=%-5s cue=%-8s %s".format(
                        t, out.gear ?: "G", rpm?.toString() ?: "-",
                        speed?.let { "%.1f".format(it) } ?: "-", out.cue, out.reason
                    )
                }
            }
            lastCue[profile] = out.cue
            cueFile?.apply {
                write(
                    cueRow(
                        // A replay has no phone clocks. The frame timestamp is the
                        // only honest time; the wall column stays 0 (unknown).
                        tMonoMs = tMs,
                        tWallMs = 0L,
                        frameTMs = tMs,
                        output = out,
                        mode = profile,
                        lastCueAgeMs = bank.assistantFor(profile)?.lastCueAtMs?.let { tMs - it },
                        visible = visible != null,
                        audioEnabled = true,
                    ).csv()
                )
                newLine()
            }
        }
    }
    cueFile?.flush()
    cueFile?.close()

    val start = firstMs ?: 0L
    val meaningfulEnd = lastMeaningfulMs
    val rawSpan = lastMs - start
    val endMs = meaningfulEnd ?: lastMs
    val newSpan = endMs - start
    val summary = accountant.finish(endMs)

    println("replay: $path")
    println("REPLAY ON OLD DRIVING, NOT DRIVER RESPONSE")
    println(
        "mode=${if (shadow) "shadow" else mode.name.lowercase()} scale=$scale redline=$redline " +
            "confirmed=$confirmed blank=${blankMs}ms " +
            "throttle=${if (idle >= 0) "$idle..$full" else "uncalibrated"}"
    )
    println("records: $records")

    println()
    println("--- trip (P0) -------------------------------------------------")
    println("raw span: %.1f s (last frame - first frame)".format(rawSpan / 1000.0))
    println(
        "end rule: %.1f s (%s), ignored tail %.1f s / %d frames".format(
            newSpan / 1000.0,
            if (meaningfulEnd == null) "no meaningful frame, last frame" else "last meaningful frame",
            (rawSpan - newSpan) / 1000.0,
            records - meaningfulFrames,
        )
    )
    println("duration:      %.1f s".format(summary.durationMs / 1000.0))
    println("distance:      %.3f km".format(summary.distanceKm))
    println("max speed:     %.1f km/h".format(summary.maxSpeedKph))
    println("max rpm:       ${summary.maxRpm}  avg ${summary.avgRpm}")
    println("moving/idle:   %.1f s / %.1f s".format(summary.movingMs / 1000.0, summary.idleMs / 1000.0))
    println("over-rev:      %.1f s".format(summary.overRevMs / 1000.0))
    println("hard accels:   ${summary.hardAccelCount}")
    println("dist_raw:      delta ${summary.distRawDelta ?: "n/a"}, " +
        "ratio ${summary.distRawRatio?.let { "%.4f".format(it) } ?: "n/a"}")
    println("eco score:     ${summary.ecoScore}")

    println()
    println("--- assistant (P1) --------------------------------------------")
    if (shadow) {
        println("gear known: $gearKnown (${"%.1f".format(100.0 * gearKnown / maxOf(records, 1))}%)")
        println("gear histogram: " + (1..CELERIO_5_SPEED_RATIOS.size)
            .joinToString(" ") { "g$it=${gearHistogram[it]}" })
        println("cues per profile (shadow: every frame ran all three):")
        println("  profile   up  down  over_rev")
        for (profile in DriveMode.entries) {
            val c = cues.getValue(profile)
            println("  %-8s %4d %5d %9d".format(profile.name.lowercase(), c[0], c[1], c[2]))
        }
        println("ups per gear: " + (1 until upPerGear.size).joinToString(" ") { "g$it=${upPerGear[it]}" })
    } else {
        println("gear known: $gearKnown (${"%.1f".format(100.0 * gearKnown / maxOf(records, 1))}%)")
        println("gear histogram: " + (1..CELERIO_5_SPEED_RATIOS.size)
            .joinToString(" ") { "g$it=${gearHistogram[it]}" })
        val c = cues.getValue(reportProfile)
        println("cues: up=${c[0]} down=${c[1]} over_rev=${c[2]}")
        println("ups per gear: " + (1 until upPerGear.size).joinToString(" ") { "g$it=${upPerGear[it]}" })
    }
    println("over-rev samples: $overRevSamples")
    if (cuesOut != null) println("cue log: $cuesOut (t_wall_ms is 0: a replay has no wall clock)")
    if (examples.isNotEmpty()) {
        println("first cues:")
        examples.forEach(::println)
    }
}
