package com.pocketmechanic.spike

import org.json.JSONObject

/**
 * One schema-v1 telemetry record from the ESP32.
 *
 * The 10 Hz "f" event omits the slow fields, so parsing merges with the previous
 * record instead of blanking them: a widget must not flicker to "unknown" ten
 * times a second because of the event cadence.
 */
data class Telemetry(
    val seq: Long,
    val tMs: Long,
    val rpm: Float?,
    val speedKph: Float?,
    val brake: Boolean?,
    val thr1: Int?,
    val thr2: Int?,
    val engineRunning: Boolean?,
    val canActive: Boolean,
    val tempRaw: Int?,
    val distRaw: Int?,
    val volt: Float?
) {
    companion object {
        fun parse(json: String, previous: Telemetry?): Telemetry {
            val o = JSONObject(json)
            fun float(key: String): Float? =
                if (o.isNull(key)) null else o.optDouble(key).toFloat()
            fun int(key: String, fallback: Int?): Int? =
                if (o.isNull(key)) fallback else o.optInt(key)
            fun bool(key: String): Boolean? =
                if (o.isNull(key)) null else o.optBoolean(key)

            return Telemetry(
                seq = o.optLong("seq"),
                tMs = o.optLong("t_ms"),
                rpm = float("rpm"),
                speedKph = float("speed_kph"),
                brake = bool("brake"),
                thr1 = int("thr1", previous?.thr1),
                thr2 = int("thr2", previous?.thr2),
                engineRunning = bool("engine_running"),
                canActive = if (o.isNull("can_active")) false else o.optBoolean("can_active"),
                tempRaw = int("temp_raw", previous?.tempRaw),
                distRaw = int("dist_raw", previous?.distRaw),
                volt = if (o.isNull("volt")) previous?.volt else float("volt")
            )
        }
    }
}

/** What the spike screen shows about the link, independent of telemetry. */
data class LinkState(
    val defaultTransport: String = "-",
    val wifiValidated: Boolean? = null,
    val espNetwork: String = "-",
    val reachable: String = "-",
    val lastError: String? = null
)

/**
 * Stream statistics. Phase 0 asks for frame rate, sequence gaps, jitter
 * (mean/p95/max) and reconnects, so those are the only things tracked.
 */
class StreamStats {
    data class Snapshot(
        val frames: Long = 0,
        val gaps: Long = 0,
        val reconnects: Int = 0,
        val fps: Double = 0.0,
        val jitterMeanMs: Double = 0.0,
        val jitterP95Ms: Long = 0,
        val jitterMaxMs: Long = 0,
        val lastSeq: Long = -1,
        val ageMs: Long = -1
    )

    private var frames = 0L
    private var gaps = 0L
    private var reconnects = 0
    private var lastSeq = -1L
    private var lastArrivalMs = -1L
    private var previousArrivalMs = -1L

    private val jitter = LongArray(200)
    private var jitterCount = 0
    private var jitterIndex = 0

    private val arrivals = ArrayDeque<Long>()

    @Synchronized
    fun onFrame(seq: Long, elapsedMs: Long) {
        frames++
        if (lastSeq >= 0 && seq > lastSeq + 1) gaps += seq - lastSeq - 1
        lastSeq = seq

        if (previousArrivalMs >= 0) {
            jitter[jitterIndex] = elapsedMs - previousArrivalMs
            jitterIndex = (jitterIndex + 1) % jitter.size
            if (jitterCount < jitter.size) jitterCount++
        }
        previousArrivalMs = elapsedMs
        lastArrivalMs = elapsedMs

        arrivals.addLast(elapsedMs)
        while (arrivals.isNotEmpty() && arrivals.first() < elapsedMs - 1000) arrivals.removeFirst()
    }

    @Synchronized
    fun onReconnect() {
        reconnects++
        previousArrivalMs = -1L
    }

    @Synchronized
    fun snapshot(nowMs: Long): Snapshot {
        val deltas = LongArray(jitterCount) { jitter[it] }
        deltas.sort()
        val mean = if (deltas.isEmpty()) 0.0 else deltas.average()
        val p95 = if (deltas.isEmpty()) 0L else deltas[((deltas.size - 1) * 0.95).toInt()]
        return Snapshot(
            frames = frames,
            gaps = gaps,
            reconnects = reconnects,
            fps = arrivals.size.toDouble(),
            jitterMeanMs = mean,
            jitterP95Ms = p95,
            jitterMaxMs = deltas.lastOrNull() ?: 0L,
            lastSeq = lastSeq,
            ageMs = if (lastArrivalMs < 0) -1 else nowMs - lastArrivalMs
        )
    }
}
