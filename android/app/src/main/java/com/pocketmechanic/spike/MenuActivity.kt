package com.pocketmechanic.spike

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb

/**
 * The one customer-facing entry point to everything that is not the dashboard.
 *
 * Why it exists: the dashboard used to carry a visible "dev" button to
 * [MainActivity], which also happened to be the only route to trips, calibration
 * and the vehicle settings. Hiding that button for customers would have made
 * those screens unreachable, so they get a real menu instead and the developer
 * screen is demoted to a dimmed row at the bottom rather than removed.
 *
 * Deliberately plain: programmatic views, one ScrollView, no ActionBar and no
 * layouts or drawables of its own, matching how the other screens are built.
 * A system back press just finishes the activity, which returns to the
 * dashboard because every row is launched in the same task.
 */
class MenuActivity : Activity() {

    private companion object {
        const val REQ_SAVE_WIFI = 42
    }

    private lateinit var settings: AppSettings

    /** Resolved once: a system light/dark switch recreates the activity. */
    private val tokens by lazy { themeTokens() }

    /** Kept so the overlay row's description can be refreshed after a toggle. */
    private lateinit var overlayDesc: TextView

    /** Kept so the screen-on row's description can be refreshed after a toggle. */
    private lateinit var keepOnDesc: TextView

    /** Kept so the Wi-Fi row can show the current link when it comes back. */
    private lateinit var wifiDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        applySystemBarAppearance(tokens)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::wifiDesc.isInitialized) wifiDesc.text = wifiDescription()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
            setBackgroundColor(tokens.page.toArgb())
        }

        root.addView(TextView(this).apply {
            text = "Pacenote"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(tokens.ink.toArgb())
        })
        root.addView(TextView(this).apply {
            text = "Trips, calibration and settings. The dashboard stays one back press away."
            textSize = 13f
            setTextColor(tokens.dim.toArgb())
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, dp(20))
        })

        addRow(
            root,
            title = "Trip history",
            description = "Every recorded drive with distance, score, max rpm, " +
                "and a per-frame CSV export for tuning the shift points.",
        ) { open(TripsActivity::class.java) }

        addRow(
            root,
            title = "Sensor calibration",
            description = "One guided run measures the throttle range, every gear ratio and " +
                "the GPS speed scale. It is listen-only - nothing is written to the car - " +
                "and a run can be stopped and resumed later.",
        ) { open(CalibrationActivity::class.java) }

        addRow(
            root,
            title = "Vehicle & driver",
            description = "Driver name, units and the car profile (gears, idle, redline), " +
                "plus the permissions the stream needs.",
        ) { open(OnboardingActivity::class.java) }

        overlayDesc = addRow(
            root,
            title = "Floating overlay",
            description = overlayDescription(),
        ) { cycleOverlay() }

        keepOnDesc = addRow(
            root,
            title = "Keep screen on",
            description = keepScreenOnDescription(),
        ) { toggleKeepScreenOn() }

        wifiDesc = addRow(
            root,
            title = "ESP32 Wi-Fi",
            description = wifiDescription(),
        ) { wifiAction() }

        root.addView(sectionLabel("Developer"))
        addRow(
            root,
            title = "Diagnostics",
            description = "Developer tools: link state, event log, stream proof.",
            demoted = true,
        ) { open(MainActivity::class.java) }

        return ScrollView(this).apply {
            setBackgroundColor(tokens.page.toArgb())
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // --- navigation -----------------------------------------------------------

    /**
     * Same-task launch (no NEW_TASK): a back press then walks back through this
     * menu to the dashboard, which is the behaviour the task asked for and needs
     * no exit confirmation.
     */
    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    // --- overlay mode ---------------------------------------------------------

    /**
     * The bubble is owned by [SpikeService], and the preference is only read by
     * the policy tick, so a bare write would be ignored until the next tick. The
     * change is pushed through the live instance when there is one; otherwise
     * the service is cold-started and reads the new mode itself.
     *
     * Caveat: showing the bubble also needs the "draw over other apps"
     * permission. Without it the service logs the attempt and no-ops, so the
     * toast says "armed" instead of claiming it is visible.
     */
    private fun cycleOverlay() {
        val next = settings.overlayMode.next()
        val canDraw = Settings.canDrawOverlays(this)
        settings.overlayMode = next

        val running = SpikeService.instance
        if (running != null) {
            running.setOverlayMode(next)
        } else {
            SpikeService.start(this)
        }
        EventLog.add(
            "overlay",
            "menu: overlay mode ${next.key} " +
                "(serviceRunning=${running != null} canDraw=$canDraw)",
        )

        overlayDesc.text = overlayDescription()
        val message = when {
            next == OverlayMode.OFF -> "overlay off"
            next == OverlayMode.AUTO && canDraw -> "overlay: auto (in other apps, while driving)"
            next == OverlayMode.ALWAYS && canDraw -> "overlay: always on"
            else -> "overlay armed - allow 'draw over other apps' to see it"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun overlayDescription(): String = when (settings.overlayMode) {
        OverlayMode.AUTO ->
            "Auto - the bubble appears over other apps once the car is streaming, " +
                "and stays out of the way while this app is open."
        OverlayMode.ALWAYS ->
            "Always - on whenever the service runs, including over this app."
        OverlayMode.OFF ->
            if (Settings.canDrawOverlays(this)) {
                "Off - tap for auto: over other apps while driving."
            } else {
                "Off - tap for auto; it also needs the 'draw over other apps' " +
                    "permission (granted from Vehicle & driver)."
            }
    }

    // --- screen-on ------------------------------------------------------------

    private fun toggleKeepScreenOn() {
        settings.keepScreenOn = !settings.keepScreenOn
        keepOnDesc.text = keepScreenOnDescription()
        // The service owns the overlay windows; patching them live means the
        // flag changes even if the panel is already up over Maps.
        SpikeService.instance?.refreshKeepScreenOn()
        EventLog.add("ui", "menu: keep_screen_on=${settings.keepScreenOn}")
        Toast.makeText(
            this,
            if (settings.keepScreenOn) {
                "screen stays on while the panel is visible"
            } else {
                "system timeout applies"
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun keepScreenOnDescription(): String =
        if (settings.keepScreenOn) {
            "On - the panel and the bubble keep the display awake. The system " +
                "timeout returns as soon as neither is on screen."
        } else {
            "Off - the system screen timeout applies, even over Maps."
        }

    // --- ESP32 Wi-Fi ----------------------------------------------------------

    /**
     * The ESP32 is saved, not merely suggested: a suggestion is not a network
     * the platform is obliged to rejoin after the AP disappears. The dialog is
     * API 30+; API 29 falls back to the suggestion, which is all it has.
     */
    private fun wifiAction() {
        val save = WifiController.saveNetworkIntent()
        if (save != null) {
            startActivityForResult(save, REQ_SAVE_WIFI)
        } else {
            Toast.makeText(this, WifiController.suggest(this), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE_WIFI) return
        val saved = WifiController.saveSucceeded(resultCode, data)
        if (saved) settings.espNetworkSaved = true
        wifiDesc.text = wifiDescription()
        Toast.makeText(
            this,
            if (saved) "Saved - Android can reconnect by itself" else "Not saved",
            Toast.LENGTH_LONG,
        ).show()
        EventLog.add("wifi", "menu add-network dialog: result=$resultCode saved=$saved")
    }

    private fun wifiDescription(): String =
        WifiController.describe(settings.espNetworkSaved, WifiController.espNetwork != null)

    // --- view helpers ---------------------------------------------------------

    /**
     * One tappable card row.
     *
     * The title/description column is weighted and zero-width on purpose: the
     * chevron can then never squeeze the text, so long descriptions wrap instead
     * of clipping, and the row keeps a 72dp minimum touch target.
     *
     * @return the description TextView, so state rows can rewrite it in place.
     */
    private fun addRow(
        parent: LinearLayout,
        title: String,
        description: String,
        demoted: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = title
            textSize = if (demoted) 15f else 18f
            setTypeface(typeface, if (demoted) Typeface.NORMAL else Typeface.BOLD)
            setTextColor(if (demoted) tokens.dim.toArgb() else tokens.ink.toArgb())
        })
        val desc = TextView(this).apply {
            text = description
            textSize = if (demoted) 11f else 12f
            setLineSpacing(0f, 1.2f)
            setTextColor(if (demoted) tokens.faint.toArgb() else tokens.dim.toArgb())
            setPadding(0, dp(4), 0, 0)
        }
        column.addView(desc)

        val chevron = TextView(this).apply {
            text = "›"
            textSize = if (demoted) 16f else 22f
            setTextColor(if (demoted) tokens.faint.toArgb() else tokens.up.toArgb())
            setPadding(dp(10), 0, 0, 0)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            minimumHeight = dp(72)
            background = cardRipple()
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                chevron,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        return desc
    }

    private fun cardBody(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(tokens.card.toArgb())
        setStroke(dp(1), tokens.border.toArgb())
    }

    /**
     * Card fill plus an accent-tinted touch ripple, clipped to the rounded
     * corners by a matching mask shape: the pressed feedback is built in code so
     * no new drawable resource is needed.
     */
    private fun cardRipple(): Drawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x3D2EC27E), cardBody(), mask)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTextColor(tokens.faint.toArgb())
        letterSpacing = 0.12f
        setPadding(0, dp(12), 0, dp(10))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
