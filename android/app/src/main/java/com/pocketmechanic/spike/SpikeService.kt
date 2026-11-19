package com.pocketmechanic.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import com.pocketmechanic.shift.Cue
import com.pocketmechanic.shift.Output
import com.pocketmechanic.shift.SpeedScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The single owner of the SSE connection.
 *
 * There is exactly one stream per process on purpose: two readers would double
 * the sequence accounting and make gap/jitter numbers meaningless. Consumers
 * observe [frames] instead of opening their own connection.
 */
/**
 * What the driving surfaces need to know about the running trip.
 *
 * Published once a second by the service so the overlay card and the dashboard
 * read the same numbers. [ecoScore] is a live estimate: the score is normalised
 * per moving second, so it moves around early in a trip and is not the value the
 * saved row will carry.
 */
data class TripUi(
    val recording: Boolean = false,
    val elapsedMs: Long = 0L,
    val distanceKm: Double = 0.0,
    val ecoScore: Int? = null,
    val serviceRemainingKm: Double? = null,
)

class SpikeService : Service() {

    companion object {
        private const val CHANNEL_ID = "spike"
        private const val NOTIFICATION_ID = 42
        private const val WIFI_CHANNEL_ID = "wifi"
        private const val WIFI_ALERT_ID = 2

        /**
         * Wait this long after the ESP32 disappears before offering help. The
         * platform often rejoins by itself within ~35 s (measured 2026-11-10,
         * Wi-Fi off/on); a shorter alert would fire just before it heals.
         */
        private const val WIFI_ALERT_MS = 45_000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val STALE = "no frame for over 2 s"

        /**
         * Dev intent extra: `adb shell run-as com.pocketmechanic.spike am
         * start-foreground-service -n com.pocketmechanic.spike/.SpikeService
         * --es assistant_mode shadow`. Values: eco, normal, sport, shadow.
         */
        const val EXTRA_MODE = "assistant_mode"

        /** No frames for this long: the last values are no longer "live". */
        private const val STREAM_LOST_MS = 10_000L

        /**
         * AUTO mode arms after this much continuous traffic. One stray frame as
         * the car wakes up must not flash a bubble on the lock screen.
         */
        private const val AUTO_ARM_MS = 1_500L

        /**
         * No frames for this long: close the trip. Longer than [STREAM_LOST_MS]
         * on purpose - a fuel stop or a key-off shorter than a minute must not
         * split one drive into two rows, while the bubble stops claiming live
         * values after ten seconds.
         */
        private const val TRIP_END_AFTER_SILENCE_MS = 60_000L

        /** Frames per Room batch (~4 s of stream at 10 Hz). */
        private const val FRAME_BATCH = 40

        /** Live service instance, for dev-screen actions only. */
        @Volatile
        var instance: SpikeService? = null
            private set

        val frames = MutableSharedFlow<Telemetry>(replay = 1, extraBufferCapacity = 64)
        val stats = MutableStateFlow(StreamStats.Snapshot())
        val link = MutableStateFlow(LinkState())
        val trip = MutableStateFlow(TripUi())

        /** Start helper that reports why a background start was refused. */
        fun start(context: Context): String {
            val intent = Intent(context, SpikeService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                EventLog.add("svc", "startForegroundService accepted")
                "accepted"
            } catch (t: Throwable) {
                val reason = "${t.javaClass.simpleName}: ${t.message}"
                EventLog.add("svc", "start REFUSED: $reason")
                "refused: $reason"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statsTracker = StreamStats()

    /**
     * Re-runs the overlay policy the moment our last activity stops (or the
     * first one starts).
     *
     * The one-second ticker alone leaves a visible hole between pressing Home
     * and the bubble appearing, and that hole is what makes AUTO look like it
     * waits for *another app* to be launched. Minimising the app is the trigger;
     * what is on screen behind it does not matter.
     */
    private val foregroundListener: () -> Unit = {
        scope.launch { evaluateOverlay(SystemClock.elapsedRealtime()) }
    }

    /**
     * Navigation sessions switch the overlay surface, not its policy.
     *
     * The notification listener and this service live independent lives: the
     * listener is bound by the system and can be up before the drive service
     * starts, and the drive service is START_STICKY while the listener can be
     * unbound when notification access changes. [NavState] holds the flag, this
     * listener is told when it flips, and [onCreate] applies the value that is
     * current at start instead of waiting for the next notification.
     */
    private val navListener: (Boolean) -> Unit = { navigating ->
        overlay.setNavLayout(navigating)
    }

    private var stream: TelemetryStream? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pinCallback: ConnectivityManager.NetworkCallback? = null
    private var ticker: Job? = null
    private var retryJob: Job? = null
    private var replayJob: Job? = null

    /** Armed only by a real loss, so a cold start at home does not nag. */
    private var wifiAlertJob: Job? = null

    // Per-trip frame log. Frames are buffered and written in batches: one Room
    // insert per frame at 10 Hz would be 10 transactions/s for no benefit.
    private var currentTripId: Long? = null
    // Frame timestamps must be unique inside a trip (tripId, tMs is the primary
    // key). The stream occasionally emits two frames in the same millisecond, so
    // the logged clock is forced to advance by at least 1 ms per frame.
    private var lastFrameTMs = 0L
    /** Distance to the next service mark; null when reminders are off. */
    private var serviceRemainingKm: Double? = null
    private var serviceTick = 0
    private val frameBuffer = ArrayList<TripFrameRow>(64)
    // Atomic, not a plain flag: the network callback thread and the main thread
    // both call connectStream() for the same AP, and a plain check-then-set let
    // both through (two SSE streams, observed 2026-10-30).
    private val streamOpen = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastFrameElapsed = -1L
    /**
     * Last time a frame reached the overlay path. Separate from
     * [lastFrameElapsed] because replay and the synthetic drive feed the
     * renderer without touching the stream watchdog.
     */
    private var overlayFrameElapsed = -1L
    /** First frame of the current continuous burst; AUTO waits on this. */
    private var burstStartElapsed = -1L
    /**
     * Set when the driver drops the bubble on the hide target. AUTO clears it
     * once the bus has been quiet for [STREAM_LOST_MS]; ALWAYS keeps it until
     * the mode is set again. Either way an explicit mode change clears it, so
     * the row's tap always works.
     */
    private var dismissedByDriver = false
    /** Last decision, so the policy only talks to the window on a change. */
    private var overlayWanted: Boolean? = null
    /** True once the silence watchdog has cleared the UI for this outage. */
    private var silenceHandled = false
    /** Bench demo frames are not the car's: its trip must never be persisted. */
    private var syntheticActive = false
    /** Set when the current trip was started by demo frames (never saved). */
    private var syntheticTrip = false
    private val overlay by lazy { OverlayHost(this) }
    private val settings by lazy { AppSettings(this) }
    private val trips by lazy { TripDatabase.get(this).trips() }
    private val tripRecorder by lazy { TripRecorder(settings.car.redlineRpm) }

    /**
     * Doubling reconnect delay. A fixed 2 s loop was measured hammering a dead
     * endpoint ~30 times/minute on the handset (2026-10-30); the delay resets as
     * soon as a stream opens.
     */
    private var retryDelayMs = RECONNECT_DELAY_MS

    override fun onCreate() {
        super.onCreate()
        instance = this
        EventLog.init(this)
        CueLogFile.init(this)
        startAsForeground()
        EventLog.add("svc", "foreground service created")
        AppForeground.addListener(foregroundListener)
        NavState.addListener(navListener)
        overlay.setNavLayout(NavState.isNavigating)

        // Passive detection is the mechanism that works on HyperOS. The pin
        // request is opt-in via the dev screen (pinEspNetwork) because on the
        // POCO F3 GT its system sheet auto-dismisses in 1.5-3 s and the request
        // returns "unavailable", so firing it on every launch only puts a dead
        // system dialog in front of the user (measured 2026-10-30).
        networkCallback = WifiController.registerEspNetwork(
            this,
            { _: Network ->
                wifiAlertJob?.cancel()
                wifiAlertJob = null
                clearWifiAlert()
                connectStream()
            },
            {
                streamOpen.set(false)
                stream?.stop()
                stream = null
                scheduleWifiAlert()
            }
        )

        registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                overlay.onOrientationChanged()
            }

            override fun onLowMemory() {}
        })

        if (settings.overlayMode.draws) {
            EventLog.add(
                "overlay",
                "at start: mode=${settings.overlayMode.key} permission=${overlay.canDraw()}",
            )
        }
        overlay.onUserDismiss = {
            dismissedByDriver = true
            EventLog.add("overlay", "dropped on hide target; suppressed until the bus goes quiet")
        }

        // A trip row is created on the first frame so frames have something to
        // point at. If the process dies mid-trip, that row has endedAt = 0 and no
        // summary. It used to be deleted on the next start, which threw the whole
        // drive away; the frames are still in trip_frames, so replay them through
        // the accountant and close the trip instead.
        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { salvageUnfinishedTrips() }
                    .onFailure {
                        EventLog.add("trip", "salvage failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
            }
        }

        refreshServiceRemaining()

        ticker = scope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                publish()
                checkSilence(now)
                evaluateOverlay(now)
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODE)?.let { name ->
            val mode = com.pocketmechanic.shift.AssistantMode.of(name)
            AssistantHost.setMode(mode, settings)
            // Nothing may be drawn or played in SHADOW; take the current
            // bubble down instead of leaving the last frame on screen.
            if (mode.shadow) {
                overlayWanted = false
                overlay.hide()
            } else {
                evaluateOverlay(SystemClock.elapsedRealtime())
            }
        }
        if (WifiController.espNetwork != null) connectStream()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        EventLog.add("svc", "service destroyed")
        AppForeground.removeListener(foregroundListener)
        NavState.removeListener(navListener)
        streamOpen.set(false)
        retryJob?.cancel()
        ticker?.cancel()
        wifiAlertJob?.cancel()
        clearWifiAlert()
        stream?.stop()
        overlay.hide()
        WifiController.unregister(this, networkCallback)
        WifiController.unregister(this, pinCallback)
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Dev-screen action: ask the system to pin the ESP32 AP. */
    fun pinEspNetwork() {
        pinCallback = WifiController.requestEspNetwork(
            this,
            { _: Network -> connectStream() },
            {
                streamOpen.set(false)
                stream?.stop()
                stream = null
            }
        )
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Telemetry spike",
            NotificationManager.IMPORTANCE_LOW
        )
        manager?.createNotificationChannel(channel)

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pacenote spike")
            .setContentText("Holding the telemetry stream")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * The ESP32 disappeared mid-drive (engine stopped, AP reset, phone roamed).
     * The passive callback already stopped the stream; the platform should
     * rejoin the saved network by itself. If it has not after
     * [WIFI_ALERT_MS], post the one notification whose tap can fix it.
     */
    private fun scheduleWifiAlert() {
        wifiAlertJob?.cancel()
        wifiAlertJob = scope.launch {
            delay(WIFI_ALERT_MS)
            if (WifiController.espNetwork == null) showWifiAlert()
        }
    }

