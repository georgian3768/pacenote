package com.pocketmechanic.spike

import android.net.Network
import android.os.SystemClock
import com.pocketmechanic.shift.GvretCaptureLog
import com.pocketmechanic.shift.GvretText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raw capture of the ESP32's text GVRET stream, for one question: what does the
 * bus do when the driver selects reverse?
 *
 * Deliberately not part of the SSE path. The service stream is decoded
 * telemetry; this is the undecoded wire, and it has to keep working even when a
 * decoder is what needs fixing. It opens the firmware's telnet port (23) on the
 * ESP's own network, sends one keepalive so the firmware starts filling the
 * telnet buffer, and stores every parsed frame in arrival order with the phase
 * markers the analysis script splits on.
 *
 * Listen-only, like the rest of the app: the keepalive is the only byte written,
 * and it is a GVRET command, not a CAN frame.
 */
class ReverseCapture(private val scope: CoroutineScope) {

    enum class Status { IDLE, CONNECTING, STREAMING, STOPPED, ERROR }

    data class Result(
        val name: String,
        val csv: String,
        val frames: Int,
        val dropped: Int,
        val reverseMarked: Boolean,
        val durationS: Double,
    )

    companion object {
        private const val PORT = 23
        private const val CONNECT_TIMEOUT_MS = 4_000

        /**
         * A quiet bus is normal (key off, engine not started); the socket is kept
         * open and the read retried rather than treating silence as a failure.
         */
        private const val READ_TIMEOUT_MS = 10_000

        /** GVRET PROTO_KEEPALIVE; also the first client byte, which arms telnet. */
        private val KEEPALIVE = byteArrayOf(0xF1.toByte(), 0x09)
    }

    @Volatile
    var status = Status.IDLE
        private set

    @Volatile
    var frames = 0
        private set

    @Volatile
    var dropped = 0
        private set

    /** `"N"` before the driver marks reverse, `"R"` after. */
    @Volatile
    var phase = "N"
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var truncated = false
        private set

    @Volatile
    var startedElapsed = 0L
        private set

    @Volatile
    var reverseElapsed = -1L
        private set

    @Volatile
    var lastFrameElapsed = -1L
        private set

    private var job: Job? = null

    @Volatile
    private var socket: Socket? = null

    private var log: GvretCaptureLog? = null
    private var fileName = "reverse_capture.csv"

