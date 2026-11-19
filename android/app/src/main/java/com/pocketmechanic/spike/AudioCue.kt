package com.pocketmechanic.spike

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pocketmechanic.shift.Cue

/**
 * Short beeps for shift cues, ducking music instead of stopping it.
 *
 * The phone is usually playing something while driving, so the cue must be
 * audible over it: audio focus is requested as GAIN_TRANSIENT_MAY_DUCK (music
 * dips for a moment, does not pause), released immediately afterwards, and the
 * whole thing is rate limited so a flickering cue cannot turn into a siren.
 */
class AudioCue(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var lastPlayedMs = 0L

    private val tone: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME_PERCENT)
    }.getOrElse {
        EventLog.add("audio", "ToneGenerator unavailable: ${it.javaClass.simpleName}")
        null
    }

    fun play(cue: Cue) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayedMs < MIN_GAP_MS) return
        lastPlayedMs = now

        val am = app.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).build()
        val granted = runCatching {
            am?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)

        // Recognisable with the eyes off the screen: up = two rising beeps,
        // down = one low beep, over-rev = three urgent beeps. A single tone per
        // cue (the old behaviour) made up and over-rev sound alike.
        val (kind, plays) = when (cue) {
            Cue.UP -> ToneGenerator.TONE_PROP_BEEP2 to 2
            Cue.DOWN -> ToneGenerator.TONE_PROP_BEEP to 1
            Cue.OVER_REV -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 3
        }
        var failures = 0
        repeat(plays) { i ->
            main.postDelayed({
                val started = runCatching { tone?.startTone(kind, DURATION_MS) }.getOrNull() == true
                if (!started) failures++
            }, i * (DURATION_MS + GAP_MS))
        }
        EventLog.add("audio", "cue ${cue.name.lowercase()} beeps=$plays duck=$granted")
        main.postDelayed({
            if (failures > 0) EventLog.add("audio", "cue ${cue.name.lowercase()} beeps failed=$failures")
        }, plays * (DURATION_MS + GAP_MS) + 10L)

        val total = plays * (DURATION_MS + GAP_MS)
        main.postDelayed({
            runCatching { am?.abandonAudioFocusRequest(request) }
        }, total + 150L)
    }

    fun release() {
        runCatching { tone?.release() }
    }

    companion object {
        private const val VOLUME_PERCENT = 90
        private const val DURATION_MS = 140
        /** Silence between the beeps of a multi-beep pattern. */
        private const val GAP_MS = 130L
        private const val MIN_GAP_MS = 3_000L
    }
}
