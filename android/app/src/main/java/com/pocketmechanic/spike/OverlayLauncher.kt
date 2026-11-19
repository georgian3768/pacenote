package com.pocketmechanic.spike

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Launches the card's shortcut targets (Maps, music). Shared by both overlay
 * renderers so a package-list change cannot fix one and miss the other.
 *
 * Android 11+ hides packages unless they are declared in `<queries>`; without
 * that this silently falls back to a browser (measured 2026-10-31).
 */
object OverlayLauncher {

    fun openMusic(context: Context, settings: AppSettings) {
        launch(
            context,
            (listOf(settings.musicPackage) + MUSIC_CANDIDATES).distinct(),
            "https://music.youtube.com/",
        )
    }

    /**
     * The calibration wizard is the only place the redline can be confirmed, so
     * the card needs to reach it without the dev screen.
     */
    fun openCalibration(context: Context) {
        try {
            context.startActivity(
                Intent(context, CalibrationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            EventLog.add("overlay", "opened calibration")
        } catch (t: Throwable) {
            EventLog.add("overlay", "calibration launch failed: ${t.javaClass.simpleName}")
        }
    }

    fun openMaps(context: Context) {
        launch(context, listOf("com.google.android.apps.maps"), "geo:0,0?q=")
    }

    private fun launch(context: Context, packages: List<String>, fallbackUri: String?) {
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                EventLog.add("overlay", "launched $pkg")
                return
            } catch (t: Throwable) {
                EventLog.add("overlay", "launch $pkg failed: ${t.javaClass.simpleName}")
            }
        }
        val uris = buildList {
            fallbackUri?.let { add(it) }
            packages.forEach { add("https://play.google.com/store/apps/details?id=$it") }
        }
        for (uri in uris) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                EventLog.add("overlay", "opened $uri (no launcher activity found)")
                return
            } catch (t: Throwable) {
                // try the next fallback
            }
        }
        EventLog.add("overlay", "could not launch any of $packages")
    }
}
