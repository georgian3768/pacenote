package com.pocketmechanic.spike

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb

/**
 * Welcome splash. Its only job is to decide the first screen, so it is timed:
 * the requirement is < 2.5 s from launch to a usable screen, and this activity
 * routes after 350 ms (measured on device, see docs/PROGRESS.md).
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokens = themeTokens()
        applySystemBarAppearance(tokens)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(tokens.page.toArgb())
        }
        root.addView(TextView(this).apply {
            text = "Pacenote"
            textSize = 30f
            setTextColor(tokens.ink.toArgb())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "telemetry for the car you already own"
            textSize = 14f
            setTextColor(tokens.dim.toArgb())
            gravity = Gravity.CENTER
        })
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (AppSettings(this).onboarded) DashboardActivity::class.java
            else OnboardingActivity::class.java
            startActivity(Intent(this, next))
            finish()
        }, 350)
    }
}
