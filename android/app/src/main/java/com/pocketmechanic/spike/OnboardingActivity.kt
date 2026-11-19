package com.pocketmechanic.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import android.widget.Toast

/**
 * First-run setup: driver name, units, car profile (gears / idle / redline) and
 * the permission deep-links the rest of the app needs.
 *
 * Everything is a single scrollable form on purpose - the S2 finding is that
 * HyperOS kills background work aggressively, so the fewer screens between
 * launch and a working service the better.
 */
class OnboardingActivity : Activity() {

    private companion object {
        const val REQ_SAVE_WIFI = 41
    }

    /** Resolved once: a system light/dark switch recreates the activity. */
    private val tokens by lazy { themeTokens() }

    private lateinit var settings: AppSettings
    private lateinit var nameField: EditText
    private lateinit var carField: EditText
    private lateinit var gearsField: EditText
    private lateinit var idleField: EditText
    private lateinit var redlineField: EditText
    private lateinit var unitsGroup: RadioGroup
    private lateinit var permStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        applySystemBarAppearance(tokens)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE_WIFI) return
        val saved = WifiController.saveSucceeded(resultCode, data)
        if (saved) settings.espNetworkSaved = true
        Toast.makeText(
            this,
            if (saved) "Saved - Android can reconnect by itself" else "Not saved",
            Toast.LENGTH_LONG,
        ).show()
        EventLog.add("wifi", "add-network dialog: result=$resultCode saved=$saved")
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 56)
            setBackgroundColor(tokens.page.toArgb())
        }

        root.addView(title("Set up Pacenote", 24f))
        root.addView(note("Three fields, then the permissions the stream needs. Everything here can be changed later."))

        root.addView(section("Driver"))
        nameField = field("Name", settings.driverName, InputType.TYPE_CLASS_TEXT)
        root.addView(nameField)

        root.addView(section("Units"))
        unitsGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val metric = RadioButton(this).apply { text = "km/h"; id = 1; setTextColor(tokens.ink.toArgb()) }
        val imperial = RadioButton(this).apply { text = "mph"; id = 2; setTextColor(tokens.ink.toArgb()) }
        unitsGroup.addView(metric)
        unitsGroup.addView(imperial)
        if (settings.units == Units.IMPERIAL) imperial.isChecked = true else metric.isChecked = true
        root.addView(unitsGroup)

        root.addView(section("Car"))
        val car = settings.car
        carField = field("Car", car.name, InputType.TYPE_CLASS_TEXT)
        gearsField = field("Gears", car.gearCount.toString(), InputType.TYPE_CLASS_NUMBER)
        idleField = field("Idle rpm", car.idleRpm.toString(), InputType.TYPE_CLASS_NUMBER)
        redlineField = field("Redline rpm", car.redlineRpm.toString(), InputType.TYPE_CLASS_NUMBER)
        root.addView(carField)
        root.addView(gearsField)
        root.addView(idleField)
        root.addView(redlineField)
        root.addView(note("Redline and idle are starting values, not measurements: both are re-checked by the calibration wizard."))

        root.addView(section("Permissions"))
        permStatus = note("")
        root.addView(permStatus)
        root.addView(button("Notifications") { requestPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })
        root.addView(button("Nearby Wi-Fi (Android 13)") {
            requestPerms(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
        })
        root.addView(button("Location (Wi-Fi scan fallback)") {
            requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        })
        root.addView(button("Battery: no restrictions") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        root.addView(button("Allow background pop-ups (overlay)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        val saveWifi = WifiController.saveNetworkIntent()
        if (saveWifi != null) {
            root.addView(button("Save the ESP32 Wi-Fi network") {
                startActivityForResult(saveWifi, REQ_SAVE_WIFI)
            })
        }
        root.addView(button(
            if (saveWifi == null) "Register the ESP32 Wi-Fi suggestion"
            else "Wi-Fi suggestion (fallback)"
        ) {
            Toast.makeText(this, WifiController.suggest(this), Toast.LENGTH_SHORT).show()
        })

        // The CTA lives outside the ScrollView on purpose: inside it, the button
        // ended up clipped under the gesture bar and could not be tapped
        // (measured on the POCO F3 GT, 2026-10-30).
        val start = button("Start driving") { save() }.apply {
            setPadding(0, 24, 0, 24)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@OnboardingActivity).apply { addView(root) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(start)
        }
    }

    private fun requestPerms(perms: Array<String>) {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(perms, 1)
    }

    private fun refreshPermissionStatus() {
        fun granted(p: String) =
            Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val battery = (getSystemService(android.os.PowerManager::class.java))
            ?.isIgnoringBatteryOptimizations(packageName) == true
        permStatus.text = buildString {
            append("notifications ${if (granted(Manifest.permission.POST_NOTIFICATIONS)) "ok" else "missing"}")
            append("  ·  nearby-wifi ${if (granted(Manifest.permission.NEARBY_WIFI_DEVICES)) "ok" else "missing"}")
            append("\noverlay ${if (overlay) "ok" else "missing"}")
            append("  ·  battery ${if (battery) "unrestricted" else "restricted"}")
        }
    }

    private fun save() {
        val gears = gearsField.text.toString().toIntOrNull() ?: 0
        val idle = idleField.text.toString().toIntOrNull() ?: 0
        val redline = redlineField.text.toString().toIntOrNull() ?: 0
        val car = CarProfile(
            name = carField.text.toString().ifBlank { CarProfile().name },
            gearCount = gears,
            idleRpm = idle,
            redlineRpm = redline,
        )
        val problems = car.problems()
        if (problems.isNotEmpty()) {
            Toast.makeText(this, problems.joinToString("; "), Toast.LENGTH_LONG).show()
            return
        }
        settings.driverName = nameField.text.toString().trim()
        settings.units = if (unitsGroup.checkedRadioButtonId == 2) Units.IMPERIAL else Units.METRIC
        settings.car = car
        settings.onboarded = true
        EventLog.add("app", "onboarding saved: gears=${car.gearCount} idle=${car.idleRpm} redline=${car.redlineRpm} units=${settings.units}")
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    // --- small view helpers ---------------------------------------------------

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(tokens.ink.toArgb())
        setPadding(0, 0, 0, 18)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(tokens.dim.toArgb())
        setPadding(0, 8, 0, 18)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTextColor(tokens.up.toArgb())
        setPadding(0, 28, 0, 10)
    }

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setTextColor(tokens.ink.toArgb())
        setHintTextColor(tokens.faint.toArgb())
        setPadding(0, 16, 0, 16)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
    }
}
