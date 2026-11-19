package com.pocketmechanic.spike

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The product screen: the menu on top, the telemetry panel pinned to the bottom.
 *
 * The panel is not a copy. It is [TelemetryPanel], the same composable the
 * floating overlay renders in its own window, fed by the same [PanelFeed] and
 * the same [AssistantHost] output. That is why the two surfaces cannot show
 * different numbers, a different cue, or a different shift mode: there is one
 * implementation of each.
 *
 * The menu is still a WebView. It is the part that changes most often, and the
 * bundled asset restyles without a rebuild. The split is a vertical
 * LinearLayout: the WebView takes the remaining space, the panel wraps its
 * content at the bottom. Everything the stream does is owned by [SpikeService];
 * this activity only renders and can be destroyed without touching the stream.
 */
class DashboardActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var panelHost: LinearLayout
    private lateinit var panelView: ComposeView
    private lateinit var panelLabel: TextView
    private lateinit var restore: TextView

    private val settings by lazy { AppSettings(this) }
    private val panel by lazy { PanelFeed(settings) }

    /**
     * Lifecycle owner for the one ComposeView. This is a plain Activity, and the
     * composition is disposed through this owner exactly like the overlay's, so
     * a finish() leaves no recomposer behind.
     */
    private val owner = OverlayLifecycleOwner()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    private var pageReady = false
    private var lastTrip: JSONObject? = null

    private var lastFrameAt = 0L
    private var startedAt = 0L
    private var streamLost = false

    private val trips by lazy { TripDatabase.get(this).trips() }

    /**
     * Resolved once per activity instance. A system light/dark switch is a
     * configuration change, so Android recreates the activity and this is read
     * again; nothing here has to observe it live.
     */
    private val tokens by lazy { themeTokens() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAt = SystemClock.elapsedRealtime()
        applySystemBarAppearance(tokens)
        applyKeepScreenOn()

        web = WebView(this).apply {
            // Same colour as the page background, so the load shows no flash of
            // a different shade before the HTML paints.
            setBackgroundColor(tokens.page.toArgb())
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.textZoom = 100
            // Chromium's auto-dark would re-tint the hand-picked light palette,
            // and its result is not the palette. API 33 renamed the switch;
            // 29-32 still expose the original.
            if (Build.VERSION.SDK_INT >= 33) {
                settings.isAlgorithmicDarkeningAllowed = false
            } else {
                @Suppress("DEPRECATION")
                settings.forceDark = WebSettings.FORCE_DARK_OFF
            }
            overScrollMode = WebView.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    pushConfig()
                    // The page finished after the first onResume, so this is the
                    // only push that actually reaches it on a cold start.
                    pushLastTrip()
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        web.loadUrl("file:///android_asset/dashboard/index.html")

        // The owners go on the content root, not on the ComposeView: Compose
        // resolves the recomposer by walking up from the view that attaches, and
        // a plain Activity supplies none of them. This is the same trick the
        // overlay uses, where the ComposeView is itself the window root.
        owner.create()
        AssistantHost.syncMode(settings)
        setContentView(buildLayout())
        owner.start()

        // The stream lives in the service; starting it here is idempotent.
        SpikeService.start(this)

        scope.launch {
            SpikeService.frames.collect { frame ->
                lastFrameAt = SystemClock.elapsedRealtime()
                streamLost = false
                // Same call the overlay makes: the assistant's output for this
                // frame, not a re-run, so both surfaces see one decision.
                panel.onFrame(frame, AssistantHost.output)
            }
        }
        scope.launch {
            var wasRecording = SpikeService.trip.value.recording
            SpikeService.trip.collect { trip ->
                // The strip only changes when a drive ends; querying Room on
                // every one-second publish would be pure waste.
                if (wasRecording && !trip.recording) pushLastTrip()
                wasRecording = trip.recording
            }
        }
        main.postDelayed(lostCheck, LOST_CHECK_MS)
    }

    override fun onResume() {
        super.onResume()
        // A drive may have finished while the screen was away, and the overlay
        // toggle may have changed in the menu.
        applyKeepScreenOn()
        pushConfig()
        pushLastTrip()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE_WIFI) return
        val saved = WifiController.saveSucceeded(resultCode, data)
        if (saved) settings.espNetworkSaved = true
        EventLog.add("wifi", "landing add-network dialog: result=$resultCode saved=$saved")
        android.widget.Toast.makeText(
            this,
            if (saved) "Saved - Android can reconnect by itself" else "Not saved",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        pushConfig()
    }

    override fun onDestroy() {
        main.removeCallbacks(lostCheck)
        scope.cancel()
        owner.destroy()
        web.destroy()
        super.onDestroy()
    }

    // --- layout --------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.page.toArgb())
        }
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        root.addView(
            web,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        panelLabel = TextView(this).apply {
            text = "LIVE TELEMETRY"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(tokens.faint.toArgb())
            setPadding(dp(4), dp(6), 0, dp(2))
        }

        restore = TextView(this).apply {
            text = "SHOW TELEMETRY"
            textSize = 11f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(tokens.dim.toArgb())
            setPadding(0, dp(14), 0, dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(tokens.card.toArgb())
                setStroke(dp(1), tokens.border.toArgb())
            }
            visibility = View.GONE
            setOnClickListener { showPanel() }
        }

        panelView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val trip by SpikeService.trip.collectAsState()
                val mode by AssistantHost.mode.collectAsState()
                CompositionLocalProvider(LocalTokens provides tokens) {
                    TelemetryPanel(
                        ui = panel.state,
                        barThr1 = { panel.barThr1 },
                        trip = trip,
                        // The landing is a portrait sheet. The overlay keeps the
                        // landscape cockpit column; the two configurations are
                        // independent on purpose.
                        landscape = false,
                        fullHeight = false,
                        availableHeightDp = (resources.displayMetrics.heightPixels /
                            resources.displayMetrics.density).toInt(),
                        railWidthDp = RAIL_PORTRAIT_DP,
                        unit = if (settings.units == Units.IMPERIAL) "mph" else "km/h",
                        throttleIdleRaw = settings.throttleIdleRaw,
                        throttleFullRaw = settings.throttleFullRaw,
                        mode = mode,
                        onCycleMode = { AssistantHost.setMode(mode.next(), settings) },
                        onMaps = { OverlayLauncher.openMaps(this@DashboardActivity) },
                        onMusic = { OverlayLauncher.openMusic(this@DashboardActivity, settings) },
                        onCalib = { OverlayLauncher.openCalibration(this@DashboardActivity) },
                        onHide = { hidePanel() },
                    )
                }
            }
        }
        panelHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 14 dp side margins match the menu cards; the bottom inset is added
            // in applyInsets() so the action row cannot sit under the gesture
            // handle.
            setPadding(dp(14), 0, dp(14), dp(10))
            addView(panelLabel)
            addView(
                panelView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(restore)
        }
        root.addView(
            panelHost,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        applyInsets(root)
        return root
    }

    /**
     * The activity is locked to portrait, so the only inset that can change at
     * runtime is the navigation bar (3-button vs gesture, or a fold). The panel
     * is the bottom-most view, so it is the one that has to clear it; the
     * WebView above never touches the screen edge.
     */
    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            panelHost.setPadding(dp(14), 0, dp(14), dp(10) + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * The panel can be collapsed to this strip. It is a session state, like the
     * overlay's Hide: the next launch shows the panel again. The menu row that
     * turns the floating bubble on and off is a different thing and stays in the
     * WebView.
     */
    private fun hidePanel() {
        panelLabel.visibility = View.GONE
        panelView.visibility = View.GONE
        restore.visibility = View.VISIBLE
        EventLog.add("ui", "landing panel collapsed")
    }

    private fun showPanel() {
        panelLabel.visibility = View.VISIBLE
        panelView.visibility = View.VISIBLE
        restore.visibility = View.GONE
        EventLog.add("ui", "landing panel restored")
    }

    // --- service -> page -----------------------------------------------------

    private fun pushConfig() {
        if (!pageReady) return
        // The page has its own CSS variables for both palettes; this is the only
        // thing that has to cross the bridge, and it goes first so the row text
        // below never paints against the wrong background.
        evaluate("window.pm.theme('${if (tokens.dark) "dark" else "light"}')")
        val json = JSONObject().apply {
            put("overlayMode", settings.overlayMode.key)
            put("overlayDesc", overlayDescription())
            put("keepScreenOn", settings.keepScreenOn)
            put("keepScreenDesc", keepScreenOnDescription())
            put("navOn", navListenerEnabled(this@DashboardActivity))
            put("navDesc", navListenerDescription(this@DashboardActivity))
            put("wifiDesc", WifiController.describe(settings.espNetworkSaved, WifiController.espNetwork != null))
            put("driver", settings.driverName)
            put("car", settings.car.name)
        }
        evaluate("window.pm.config($json)")
    }

    private fun pushLastTrip() {
        if (!pageReady) return
        scope.launch {
            val rows = withContext(Dispatchers.IO) { trips.all() }
            val row = rows.firstOrNull() ?: run {
                lastTrip = null
                evaluate("window.pm.lastTrip(null)")
                return@launch
            }
            val json = JSONObject().apply {
                put("id", row.id)
                put("distanceKm", row.distanceKm)
                put("ecoScore", row.ecoScore)
                put("startedAt", row.startedAt)
                put("startedLabel", startedLabel(row.startedAt))
                put("count", rows.size)
            }
            lastTrip = json
            evaluateLastTrip(json)
        }
    }

    private fun evaluateLastTrip(json: JSONObject) {
        evaluate("window.pm.lastTrip($json)")
    }

    /** "12 Nov 19:04", local time; the year is not useful on a landing strip. */
    private fun startedLabel(at: Long): String =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at))

    private fun overlayDescription(): String = when (settings.overlayMode) {
        OverlayMode.AUTO -> "Auto · over other apps while driving"
        OverlayMode.ALWAYS -> "Always · over everything, even this app"
        OverlayMode.OFF ->
            if (Settings.canDrawOverlays(this)) {
                "Off · tap for auto"
            } else {
                "Off · needs 'draw over other apps' (Vehicle & driver)"
            }
    }

    private fun keepScreenOnDescription(): String =
        if (settings.keepScreenOn) {
            "On · the panel keeps the display awake"
        } else {
            "Off · the system timeout applies"
        }

    private fun evaluate(js: String) {
        web.evaluateJavascript(js, null)
    }

    // --- watchdogs -----------------------------------------------------------

    /**
     * The service flags a stale link after 2 s, which is the "values may be
     * frozen" threshold, not "the stream is gone". The panel's NO LINK means the
     * latter, so it uses the same 10 s the overlay uses. One second of polling
     * is enough for a word that changes this slowly, and it also covers the
     * never-connected case the service watchdog cannot see.
     */
    private val lostCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val since = if (lastFrameAt == 0L) now - startedAt else now - lastFrameAt
            if (!streamLost && since >= STREAM_LOST_MS) {
                streamLost = true
                panel.onStreamLost(since)
            }
            main.postDelayed(this, LOST_CHECK_MS)
        }
    }

    /**
     * The dashboard is a window too: the same flag that keeps the panel awake
     * over Maps also keeps it awake here. No wake lock, so it lapses as soon as
     * the activity is no longer visible.
     */
    private fun applyKeepScreenOn() {
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- JS -> Kotlin --------------------------------------------------------

    /**
     * JS -> Kotlin. Rendering must never drive the stream, so every method here
     * either opens another screen or flips a user setting; none of them touches
     * the CAN path.
     *
     * The calls arrive on the WebView's JavaScript thread, so UI work and
     * `startActivity` are posted to the main thread.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun openMenu() = runOnUiThread {
            startActivity(Intent(this@DashboardActivity, MenuActivity::class.java))
        }

        @JavascriptInterface
        fun openDevScreen() = runOnUiThread {
            startActivity(Intent(this@DashboardActivity, MainActivity::class.java))
        }

        @JavascriptInterface
        fun openTrips() = runOnUiThread {
            startActivity(Intent(this@DashboardActivity, TripsActivity::class.java))
        }

        @JavascriptInterface
        fun openCalibration() = runOnUiThread {
            startActivity(Intent(this@DashboardActivity, CalibrationActivity::class.java))
        }

        @JavascriptInterface
        fun openVehicle() = runOnUiThread {
            startActivity(Intent(this@DashboardActivity, OnboardingActivity::class.java))
        }

        @JavascriptInterface
        fun openDiagnostics() = runOnUiThread {
            startActivity(Intent(this@DashboardActivity, MainActivity::class.java))
        }

        /**
         * Cycles the floating overlay AUTO → ALWAYS → OFF, the same order as the
         * menu row so the two surfaces cannot teach different gestures. Returns
         * the new mode as JSON so the row repaints without a round trip through
         * the service.
         */
        @JavascriptInterface
        fun cycleOverlay(): String {
            val next = settings.overlayMode.next()
            val canDraw = Settings.canDrawOverlays(this@DashboardActivity)
            settings.overlayMode = next
            val running = SpikeService.instance
            if (running != null) {
                running.setOverlayMode(next)
            } else {
                runOnUiThread { SpikeService.start(this@DashboardActivity) }
            }
            EventLog.add(
                "overlay",
                "landing: overlay mode ${next.key} " +
                    "(serviceRunning=${running != null} canDraw=$canDraw)",
            )
            val message = when {
                next == OverlayMode.OFF -> "overlay off"
                next == OverlayMode.AUTO && canDraw -> "overlay: auto (in other apps, while driving)"
                next == OverlayMode.ALWAYS && canDraw -> "overlay: always on"
                else -> "overlay armed - allow 'draw over other apps' to see it"
            }
            runOnUiThread {
                android.widget.Toast.makeText(this@DashboardActivity, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            return JSONObject().apply {
                put("mode", next.key)
                put("desc", overlayDescription())
            }.toString()
        }

        /**
         * "Keep screen on" from the landing row. The service owns the overlay
         * windows, so the live flag is patched there; the dashboard's own
         * window is patched here.
         */
        @JavascriptInterface
        fun toggleKeepScreenOn(): String {
            val next = !settings.keepScreenOn
            settings.keepScreenOn = next
            runOnUiThread { this@DashboardActivity.applyKeepScreenOn() }
            SpikeService.instance?.refreshKeepScreenOn()
            EventLog.add("ui", "landing: keep_screen_on=$next")
            return JSONObject().apply {
                put("on", next)
                put("desc", keepScreenOnDescription())
            }.toString()
        }

        /**
         * "Navigation HUD" from the landing row. Nothing to toggle in the app:
         * the HUD is driven by the system's notification-access grant, so the
         * row opens that screen and the description is refreshed on resume.
         */
        @JavascriptInterface
        fun openNavAccess() {
            EventLog.add(
                "nav",
                "landing: opening notification access (granted=${navListenerEnabled(this@DashboardActivity)})",
            )
            runOnUiThread {
                if (!openNavListenerSettings(this@DashboardActivity)) {
                    android.widget.Toast.makeText(
                        this@DashboardActivity,
                        "Notification access screen not available on this ROM",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        /**
         * The saved-network dialog. On API 29 there is no such dialog; the
         * suggestion is the only public path, and its status is the toast.
         */
        @JavascriptInterface
        fun saveWifi() {
            val intent = WifiController.saveNetworkIntent()
            if (intent == null) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@DashboardActivity,
                        WifiController.suggest(this@DashboardActivity),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                runOnUiThread { startActivityForResult(intent, REQ_SAVE_WIFI) }
            }
        }

        @JavascriptInterface
        fun showPanel() = runOnUiThread { this@DashboardActivity.showPanel() }

        @JavascriptInterface
        fun appVersion(): String = "0.2-s3a"
    }

    private companion object {
        const val STREAM_LOST_MS = 10_000L
        const val LOST_CHECK_MS = 1_000L
        const val REQ_SAVE_WIFI = 43

        /** Portrait rail width, the overlay's value: a physical rail, not a %. */
        const val RAIL_PORTRAIT_DP = 24
    }
}
