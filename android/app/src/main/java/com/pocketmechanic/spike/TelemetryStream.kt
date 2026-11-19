package com.pocketmechanic.spike

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * SSE client bound to the ESP32's own network.
 *
 * Sockets are created through Network.socketFactory so the stream cannot leak to
 * cellular, and bindProcessToNetwork is deliberately never called: the process
 * keeps its default network so Maps and YouTube Music stay on mobile data.
 */
class TelemetryStream(
    private val onFrame: (Telemetry, Long) -> Unit,
    private val onState: (String) -> Unit,
    private val onOpen: () -> Unit,
    private val onClosed: (String) -> Unit
) {
    private var source: EventSource? = null
    private var last: Telemetry? = null

    fun start(host: String) {
        val network = WifiController.espNetwork
        if (network == null) {
            // Report through onClosed so the service releases its stream slot and
            // schedules a retry; a silent return left it claimed forever.
            onState("no ESP32 network yet; stream not started")
            onClosed("no ESP32 network")
            return
        }

        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val request = Request.Builder()
            .url("http://$host/events")
            .header("Accept", "text/event-stream")
            .build()

        onState("GET http://$host/events over $network")
        source = EventSources.createFactory(client).newEventSource(request, listener)
    }

    fun stop() {
        source?.cancel()
        source = null
        last = null
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
            onState("stream open HTTP ${response.code}")
            onOpen()
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (type != "f" && type != "s") return      // ping keepalives are ignored
            try {
                val parsed = Telemetry.parse(data, last)
                last = parsed
                onFrame(parsed, android.os.SystemClock.elapsedRealtime())
            } catch (t: Throwable) {
                onState("bad payload on '$type': ${t.message}")
            }
        }

        override fun onClosed(eventSource: EventSource) {
            onState("stream closed by server")
            onClosed("closed")
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
            val reason = t?.message ?: "http ${response?.code}"
            onState("stream failure: $reason")
            onClosed(reason)
        }
    }
}
