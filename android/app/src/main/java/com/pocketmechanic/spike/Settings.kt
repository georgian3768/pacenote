package com.pocketmechanic.spike

import android.content.Context
import com.pocketmechanic.shift.AssistantMode
import com.pocketmechanic.shift.DriveMode

/** Metric or imperial display; the wire format stays metric (km/h, °C). */
enum class Units { METRIC, IMPERIAL }

/**
 * When the floating bubble is on screen.
 *
 * AUTO is the default because the bubble exists for driving, not for looking at
 * the app: while one of our screens is visible the panel already shows the same
 * numbers, and an overlay on top of it is two renderers fighting. ALWAYS is the
 * escape hatch for anyone who wants it over everything, including our own
 * screens; OFF is off.
 */
enum class OverlayMode(val key: String) {
    AUTO("auto"),
    ALWAYS("always"),
    OFF("off");

    /** True when this mode can put the bubble on screen at all. */
    val draws: Boolean get() = this != OFF

    /** Tap order for the menu row and the landing row. */
    fun next(): OverlayMode = when (this) {
        AUTO -> ALWAYS
        ALWAYS -> OFF
        OFF -> AUTO
    }

    companion object {
        val DEFAULT = AUTO
        fun of(raw: String?): OverlayMode? = entries.firstOrNull { it.key == raw?.lowercase() }
    }
}

/** Music targets the panel can open, most specific first. */
val MUSIC_CANDIDATES = listOf(
    "app.revanced.android.apps.youtube.music",
    "com.google.android.apps.youtube.music",
    "com.spotify.music",
)

/**
 * Car profile. The Celerio's redline is not broadcast on the CAN bus, so the
 * default comes from the K10B engine's published numbers: the owner's manual
 * states the fuel cut in neutral is at about 6400 rpm, and peak power is
 * 67 bhp @ 6000 rpm (peak torque 90 Nm @ 3500). 6400 is therefore the "engine
 * limit" the over-rev cue is measured against; the 6000 power peak is what the
 * shift advice lands on. Any value here is still a default the driver confirms
 * during onboarding (see docs/PROGRESS.md, 2026-11-09).
 */
data class CarProfile(
    val name: String = "Maruti Celerio 2016",
    val gearCount: Int = 5,
    val idleRpm: Int = 700,
    val redlineRpm: Int = 6400,
) {
    companion object {
        const val GEARS_MIN = 3
        const val GEARS_MAX = 8
        const val IDLE_MIN = 300
        const val IDLE_MAX = 2000
        const val REDLINE_MIN = 3000
        const val REDLINE_MAX = 12000
    }

    fun problems(): List<String> = buildList {
        if (gearCount !in GEARS_MIN..GEARS_MAX) add("gears must be ${GEARS_MIN}–${GEARS_MAX}")
        if (idleRpm !in IDLE_MIN..IDLE_MAX) add("idle must be ${IDLE_MIN}–${IDLE_MAX} rpm")
        if (redlineRpm !in REDLINE_MIN..REDLINE_MAX) add("redline must be ${REDLINE_MIN}–${REDLINE_MAX} rpm")
        if (redlineRpm <= idleRpm) add("redline must be above idle")
    }
}