    /** Starts a capture; null on success, otherwise why it could not. */
    fun start(): String? {
        if (job?.isActive == true) return "a capture is already running"
        val network = WifiController.espNetwork
            ?: return "not on the ESP32 Wi-Fi network - join A0RETSSID first"

        val startedAt = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAt))
        fileName = "reverse_capture_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt)) + ".csv"

        val captureLog = GvretCaptureLog(
            listOf(
                "Pacenote reverse capture",
                "started $stamp",
                "source ${WifiController.ESP_HOST}:$PORT (ESP32RET GVRET over telnet, text)",
                "format micros - id S|X bus length b0..bN (id and bytes are hex)",
                "listen-only: one keepalive (F1 09) at connect; no CAN frame is written",
                "phases: N = neutral before the driver selects reverse, R = after",
            )
        )
        captureLog.comment("phase N - neutral, engine running, stationary")

        log = captureLog
        frames = 0
        dropped = 0
        phase = "N"
        error = null
        truncated = false
        reverseElapsed = -1L
        lastFrameElapsed = -1L
        startedElapsed = SystemClock.elapsedRealtime()
        status = Status.CONNECTING
        EventLog.add("capture", "connecting to ${WifiController.ESP_HOST}:$PORT")

        job = scope.launch(Dispatchers.IO) { readLoop(network) }
        return null
    }

    /**
     * Marks the moment the driver says reverse is engaged.
     *
     * A comment is written into the file at the current position, so the split
     * is by row order - the phone's clock and the ESP's micros counter never have
     * to agree. One-way: a second tap does nothing, and fixing a premature tap
     * means starting a new capture, which is cheaper than a file with two
     * "reverse started" claims.
     */
    fun markReverse(): Boolean {
        if (status != Status.STREAMING) return false
        if (phase == "R") return false
        val now = SystemClock.elapsedRealtime()
        phase = "R"
        reverseElapsed = now
        val at = String.format(Locale.US, "%.1f", (now - startedElapsed) / 1000.0)
        log?.comment("phase R - driver selected reverse at ${at}s (frame $frames)")
        EventLog.add("capture", "reverse marker written at ${at}s")
        return true
    }

    /**
     * Closes the socket, waits for the reader to finish, and returns the file.
     *
     * The wait matters: the reader is the only writer of frames, so building the
     * text while it could still append one would lose a row at the join - and a
     * row lost at the phase boundary is exactly the one the diff needs.
     */
    suspend fun stop(): Result = withContext(Dispatchers.IO) {
        val running = job
        status = Status.STOPPED
        runCatching { socket?.close() }
        socket = null
        // Cancel as well as close: stop can arrive before the job has created its
        // socket, and a job that wakes up afterwards would otherwise keep reading
        // while stop() waits in join() forever.
        running?.cancel()
        running?.join()

        val elapsed = if (startedElapsed == 0L) 0.0
        else (SystemClock.elapsedRealtime() - startedElapsed) / 1000.0
        val duration = String.format(Locale.US, "%.1f", elapsed)
        if (phase == "R") {
            val at = String.format(Locale.US, "%.1f", (reverseElapsed - startedElapsed) / 1000.0)
            log?.comment(
                "end - reverse marked at ${at}s; $frames frames stored, " +
                    "$dropped lines dropped, duration ${duration}s"
            )
        } else {
            log?.comment(
                "end - reverse was NEVER marked; $frames frames stored, " +
                    "$dropped lines dropped, duration ${duration}s"
            )
        }

        Result(
            name = fileName,
            csv = log?.text() ?: "",
            frames = frames,
            dropped = dropped,
            reverseMarked = phase == "R",
            durationS = elapsed,
        )
    }

    /** Leaves the screen without saving; the socket must not outlive it. */
    fun abandon() {
        status = Status.STOPPED
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
        job = null
        log = null
    }

    private suspend fun readLoop(network: Network) {
        var connected: Socket? = null
        try {
            val sock = network.socketFactory.createSocket()
            connected = sock
            socket = sock
            sock.connect(InetSocketAddress(WifiController.ESP_HOST, PORT), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            sock.tcpNoDelay = true

            // One keepalive: the firmware only starts filling the telnet buffer
            // after it has received a byte from this client, and replying with
            // F1 09 DE AD proves the link end-to-end.
            sock.getOutputStream().apply {
                write(KEEPALIVE)
                flush()
            }
            status = Status.STREAMING
            EventLog.add("capture", "telnet open, keepalive sent; recording")

            val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            while (currentCoroutineContext().isActive) {
                val line = try {
                    reader.readLine() ?: break
                } catch (timeout: SocketTimeoutException) {
                    continue
                }
                val frame = GvretText.parse(line)
                if (frame == null) {
                    // The keepalive reply and any partial binary bytes are not
                    // frames; count them so a misaligned stream is visible.
                    if (line.isNotBlank()) {
                        log?.drop()
                        dropped++
                    }
                    continue
                }
                if (log?.append(frame) == true) {
                    frames++
                    lastFrameElapsed = SystemClock.elapsedRealtime()
                } else {
                    truncated = true
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (status != Status.STOPPED) {
                error = t.javaClass.simpleName + ": " + (t.message ?: "no detail")
                EventLog.add("capture", "failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        } finally {
            runCatching { connected?.close() }
            if (socket === connected) socket = null
            if (status == Status.CONNECTING || status == Status.STREAMING) {
                error = if (frames == 0) {
                    "connection closed by the ESP32 before any frame arrived - " +
                        "another telnet client (SavvyCAN) may still be connected"
                } else {
                    error ?: "connection closed by the ESP32"
                }
                status = Status.ERROR
                EventLog.add("capture", "closed: $error")
            }
        }
    }
}