    private fun showWifiAlert() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                WIFI_CHANNEL_ID,
                "Wi-Fi connection",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        // The Wi-Fi panel is the shortest path to a tap-to-connect; the full
        // settings page is the fallback on a device without the panel activity.
        val panel = Intent(Settings.Panel.ACTION_WIFI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (panel.resolveActivity(packageManager) != null) {
            panel
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, WIFI_CHANNEL_ID)
            .setContentTitle("ESP32 not connected")
            .setContentText("Tap to open Wi-Fi and reconnect")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(WIFI_ALERT_ID, notification)
        EventLog.add("wifi", "offline alert posted after $WIFI_ALERT_MS ms")
    }

    private fun clearWifiAlert() {
        getSystemService(NotificationManager::class.java)?.cancel(WIFI_ALERT_ID)
    }

    private fun connectStream() {
        // Both the passive callback and the (optional) pin callback can fire for
        // the same AP; without this guard the service opened two SSE streams and
        // the ESP32 served both (observed 2026-10-30).
        if (!streamOpen.compareAndSet(false, true)) {
            EventLog.add("sse", "already streaming; ignoring duplicate connect")
            return
        }
        val network = WifiController.espNetwork
        if (network == null) {
            // The AP can vanish (ESP32 reset, phone roamed). Without this the
            // service would sit idle forever: nothing else retries.
            EventLog.add("sse", "no ESP32 network; waiting")
            streamOpen.set(false)
            scheduleReconnect("no ESP32 network")
            return
        }

        stream?.stop()
        statsTracker.onReconnect()
        stream = TelemetryStream(
            onFrame = { frame, elapsed -> handleFrame(frame, elapsed) },
            onState = { message -> EventLog.add("sse", message) },
            onOpen = {
                retryJob?.cancel()
                retryJob = null
                retryDelayMs = RECONNECT_DELAY_MS
            },
            onClosed = { reason ->
                streamOpen.set(false)
                scheduleReconnect(reason)
            }
        )
        stream?.start(WifiController.ESP_HOST)
    }

