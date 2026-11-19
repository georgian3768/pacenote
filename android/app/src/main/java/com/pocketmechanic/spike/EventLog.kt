package com.pocketmechanic.spike

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Timestamped log that the spike screen can show and export. Bounded in memory so
 * a long stream cannot exhaust it; everything here is meant to be pasted back
 * verbatim when a device test fails.
 *
 * Also mirrored to `files/eventlog.txt`. The in-memory ring and logcat both died
 * before a failed in-car calibration could be inspected (2026-11-05: the ring
 * survived the restart but logcat had already rotated), so the file is the copy
 * that survives a process death and can be pulled with
 * `adb exec-out run-as <pkg> cat files/eventlog.txt`.
 */
object EventLog {
    private const val MAX_LINES = 4000
    private const val TAG = "PocketMechanic"
    private const val FILE_NAME = "eventlog.txt"
    private const val MAX_FILE_BYTES = 1_000_000L

    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Single writer thread: logging must never block the SSE or main thread. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eventlog").apply { isDaemon = true }
    }

    @Volatile
    private var logFile: File? = null

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${format.format(Date())} [$tag] $message"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        Log.i(TAG, line)

        val file = logFile ?: return
        writer.execute {
            runCatching {
                if (file.length() > MAX_FILE_BYTES) {
                    File(file.parentFile, "$FILE_NAME.1").let { old ->
                        if (old.exists()) old.delete()
                        file.renameTo(old)
                    }
                }
                file.appendText(line + "\n")
            }
        }
    }

    /** Called once from the service; enables the on-disk mirror. */
    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
