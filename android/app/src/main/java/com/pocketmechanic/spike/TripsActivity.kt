package com.pocketmechanic.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recorded trips: one row per drive, and two exports.
 *
 *  - tap a trip  -> that trip's **per-frame** CSV (10 Hz telemetry), which is what
 *    replaces the laptop + SavvyCAN for tuning the shift thresholds;
 *  - "Share summary" -> the one-row-per-trip aggregate CSV.
 *
 * Both are written to Downloads/Pacenote/ (MediaStore) and handed to the
 * share sheet, so the file survives cache clearing either way.
 */
class TripsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var trips: List<TripRow> = emptyList()

    /** Resolved once: a system light/dark switch recreates the activity. */
    private val tokens by lazy { themeTokens() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarAppearance(tokens)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(tokens.page.toArgb())
        }

        root.addView(TextView(this).apply {
            text = "Trips"
            textSize = 28f
            setTextColor(tokens.ink.toArgb())
        })

        summary = TextView(this).apply {
            textSize = 13f
            setTextColor(tokens.dim.toArgb())
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(summary)

        root.addView(TextView(this).apply {
            text = "Tap a trip for its 10 Hz frame CSV (saved to Downloads/Pacenote)."
            textSize = 11f
            setTextColor(tokens.faint.toArgb())
            setPadding(0, 0, 0, dp(12))
        })

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            list,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(Button(this).apply {
            text = "Share summary CSV"
            setOnClickListener { shareSummary() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val service = SpikeService.instance
        if (service == null) {
            summary.text = "service not running: trips cannot be read"
            return
        }
        scope.launch {
            trips = service.listTrips()
            val total = service.totalKm()
            val interval = AppSettings(this@TripsActivity).serviceIntervalKm
            summary.text = buildString {
                append("%d trips · %.1f km total".format(trips.size, total))
                if (interval > 0) {
                    val next = (Math.floor(total / interval) + 1) * interval
                    append(" · next reminder at %.0f km (in %.0f km)".format(next, next - total))
                }
                append(" · live: ${service.tripsSummary()}")
            }
            list.removeAllViews()
            if (trips.isEmpty()) {
                list.addView(TextView(this@TripsActivity).apply {
                    text = "No trips yet. A trip starts when the engine-running bit is set and " +
                        "ends 10 s after the CAN stream goes silent."
                    textSize = 13f
                    setTextColor(tokens.dim.toArgb())
                })
            }
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
            trips.forEach { t ->
                val seconds = (t.endedAt - t.startedAt) / 1000
                val frames = service.frameCount(t.id)
                val row = TextView(this@TripsActivity).apply {
                    text = buildString {
                        append(fmt.format(Date(t.startedAt)))
                        append("  ·  %.2f km".format(t.distanceKm))
                        append(if (seconds <= 0) "  ·  in progress" else if (seconds < 60)
                            "  ·  %d s".format(seconds) else "  ·  %.0f min".format(seconds / 60.0))
                        append("  ·  score ${t.ecoScore}")
                        if (!t.redlineConfirmed) append(" (provisional)")
                        append("\n")
                        append("max ${t.maxRpm} rpm · avg ${t.avgRpm} · over-rev %.1f s".format(t.overRevMs / 1000.0))
                        append(" · ${t.hardAccelCount} hard accel · $frames frames")
                        t.distRawRatio?.let { append("\ndist_raw %.0f/km".format(it)) }
                        if (t.parkedLat != null) {
                            append("\nparked %.5f, %.5f".format(t.parkedLat, t.parkedLon))
                        }
                    }
                    textSize = 13f
                    setTextColor(tokens.action.toArgb())
                    setPadding(0, 0, 0, (14 * resources.displayMetrics.density).toInt())
                    setOnClickListener { shareFrames(t) }
                }
                list.addView(row)
            }
        }
    }

    private fun shareFrames(trip: TripRow) {
        val service = SpikeService.instance ?: return
        scope.launch {
            val frames = service.frames(trip.id)
            if (frames.isEmpty()) {
                EventLog.add("trips", "trip #${trip.id} has no frames (recorded before v2?)")
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(trip.startedAt))
            share(
                "trip-$stamp.csv",
                TripFormat.framesCsv(trip, frames),
                "Pacenote trip $stamp (${frames.size} frames)"
            )
        }
    }

    private fun shareSummary() {
        if (trips.isEmpty()) {
            EventLog.add("trips", "summary: nothing to export")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        share(
            "trips-$stamp.csv",
            TripFormat.csv(trips),
            "Pacenote trips (${trips.size})"
        )
    }

    /** Writes to Downloads/Pacenote and opens the share sheet for that URI. */
    private fun share(name: String, content: String, subject: String) {
        runCatching {
            val uri = Exports.writeCsv(this@TripsActivity, name, content)
            EventLog.add("trips", "wrote Downloads/Pacenote/$name (${content.length} bytes)")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share $name"))
        }.onFailure {
            EventLog.add("trips", "export failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