    /**
     * Single entry point for telemetry: statistics, the shift assistant, the
     * overlay and the UI flows all see the same frame exactly once.
     */
    private fun handleFrame(frame: Telemetry, elapsed: Long) {
        statsTracker.onFrame(frame.seq, elapsed)
        lastFrameElapsed = elapsed
        silenceHandled = false
        // A real frame means any trip from here on is the car's, not the demo's.
        syntheticTrip = false
        val out = AssistantHost.onFrame(frame, settings)
        deliver(frame, out)
        // The redline is read at trip start; an unconfirmed one is a placeholder
        // and must not count over-rev time (TripRecorder takes the value then).
        tripRecorder.redlineRpm =
            if (settings.redlineTrusted) settings.car.redlineRpm else TripRecorder.UNKNOWN_REDLINE
        // Applied once, inside the recorder: the trip's speeds and the assistant's
        // are then the same number, and the row remembers which scale it used.
        tripRecorder.speedScale = settings.gpsSpeedScale
        tripRecorder.onFrame(frame)?.let { saveTrip(it) }
        logTripFrame(frame)
        scope.launch { frames.emit(frame) }
    }

    /** The overlay is a renderer: in SHADOW the assistant must render nothing. */
    private fun deliver(frame: Telemetry, out: Output?) {
        // Every frame the overlay could show passes through here - live, replay
        // or synthetic - so this is where AUTO's "is the car talking" clock is
        // kept. A gap longer than the arm window starts a new burst, so the
        // bubble re-appears a beat after the bus comes back instead of on the
        // first frame.
        val now = SystemClock.elapsedRealtime()
        if (overlayFrameElapsed < 0 || now - overlayFrameElapsed > AUTO_ARM_MS) {
            burstStartElapsed = now
        }
        overlayFrameElapsed = now
        if (!AssistantHost.shadow) overlay.onFrame(frame, out)
    }