/**
 * Single source of truth for user settings. Deliberately tiny and synchronous:
 * it is read on the splash path, so it must never touch disk off the main
 * thread in a way that delays the first screen.
 */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("pm_settings", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    var driverName: String
        get() = prefs.getString("driver_name", "") ?: ""
        set(value) = prefs.edit().putString("driver_name", value).apply()

    var units: Units
        get() = if (prefs.getString("units", "metric") == "imperial") Units.IMPERIAL else Units.METRIC
        set(value) = prefs.edit().putString("units", value.name.lowercase()).apply()

    /**
     * The assistant's mode: ECO / NORMAL / SPORT, or SHADOW (all three run,
     * nothing is drawn). Stored separately from the legacy `drive_mode` key so
     * an existing install keeps its selected profile.
     */
    var assistantMode: AssistantMode
        get() = AssistantMode.of(
            prefs.getString(
                "assistant_mode",
                prefs.getString("drive_mode", AssistantMode.DEFAULT.name.lowercase()),
            )
        )
        set(value) = prefs.edit().putString("assistant_mode", value.name.lowercase()).apply()

    /** Eco / Normal / Sport: the shift profile the UI chip cycles. */
    var driveMode: DriveMode
        get() = assistantMode.profile ?: DriveMode.DEFAULT
        set(value) {
            assistantMode = AssistantMode.of(value.name)
        }

    /** Below this speed the assistant reports no gear (clutch / neutral). */
    var minSpeedKph: Double
        get() = prefs.getFloat("min_speed_kph", 15f).toDouble()
        set(value) = prefs.edit().putFloat("min_speed_kph", value.toFloat()).apply()

    /** Full-scale raw value of the throttle channel, measured by the wizard. */
    var throttleFullRaw: Int
        get() = prefs.getInt("throttle_full_raw", 255)
        set(value) = prefs.edit().putInt("throttle_full_raw", value).apply()

    /** Closed-throttle raw value, measured by the wizard. */
    var throttleIdleRaw: Int
        get() = prefs.getInt("throttle_idle_raw", -1)
        set(value) = prefs.edit().putInt("throttle_idle_raw", value).apply()

    /**
     * rpm-per-km/h per gear, produced by the gear wizard. Empty means "use the
     * provisional ratios" - the assistant falls back to its defaults.
     */
    var gearRatios: List<Double>
        get() = prefs.getString("gear_ratios", "")!!
            .split(',')
            .mapNotNull { it.trim().toDoubleOrNull() }
            .filter { it > 0.0 }
        set(value) = prefs.edit().putString("gear_ratios", value.joinToString(",")).apply()

    /** Multiplier from CAN speed to GPS speed; 1.0 until the GPS wizard runs. */
    var gpsSpeedScale: Double
        get() = prefs.getFloat("gps_speed_scale", 1f).toDouble()
        set(value) = prefs.edit().putFloat("gps_speed_scale", value.toFloat()).apply()

    /**
     * Package the panel's Music button opens. Default is YouTube Music ReVanced
     * because that is what is actually installed on this phone; the stock
     * package and Spotify are kept as fallbacks in OverlayController.
     */
    var musicPackage: String
        get() = prefs.getString("music_package", MUSIC_CANDIDATES.first())!!
        set(value) = prefs.edit().putString("music_package", value).apply()

    /**
     * How the floating bubble is presented: AUTO (background only, the default),
     * ALWAYS, or OFF.
     *
     * Migrated from the old `show_overlay` boolean on first read: an install
     * that had the bubble on had chosen "over other apps, always", and an
     * install that had it off had never chosen anything, so it inherits the new
     * automatic default rather than staying silently off.
     */
    var overlayMode: OverlayMode
        get() {
            OverlayMode.of(prefs.getString("overlay_mode", null))?.let { return it }
            val migrated =
                if (prefs.getBoolean("show_overlay", false)) OverlayMode.ALWAYS else OverlayMode.DEFAULT
            prefs.edit().putString("overlay_mode", migrated.key).apply()
            return migrated
        }
        set(value) = prefs.edit().putString("overlay_mode", value.key).apply()

    /**
     * Use the Compose bubble instead of the legacy View overlay. Off by default:
     * the rewrite is a debug path until it has been driven, and the legacy
     * overlay stays selectable as a fallback.
     */
    var composeOverlay: Boolean
        get() = prefs.getBoolean("compose_overlay", false)
        set(value) = prefs.edit().putBoolean("compose_overlay", value).apply()

    /**
     * Keep the display awake while the panel or bubble is visible.
     *
     * A window flag, not a wake lock: Android drops it as soon as the window is
     * gone, so a crash or a finished drive cannot leave the phone lit. The flag
     * is set on every surface the driver looks at (dashboard, card, bubble);
     * the system timeout still applies when none of them is on screen.
     */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    /**
     * True once the ESP32 was saved through the system "add network" dialog.
     * Only used to word the menu row; the network itself lives in the platform's
     * saved list, not here.
     */
    var espNetworkSaved: Boolean
        get() = prefs.getBoolean("esp_network_saved", false)
        set(value) = prefs.edit().putBoolean("esp_network_saved", value).apply()

    /**
     * Service-interval reminder in km; 0 disables it. Purely a distance counter:
     * nothing about oil life or the car's own schedule is known.
     */
    var serviceIntervalKm: Int
        get() = prefs.getInt("service_interval_km", 500)
        set(value) = prefs.edit().putInt("service_interval_km", value).apply()

    /** Beep on a shift cue (ducks music instead of pausing it). */
    var audioCues: Boolean
        get() = prefs.getBoolean("audio_cues", true)
        set(value) = prefs.edit().putBoolean("audio_cues", value).apply()

    /** Show the click-through green/red border flash. */
    var showBorder: Boolean
        get() = prefs.getBoolean("show_border", true)
        set(value) = prefs.edit().putBoolean("show_border", value).apply()

    /**
     * The redline the driver explicitly confirmed on the calibration screen; 0
     * when nothing has been confirmed. Storing the value rather than a flag means
     * editing the redline re-arms the gate by itself.
     */
    var confirmedRedlineRpm: Int
        get() = prefs.getInt("car_redline_confirmed", 0)
        set(value) = prefs.edit().putInt("car_redline_confirmed", value).apply()

    /** True when the profile's redline is the number the driver confirmed. */
    val redlineTrusted: Boolean get() = confirmedRedlineRpm == car.redlineRpm

    var car: CarProfile
        get() = CarProfile(
            name = prefs.getString("car_name", CarProfile().name) ?: CarProfile().name,
            gearCount = prefs.getInt("car_gears", 5),
            idleRpm = prefs.getInt("car_idle", 700),
            redlineRpm = prefs.getInt("car_redline", 6400),
        )
        set(value) = prefs.edit()
            .putString("car_name", value.name)
            .putInt("car_gears", value.gearCount)
            .putInt("car_idle", value.idleRpm)
            .putInt("car_redline", value.redlineRpm)
            .apply()
}
