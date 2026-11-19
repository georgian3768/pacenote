package com.pocketmechanic.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import com.pocketmechanic.shift.Cue
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Phase 0 spike screen.
 *
 * Everything shown here exists to answer one question per line: is the stream
 * healthy, is the phone still using cellular by default, and can a background
 * start actually succeed on this handset.
 */
class MainActivity : Activity() {

    /** Resolved once: a system light/dark switch recreates the activity. */
    private val tokens by lazy { themeTokens() }

    private lateinit var banner: TextView
    private lateinit var linkView: TextView
    private lateinit var statsView: TextView
    private lateinit var liveView: TextView
    private lateinit var logView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastTelemetry: Telemetry? = null

    private companion object {
        /** Stop showing the last frame as "live" after this much silence. */
        const val STREAM_LOST_MS = 10_000L
    }

    private val renderer = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarAppearance(tokens)
        setContentView(buildUi())
        EventLog.add("app", "spike screen opened, pid=${android.os.Process.myPid()}")

        val perms = mutableListOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Without this, WifiInfo.getSSID() is "<unknown ssid>" on Android 13
            // and the ESP32 AP is never recognised.
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        requestPermissions(perms.toTypedArray(), 1)

        scope.launch {
            SpikeService.frames.collect { frame -> lastTelemetry = frame }
        }
        handler.post(renderer)
    }

    override fun onDestroy() {
        handler.removeCallbacks(renderer)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
            setBackgroundColor(tokens.page.toArgb())
        }

        root.addView(text("Pacenote — Phase 0 spike", 22f, bold = true))
        root.addView(text("Wi-Fi telemetry over the ESP32 AP; cellular stays the default.", 13f))

        banner = text("", 15f, bold = true).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(60, 60, 60))
            setPadding(18, 14, 18, 14)
        }
        root.addView(banner)

        linkView = text("", 14f, mono = true)
        statsView = text("", 14f, mono = true)
        liveView = text("", 15f, mono = true)

        root.addView(section("Link", linkView))
        root.addView(section("Stream", statsView))
        root.addView(section("Telemetry", liveView))

        root.addView(section("Wi-Fi & startup", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button("Add Wi-Fi suggestion") {
                val result = WifiController.suggest(this@MainActivity)
                toast("suggestion: $result")
                render()
            })
            addView(button("Start service (app visible)") {
                val result = SpikeService.start(this@MainActivity)
                EventLog.add("matrix", "case (a) visible start -> $result")
                render()
            })
            addView(button("Background start (case b/c)") { testBackgroundStart() })
            addView(button("Pin AP (dev; may show a system sheet)") {
                val svc = SpikeService.instance
                if (svc == null) {
                    toast("service not running")
                } else {
                    svc.pinEspNetwork()
                    toast("pin request sent")
                }
                render()
            })
            addView(button("Register PendingIntent callback") {
                WifiController.registerPendingIntentForm(this@MainActivity)
                render()
            })
            addView(button("Battery exemption state") { reportBatteryExemption() })
            addView(button("Overlay permission (case d)") { requestOverlayPermission() })
        }))

        root.addView(section("Overlays (S3B)", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The label is refreshed in place: render() only repaints the
            // stream/link text, so a label built once would go stale here.
            lateinit var modeButton: Button
            modeButton = button("Overlay mode: ${AppSettings(this@MainActivity).overlayMode.key}") {
                val svc = SpikeService.instance
                if (svc == null) {
                    toast("service not running")
                } else {
                    val next = AppSettings(this@MainActivity).overlayMode.next()
                    svc.setOverlayMode(next)
                    modeButton.text = "Overlay mode: ${next.key}"
                    toast("overlay: ${next.key}")
                }
                render()
            }
            addView(modeButton)
            addView(button("Swap bubble engine (legacy ⇄ Compose)") {
                val svc = SpikeService.instance
                if (svc == null) {
                    toast("service not running")
                } else {
                    val settings = AppSettings(this@MainActivity)
                    settings.composeOverlay = !settings.composeOverlay
                    svc.restartOverlay()
                    toast("bubble engine: ${svc.overlayRenderer()}")
                }
                render()
            })
            addView(button("Nav HUD (bubble ⇄ HUD)") {
                val svc = SpikeService.instance
                if (svc == null) {
                    toast("service not running")
                } else {
                    val next = !svc.navLayout()
                    svc.setNavLayout(next)
                    toast("nav layout: ${if (next) "hud" else "bubble"}")
                }
                render()
            })
            addView(button("Synthetic drive (20 s, app-side)") {
                val svc = SpikeService.instance
                if (svc == null) {
                    toast("service not running")
                } else {
                    // The demo runs while this screen is in front, so AUTO would
                    // correctly keep the bubble hidden; force it on to watch it.
                    svc.setOverlayMode(OverlayMode.ALWAYS)
                    svc.startSynthetic(20)
                    toast("synthetic drive started")
                }
                render()
            })
            addView(button("Music target: ${AppSettings(this@MainActivity).musicPackage}") {
                val settings = AppSettings(this@MainActivity)
                val next = MUSIC_CANDIDATES[(MUSIC_CANDIDATES.indexOf(settings.musicPackage) + 1) % MUSIC_CANDIDATES.size]
                settings.musicPackage = next
                toast("music target: $next")
                render()
            })
            addView(button("Border flash test (green then red)") {
                SpikeService.instance?.flashBorderTest()
                toast("border flash")
            })
            addView(button("Assistant state") {
                val svc = SpikeService.instance
                toast(svc?.assistantOutput() ?: "service not running")
                render()
            })
            addView(button("Bubble cue preview: up, down, idle") { previewBubbleCue() })
            addView(button("Over-rev ring preview (red pulse)") {
                val svc = SpikeService.instance
                if (svc == null) {
                    toast("service not running")
                } else {
                    svc.previewCue(Cue.OVER_REV)
                    handler.postDelayed({ svc.previewCue(null) }, 5000)
                    toast("over-rev ring preview")
                }
                render()
            })
            addView(button("Trips (Room)") {
                startActivity(Intent(this@MainActivity, TripsActivity::class.java))
            })
            addView(button("End trip now") { SpikeService.instance?.endTripNow() })
            addView(button("Replay Session B (8x)") { SpikeService.instance?.startReplay() })
            addView(button("Service reminder test") { SpikeService.instance?.reminderTest() })
            addView(button("Over-rev beep test") { SpikeService.instance?.overRevBeepTest() })
            addView(button("Split screen: Maps + dashboard") { trySplitScreen() })
            addView(button("Calibration wizards") {
                startActivity(Intent(this@MainActivity, CalibrationActivity::class.java))
            })
        }))

        root.addView(section("Network proof", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button("Request on DEFAULT network") { testDefaultNetwork() })
            addView(button("Check 192.168.4.1 /status") { checkEspReachable() })
        }))

        logView = text("", 11f, mono = true)
        root.addView(section("Log", logView))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Export log") { exportLog() })
            addView(button("Clear") {
                EventLog.clear()
                render()
            })
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun render() {
        val link = SpikeService.link.value
        val stats = SpikeService.stats.value
        // A frozen frame is worse than no frame: after the same 10 s the service
        // uses, the dashboard stops presenting old rpm/brake as current values.
        val frame = lastTelemetry.takeIf { stats.ageMs in 0..STREAM_LOST_MS }

        banner.text = link.lastError?.let { "STALE — $it" } ?: "LIVE"
        banner.setBackgroundColor(
            if (link.lastError == null) tokens.up.toArgb() else tokens.overRev.toArgb()
        )

        linkView.text = buildString {
            append("default network   : ${link.defaultTransport}\n")
            append("wifi validated    : ${link.wifiValidated ?: "unknown"}\n")
            append("esp network       : ${link.espNetwork}\n")
            append("esp /status       : ${link.reachable}\n")
        }

        statsView.text = buildString {
            append("frames            : ${stats.frames}\n")
            append("frames/s          : ${"%.1f".format(stats.fps)}\n")
            append("seq gaps          : ${stats.gaps}\n")
            append("jitter mean/p95/max: %.1f / %d / %d ms\n".format(
                stats.jitterMeanMs, stats.jitterP95Ms, stats.jitterMaxMs
            ))
            append("reconnects        : ${stats.reconnects}\n")
            append("last seq          : ${stats.lastSeq}\n")
            append("age               : ${if (stats.ageMs < 0) "-" else "${stats.ageMs} ms"}\n")
        }

        liveView.text = if (frame == null) {
            if (stats.ageMs >= STREAM_LOST_MS) {
                "no frames for ${stats.ageMs / 1000} s - stream lost, values cleared"
            } else {
                "no frame yet"
            }
        } else buildString {
            append("rpm ${frame.rpm ?: "-"}  speed ${frame.speedKph ?: "-"} km/h\n")
            append("brake ${frame.brake ?: "-"}  thr ${frame.thr1 ?: "-"}/${frame.thr2 ?: "-"}\n")
            append("engine ${frame.engineRunning ?: "-"}  can ${frame.canActive}\n")
            append("temp ${frame.tempRaw ?: "-"}  dist ${frame.distRaw ?: "-"}  volt ${frame.volt ?: "-"}\n")
        }

        logView.text = EventLog.dump().takeLast(4000)
    }

    // --- startup matrix cases -------------------------------------------------

    private fun testBackgroundStart() {
        val exempt = isBatteryExempt()
        EventLog.add("matrix", "case (b/c) background start, battery exemption=$exempt")
        moveTaskToBack(true)
        handler.postDelayed({
            val result = SpikeService.start(this)
            EventLog.add("matrix", "background start result -> $result")
            render()
        }, 3000)
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun reportBatteryExemption() {
        val exempt = isBatteryExempt()
        EventLog.add("matrix", "battery optimization exemption=$exempt")
        if (!exempt) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                EventLog.add("matrix", "battery exemption intent failed: $t")
            }
        }
        render()
    }

    private fun requestOverlayPermission() {
        val granted = Settings.canDrawOverlays(this)
        EventLog.add("matrix", "case (d) SYSTEM_ALERT_WINDOW granted=$granted")
        if (!granted) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                EventLog.add("matrix", "overlay intent failed: $t")
            }
        }
        render()
    }

    // --- network proof -------------------------------------------------------

    private fun testDefaultNetwork() {
        Thread {
            val (transport, validated) = WifiController.describeDefault(this)
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("http://clients3.google.com/generate_204")
                .build()
            val result = try {
                client.newCall(request).execute().use { "HTTP ${it.code}" }
            } catch (t: Throwable) {
                "failed: ${t.message}"
            }
            EventLog.add(
                "default",
                "request on DEFAULT network ($transport, validated=$validated) -> $result"
            )
            runOnUiThread { render() }
        }.start()
    }

    private fun checkEspReachable() {
        Thread {
            val network = WifiController.espNetwork
            if (network == null) {
                SpikeService.link.value = SpikeService.link.value.copy(reachable = "no ESP32 network")
                EventLog.add("esp", "check skipped: not on the ESP32 network")
                runOnUiThread { render() }
                return@Thread
            }
            val client = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("http://${WifiController.ESP_HOST}/status")
                .build()
            val result = try {
                client.newCall(request).execute().use { response ->
                    "HTTP ${response.code} ${response.body?.string()?.take(180) ?: ""}"
                }
            } catch (t: Throwable) {
                "failed: ${t.message}"
            }
            SpikeService.link.value = SpikeService.link.value.copy(reachable = result)
            EventLog.add("esp", "GET /status -> $result")
            runOnUiThread { render() }
        }.start()
    }

    private fun exportLog() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Pacenote spike log")
            putExtra(Intent.EXTRA_TEXT, EventLog.dump())
        }
        startActivity(Intent.createChooser(intent, "Export spike log"))
    }

    // --- view helpers --------------------------------------------------------

    private fun text(value: String, size: Float, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) setTypeface(Typeface.MONOSPACE)
            setTextColor(tokens.ink.toArgb())
            setPadding(0, 10, 0, 10)
        }

    private fun section(title: String, content: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 14, 0, 14)
        addView(TextView(this@MainActivity).apply {
            text = title.uppercase()
            textSize = 12f
            setTextColor(tokens.dim.toArgb())
            letterSpacing = 0.12f
        })
        addView(content)
    }

    /**
     * Best-effort split screen.
     *
     * `ActivityOptions.setLaunchWindowingMode` is a hidden system API (it does
     * not compile against the public SDK), so the only public lever is
     * FLAG_ACTIVITY_LAUNCH_ADJACENT: "put this next to the current window". The
     * system is free to ignore it, which is why this is a dev action with a log
     * line rather than a product feature.
     */
    private fun trySplitScreen() {
        val maps = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (maps == null) {
            toast("Maps not installed or not visible to this app")
            return
        }
        maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching { startActivity(maps) }
            .onFailure { EventLog.add("split", "maps launch failed: ${it.message}") }
        handler.postDelayed({
            val dash = Intent(this, DashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            runCatching { startActivity(dash) }
                .onFailure { EventLog.add("split", "dashboard launch failed: ${it.message}") }
            EventLog.add("split", "requested adjacent launch: maps + dashboard")
        }, 1500)
    }

    /** Renders green up / red down / idle on the bubble for screenshots. */
    private fun previewBubbleCue() {
        val service = SpikeService.instance
        if (service == null) {
            toast("service not running")
            return
        }
        service.previewCue(Cue.UP)
        handler.postDelayed({ service.previewCue(Cue.DOWN) }, 2500)
        handler.postDelayed({ service.previewCue(null) }, 5000)
        EventLog.add("overlay", "cue preview: up, down, idle")
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