    /**
     * Appends one frame to the current trip's log.
     *
     * The trip row is created on the first frame (so frames have a foreign key to
     * point at) and completed by [saveTrip]. A crash mid-trip therefore leaves a
     * row with `endedAt = 0`, which the Trips screen shows as "in progress"
     * instead of pretending it finished.
     */
    private fun logTripFrame(frame: Telemetry) {
        if (!tripRecorder.recording) return
        val id = currentTripId ?: kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            trips.insert(
                TripRow(
                    startedAt = System.currentTimeMillis(),
                    endedAt = 0,
                    distanceKm = 0.0,
                    maxSpeedKph = 0.0,
                    maxRpm = 0,
                    avgRpm = 0,
                    movingMs = 0,
                    idleMs = 0,
                    overRevMs = 0,
                    hardAccelCount = 0,
                    ecoScore = 0,
                    distRawDelta = null,
                    distRawRatio = null,
                    synthetic = syntheticTrip,
                    redlineConfirmed = settings.redlineTrusted,
                    speedScale = tripRecorder.recordingSpeedScale,
                )
            )
        }.also { currentTripId = it }

        synchronized(frameBuffer) {
            val now = SystemClock.elapsedRealtime()
            val tMs = if (now > lastFrameTMs) now else lastFrameTMs + 1
            lastFrameTMs = tMs
            frameBuffer.add(
                TripFrameRow(
                    tripId = id,
                    tMs = tMs,
                    rpm = frame.rpm,
                    speedKph = frame.speedKph,
                    thr1 = frame.thr1,
                    thr2 = frame.thr2,
                    brake = frame.brake,
                    engineRunning = frame.engineRunning,
                    canActive = frame.canActive,
                    // 0x124 D4-D5 pins at 4000 once warm (with 33 single-frame
                    // 4800 glitches on 2026-11-08), so the raw value is not a
                    // temperature and is deliberately not stored. The CSV keeps
                    // the column for older rows only.
                    tempRaw = null,
                    distRaw = frame.distRaw,
                    volt = frame.volt,
                )
            )
        }
        flushFramesIfFull()
    }

    private fun flushFramesIfFull() {
        val batch = synchronized(frameBuffer) {
            if (frameBuffer.size < FRAME_BATCH) return
            ArrayList(frameBuffer).also { frameBuffer.clear() }
        }
        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { trips.insertFrames(batch) }
                    .onFailure { EventLog.add("trip", "frame flush failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Atomically takes everything buffered, so a caller can flush or drop it. */
    private fun drainFrames(): List<TripFrameRow> = synchronized(frameBuffer) {
        ArrayList(frameBuffer).also { frameBuffer.clear() }
    }

    private fun flushFrames() {
        val batch = drainFrames()
        if (batch.isEmpty()) return
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            runCatching { trips.insertFrames(batch) }
                .onFailure { EventLog.add("trip", "frame flush failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Closes trips left open by a process death.
     *
     * The row was written at trip start and its frames were flushed as the drive
     * went on, so the aggregates can be recomputed from what is on disk. The end
     * timestamp is derived from the frames themselves: `tMs` is elapsedRealtime,
     * so `startedAt + (last - first)` is the wall-clock end. A row with no frames
     * has nothing to save and is dropped.
     *
     * Runs on the IO dispatcher from the startup coroutine.
     */
    private fun salvageUnfinishedTrips() {
        val rows = trips.unfinished()
        if (rows.isEmpty()) return
        var saved = 0
        var dropped = 0
        rows.forEach { row ->
            if (row.synthetic) {
                trips.deleteFrames(row.id)
                trips.delete(row.id)
                dropped++
                EventLog.add("trip", "dropped unfinished #${row.id}: bench demo")
                return@forEach
            }
            val frames = trips.frames(row.id)
            if (frames.isEmpty()) {
                trips.delete(row.id)
                dropped++
                EventLog.add("trip", "dropped unfinished #${row.id}: no frames to salvage")
                return@forEach
            }
            // Score the salvaged frames with the same redline rule the trip ran
            // under, not with whatever the profile says today.
            val accountant = com.pocketmechanic.shift.TripAccountant(
                redlineRpm = if (row.redlineConfirmed) {
                    settings.car.redlineRpm
                } else {
                    TripRecorder.UNKNOWN_REDLINE
                }
            )
            frames.forEach { f ->
                accountant.update(
                    com.pocketmechanic.shift.TripFrame(
                        tMs = f.tMs,
                        // The frames store raw CAN speed, so the row's own scale
                        // is applied here - never today's setting.
                        speedKph = SpeedScale.apply(f.speedKph, row.speedScale),
                        rpm = f.rpm?.toInt(),
                        canActive = f.canActive,
                        engineRunning = f.engineRunning,
                        distRaw = f.distRaw?.toLong(),
                        brake = f.brake,
                    )
                )
            }
            val summary = accountant.finish(frames.last().tMs)
            trips.update(
                TripFormat.row(
                    summary,
                    row.startedAt,
                    row.startedAt + summary.durationMs,
                    redlineConfirmed = row.redlineConfirmed,
                    speedScale = row.speedScale,
                ).copy(id = row.id, synthetic = row.synthetic)
            )
            saved++
            EventLog.add(
                "trip",
                "salvaged #${row.id}: %.2f km, %d frames, score %d".format(
                    summary.distanceKm, frames.size, summary.ecoScore
                )
            )
        }
        EventLog.add("trip", "salvage done: $saved saved, $dropped dropped")
    }

    /** Persists a finished trip. Storage failure must never kill the stream. */
    private fun saveTrip(finished: TripRecorder.Finished) {
        scope.launch {
            runCatching {
                flushFrames()
                val row = TripFormat.row(
                    finished.summary,
                    finished.startedAt,
                    finished.endedAt,
                    redlineConfirmed = settings.redlineTrusted,
                    speedScale = finished.speedScale,
                )
                val existing = currentTripId
                val id = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (existing != null) {
                        trips.update(row.copy(id = existing))
                        existing
                    } else {
                        trips.insert(row)
                    }
                }
                val frames = kotlinx.coroutines.withContext(Dispatchers.IO) { trips.frameCount(id) }
                currentTripId = null
                trip.value = TripUi(serviceRemainingKm = serviceRemainingKm)
                refreshServiceRemaining()
                EventLog.add(
                    "trip",
                    "saved #$id (%.2f km, score %d, $frames frames)".format(row.distanceKm, row.ecoScore)
                )
                lastKnownLocation()?.let { (lat, lon) ->
                    kotlinx.coroutines.withContext(Dispatchers.IO) { trips.setParked(id, lat, lon) }
                    EventLog.add("trip", "parked %.5f, %.5f".format(lat, lon))
                }
                checkServiceReminder(row)
            }.onFailure {
                EventLog.add("trip", "save failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /**
     * Last known fix, not a fresh request: asking for an update at trip end would
     * need a location foreground-service type on newer targets, and a stale fix
     * from the drive is what "where did I park" actually needs.
     */
    private fun lastKnownLocation(): Pair<Double, Double>? {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            EventLog.add("trip", "parked location skipped: no fine location permission")
            return null
        }
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return null
        val fix = runCatching { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?: runCatching { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) }
                .getOrNull()
        if (fix == null) {
            EventLog.add("trip", "parked location skipped: no last known fix")
            return null
        }
        return fix.latitude to fix.longitude
    }

    /**
     * Distance-based service reminder. Fires only when a whole interval has been
     * crossed between the previous total and the new one, so it cannot repeat on
     * every trip. The notification is best effort: on API 33 the permission can
     * be denied, and the log line records that instead of pretending.
     */
    private fun checkServiceReminder(trip: TripRow) {
        val interval = settings.serviceIntervalKm
        if (interval <= 0) return
        val total = kotlinx.coroutines.runBlocking(Dispatchers.IO) { trips.totalKm() }
        val before = total - trip.distanceKm
        val mark = Math.floor(total / interval) * interval
        val previousMark = Math.floor(before / interval) * interval
        if (mark <= previousMark || mark <= 0) return

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val posted = runCatching {
            manager?.notify(
                77,
                Notification.Builder(this, "reminders")
                    .setContentTitle("Service reminder")
                    .setContentText("%.0f km reached: check oil, tyres and brakes".format(mark))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            )
        }.isSuccess
        EventLog.add("reminder", "%.0f km reached (notification posted=$posted)".format(mark))
    }

    /**
     * Dev action: post the reminder notification directly. The crossing
     * arithmetic is unit tested in DistanceReminder; this only proves the
     * channel and the POST_NOTIFICATIONS permission work on the handset.
     */
    fun reminderTest() {
        val interval = settings.serviceIntervalKm.coerceAtLeast(1)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val posted = runCatching {
            manager?.notify(
                77,
                Notification.Builder(this, "reminders")
                    .setContentTitle("Service reminder")
                    .setContentText("$interval km reached: check oil, tyres and brakes")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            )
        }.isSuccess
        EventLog.add("reminder", "test $interval km (notification posted=$posted)")
    }

    /** Dev action: the over-rev tone, so that path is not only code review. */
    fun overRevBeepTest() = overlay.testOverRevBeep()

    /** Dev action: close the current trip now and persist it. */
    fun endTripNow() {
        val finished = tripRecorder.forceEnd()
        if (finished == null) {
            EventLog.add("trip", "no trip in progress")
            return
        }
        saveTrip(finished)
    }

    fun tripsSummary(): String {
        val t = tripRecorder
        return "recording=${t.recording} km=%.2f".format(t.distanceKm)
    }

    suspend fun listTrips(): List<TripRow> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { trips.all() }

    suspend fun frames(tripId: Long): List<TripFrameRow> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { trips.frames(tripId) }

    suspend fun frameCount(tripId: Long): Int =
        kotlinx.coroutines.withContext(Dispatchers.IO) { trips.frameCount(tripId) }

    suspend fun totalKm(): Double =
        kotlinx.coroutines.withContext(Dispatchers.IO) { trips.totalKm() }

    /**
     * Replay a recorded drive through the assistant and the overlay.
     *
     * The asset is a 200 ms-resampled JSONL of the Session B capture (built by
     * tools/savvycan_to_jsonl.py), so the cue logic can be demonstrated with no
     * car, no ESP32 and no CAN bus. Trips are not recorded during a replay.
     */
    fun startReplay(speedUp: Int = 8) {
        if (replayJob?.isActive == true) {
            EventLog.add("replay", "already running")
            return
        }
        replayJob = scope.launch {
            runCatching {
                val lines = assets.open("replay_session_b.jsonl").bufferedReader().use { it.readLines() }
                EventLog.add("replay", "start: ${lines.size} records at ${speedUp}x")
                var previous: Telemetry? = null
                lines.forEach { line ->
                    val frame = Telemetry.parse(line, previous)
                    previous = frame
                    val out = AssistantHost.onFrame(frame, settings)
                    deliver(frame, out)
                    frames.emit(frame)
                    delay((200 / speedUp).toLong())
                }
                EventLog.add(
                    "replay",
                    "done: last=${AssistantHost.output?.let { "gear=${it.gear} cue=${it.cue}" }}"
                )
            }.onFailure { EventLog.add("replay", "failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /**
     * Set the overlay mode from a UI action and apply it immediately.
     *
     * Clearing the dismissal matters: the tap is a fresh instruction, so it must
     * work even in the middle of a drive the driver already hid the bubble on.
     */
    fun setOverlayMode(mode: OverlayMode): OverlayMode {
        settings.overlayMode = mode
        dismissedByDriver = false
        evaluateOverlay(SystemClock.elapsedRealtime())
        EventLog.add("overlay", "mode set to ${mode.key} (shown=${overlay.isShown()})")
        return mode
    }

    /** The mode the service is currently honouring, for the dev screen. */
    fun overlayMode(): OverlayMode = settings.overlayMode

    /** The menu toggled "keep screen on": patch the live windows in place. */
    fun refreshKeepScreenOn() = overlay.onKeepScreenOnChanged()

    /** Which renderer the debug pref currently selects. */
    fun overlayRenderer(): String = overlay.activeName()

    /**
     * Dev action: rebuild the overlay with the opposite renderer.
     *
     * The old window is torn down first, then the policy re-applies, so the swap
     * cannot leave a hidden window behind (or a visible one that the current
     * mode would have suppressed).
     */
    fun restartOverlay() {
        overlay.restart()
        overlayWanted = null
        evaluateOverlay(SystemClock.elapsedRealtime())
        EventLog.add("overlay", "renderer switched to ${overlay.activeName()}")
    }

    /** Dev action: flash the border green, then red. */
    fun flashBorderTest() = overlay.flashTest()

    /**
     * Dev action: force the navigation layout on/off without a navigation app.
     *
     * The real switch is the navigation notification listener (next commit);
     * this exists so the HUD can be seen, and its drag/panel/dismiss behaviour
     * exercised, on a bench with no Maps session running.
     */
    fun setNavLayout(enabled: Boolean) = overlay.setNavLayout(enabled)

    fun navLayout(): Boolean = overlay.navLayout()

    /** Dev action: the last assistant output, for the dev screen. */
    fun assistantOutput(): String {
        val out = AssistantHost.output ?: return "no output yet"
        return "gear=${out.gear ?: "G"} cue=${out.cue ?: "-"} overRev=${out.overRev} " +
            "mode=${out.mode} demand=${out.demand?.let { String.format("%.2f", it) } ?: "-"} " +
            "reason=${out.reason}"
    }

    /**
     * Dev action: feeds a synthetic drive through the assistant and the overlay.
     *
     * This exists because the ESP32 is on a bench with the transceiver unplugged,
     * so `rpm`/`speed` are always null and the cue logic could never be seen.
     * It touches nothing but the app's own flows - no CAN frame is built or sent.
     */
    /** Dev only: paint a cue on the bubble without going through the assistant. */
    fun previewCue(cue: Cue?) = overlay.previewCue(cue)

    /** Idle lead-in for [startSynthetic], in seconds. */
    private val IDLE_PHASE_S = 6.0

    fun startSynthetic(seconds: Int = 20) {
        val ratios = listOf(150.0, 100.0, 70.0, 46.0, 30.0)
        var gear = 3
        scope.launch {
            EventLog.add("synthetic", "start ${seconds}s (app-side only, no CAN send)")
            syntheticActive = true
            syntheticTrip = true
            val t0 = SystemClock.elapsedRealtime()
            var i = 0
            while (SystemClock.elapsedRealtime() - t0 < seconds * 1000L) {
                val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
                // 6 s of genuine idle first: the guided calibration's first step
                // needs a steady rpm, and without this the only bench demo of the
                // wizard would fail at step one. Then ramp to 80, then brake down.
                val speed = when {
                    t < IDLE_PHASE_S -> 0.0
                    t < 14.0 -> minOf(80.0, (t - IDLE_PHASE_S) * 10.0)
                    else -> maxOf(20.0, 80.0 - (t - 14.0) * 12.0)
                }
                var rpm = if (t < IDLE_PHASE_S) 750.0 + (i % 3) else speed * ratios[gear - 1]
                if (rpm > 4400 && gear < 5) {
                    gear++
                    rpm = speed * ratios[gear - 1]
                    EventLog.add("synthetic", "gear up -> $gear")
                }
                val frame = Telemetry(
                    seq = 900_000L + i,
                    tMs = (SystemClock.elapsedRealtime() - t0),
                    rpm = rpm.toFloat(),
                    speedKph = speed.toFloat(),
                    brake = t >= 14.0,
                    // Stomp, not a ramp: a real pedal reaches full scale in one
                    // frame, and the guided wizard captures the peak of the press.
                    thr1 = when {
                        t < IDLE_PHASE_S -> 37
                        t < 14.0 -> 240
                        else -> 40
                    },
                    thr2 = 100,
                    engineRunning = true,
                    canActive = true,
                    tempRaw = 120,
                    distRaw = (speed * t).toInt(),
                    volt = 13.9f
                )
                lastFrameElapsed = SystemClock.elapsedRealtime()
                silenceHandled = false
                val out = AssistantHost.onFrame(frame, settings)
                deliver(frame, out)
                tripRecorder.onFrame(frame)
                logTripFrame(frame)
                frames.emit(frame)
                i++
                delay(100)
            }
            syntheticActive = false
            EventLog.add("synthetic", "done: ${i} frames, last=${AssistantHost.output?.let { "gear=${it.gear} cue=${it.cue}" }}")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (retryJob?.isActive == true) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        EventLog.add("sse", "reconnect in ${wait}ms ($reason)")
        retryJob = scope.launch {
            delay(wait)
            // Cleared before the attempt so a failure inside connectStream()
            // can schedule the next one instead of being dropped as "active".
            retryJob = null
            connectStream()
        }
    }

    /**
     * A dead ESP link is indistinguishable from a parked car on the bubble: no
     * frames arrive, so the last rpm/brake stay frozen on screen forever and the
     * trip row stays open. After [STREAM_LOST_MS] the displayed values are cleared;
     * after [TRIP_END_AFTER_SILENCE_MS] the trip is closed and saved, exactly as
     * if the ignition had gone quiet.
     */
    private fun checkSilence(nowMs: Long) {
        val last = lastFrameElapsed
        if (last < 0) return
        val age = nowMs - last
        if (age < STREAM_LOST_MS) {
            silenceHandled = false
            return
        }
        if (!silenceHandled) {
            silenceHandled = true
            AssistantHost.reset()
            overlay.onStreamLost(age)
            EventLog.add("stream", "no frames for ${age / 1000}s - values cleared")
        }
        if (age >= TRIP_END_AFTER_SILENCE_MS && tripRecorder.recording) {
            val finished = tripRecorder.endOnSilence()
            EventLog.add("trip", "no frames for ${age / 1000}s - closing trip")
            when {
                finished == null -> {}
                syntheticActive || syntheticTrip -> {
                    syntheticTrip = false
                    // Leaving the row open is what made the old startup cleaner
                    // necessary; now it would be salvaged as a real drive, so
                    // delete it (and anything still buffered for it) outright.
                    val id = currentTripId
                    currentTripId = null
                    if (id != null) {
                        scope.launch(Dispatchers.IO) {
                            val batch = drainFrames()
                            runCatching {
                                if (batch.isNotEmpty()) trips.insertFrames(batch)
                                trips.deleteFrames(id)
                                trips.delete(id)
                            }.onFailure {
                                EventLog.add(
                                    "trip",
                                    "demo row cleanup failed: ${it.javaClass.simpleName}"
                                )
                            }
                        }
                    }
                    EventLog.add(
                        "trip",
                        "synthetic trip closed on silence - deleted (bench data)"
                    )
                }
                else -> saveTrip(finished)
            }
        }
    }

    /**
     * The one place that decides whether the bubble should be on screen.
     *
     * AUTO (the default) draws it whenever our own UI is not on screen. The
     * trigger is our app being minimised, not another app being launched: press
     * Home with nothing else open and the bubble still comes up, because the
     * driver has left the cockpit. It arms after [AUTO_ARM_MS] of continuous
     * traffic, goes away after [STREAM_LOST_MS] of silence, and a drop on the
     * hide target suppresses it until the bus has been quiet once.
     *
     * ALWAYS is the old `show_overlay = true` behaviour: on for as long as the
     * service lives, our own screens included. SHADOW beats both, because the
     * assistant must not draw anything in that mode.
     *
     * Called once a second and immediately on every foreground change. Every
     * branch is idempotent and the renderers guard their own show/hide, so a
     * repeated decision costs nothing.
     */
    private fun evaluateOverlay(nowMs: Long) {
        val mode = settings.overlayMode
        val quiet = overlayFrameElapsed < 0 || nowMs - overlayFrameElapsed > STREAM_LOST_MS
        if (quiet && mode == OverlayMode.AUTO) {
            // The car has gone quiet: a dismissal only held for that drive, so
            // AUTO re-arms for the next one. ALWAYS keeps the dismissal until
            // the driver asks for the bubble again - in that mode "comes back
            // when the ignition is off" would be the opposite of what "always"
            // means, and it would paint a NO LINK bubble over a parked car.
            dismissedByDriver = false
        }
        val wanted = when {
            mode == OverlayMode.OFF -> false
            AssistantHost.shadow -> false
            dismissedByDriver -> false
            mode == OverlayMode.ALWAYS -> true
            else -> autoWantsOverlay(nowMs, quiet)
        }

        if (wanted == overlayWanted) {
            // The permission can be granted (or revoked) while the policy
            // already wants the bubble. Ask again only once the system allows
            // it, so a missing permission does not log an attempt every second.
            if (wanted && !overlay.isShown() && overlay.canDraw()) overlay.show()
            else if (!wanted && overlay.isShown()) overlay.hide()
            return
        }

        overlayWanted = wanted
        if (wanted) overlay.show() else overlay.hide()
        EventLog.add(
            "overlay",
            "policy ${if (wanted) "show" else "hide"}: ${mode.key} " +
                "fg=${AppForeground.isUiVisible()} dismissed=$dismissedByDriver " +
                "age=${if (overlayFrameElapsed < 0) "-" else (nowMs - overlayFrameElapsed).toString()}ms",
        )
    }

    /**
     * AUTO: our app is not on screen, the bus is talking, and the traffic has
     * been continuous long enough to be a drive rather than a stray frame.
     *
     * "Not on screen" is the whole trigger. Whatever the driver opened behind
     * us - the launcher, Maps, nothing at all - the bubble is wanted.
     */
    private fun autoWantsOverlay(nowMs: Long, quiet: Boolean): Boolean {
        if (AppForeground.isUiVisible()) return false
        if (quiet) return false
        val since = burstStartElapsed
        return since >= 0 && nowMs - since >= AUTO_ARM_MS
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        stats.value = statsTracker.snapshot(now)

        val (transport, validated) = WifiController.describeDefault(this)
        link.value = LinkState(
            defaultTransport = transport,
            wifiValidated = validated,
            espNetwork = WifiController.espNetwork?.toString() ?: "-",
            reachable = link.value.reachable,
            lastError = if (lastFrameElapsed >= 0 && now - lastFrameElapsed > 2000) STALE else null
        )
        publishTrip(now)
    }

    /**
     * One-second snapshot of the trip, from the recorder the frames already go
     * through. Nothing is recomputed here: the elapsed time, the distance and the
     * score all come from TripAccountant so the live badge cannot drift away from
     * the row that gets saved.
     */
    private fun publishTrip(nowElapsed: Long) {
        val recording = tripRecorder.recording
        trip.value = TripUi(
            recording = recording,
            elapsedMs = if (recording) tripRecorder.elapsedMs else 0L,
            distanceKm = if (recording) tripRecorder.distanceKm else 0.0,
            ecoScore = if (recording) tripRecorder.liveEcoScore(nowElapsed) else null,
            serviceRemainingKm = serviceRemainingKm,
        )
        serviceTick++
        if (serviceTick % 60 == 0) refreshServiceRemaining()
    }

    /**
     * Remaining kilometres to the next whole service interval, computed from the
     * trip totals already in Room. Once a minute is plenty: this is a garage
     * number, not telemetry.
     */
    private fun refreshServiceRemaining() {
        val interval = settings.serviceIntervalKm
        if (interval <= 0) {
            serviceRemainingKm = null
            return
        }
        scope.launch {
            val total = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) { trips.totalKm() }
            }.getOrNull() ?: return@launch
            val mark = Math.ceil(total / interval) * interval
            serviceRemainingKm = (mark - total).coerceAtLeast(0.0)
        }
    }
}
