package com.pocketmechanic.spike

import android.content.Context
import com.pocketmechanic.shift.CueRow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Append-only frame-level cue log (`files/cues.csv`).
 *
 * Written **on the stream thread**, deliberately: one line per profile per
 * frame, in order, with no queue and no executor. The point is to have the
 * decision for every frame after a drive, so a cue that did not fire can be
 * explained - not to be fast. (The ring-buffer [EventLog] is the wrong tool: it
 * drops the oldest lines and only records events, never the frames between
 * them.)
 *
 * The audio path never touches this file: rows are written after the assistant
 * returns, and [write] only appends to a buffered writer.
 *
 * Pull with:
 *   adb exec-out run-as com.pocketmechanic.spike cat files/cues.csv
 */
object CueLogFile {
    private const val FILE_NAME = "cues.csv"
    private const val MAX_BYTES = 8_000_000L
    private const val FLUSH_EVERY = 32

    private var file: File? = null
    private var writer: BufferedWriter? = null
    private var unflushed = 0
    private var bytes = 0L

    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val f = File(context.filesDir, FILE_NAME)
        val fresh = !f.exists() || f.length() == 0L
        file = f
        bytes = f.length()
        writer = BufferedWriter(FileWriter(f, true)).also {
            if (fresh) {
                it.write(CueRow.HEADER)
                it.newLine()
                it.flush()
            }
        }
    }

    /** Append one row. Stream thread only; no-op until [init]. */
    @Synchronized
    fun write(row: CueRow) {
        val out = writer ?: return
        val line = row.csv()
        out.write(line)
        out.newLine()
        bytes += line.length + 1
        if (++unflushed >= FLUSH_EVERY) flushLocked()
        if (bytes > MAX_BYTES) rotateLocked()
    }

    @Synchronized
    fun flush() = flushLocked()

    private fun flushLocked() {
        writer?.flush()
        unflushed = 0
    }

    /**
     * Keeps one previous file. Rotation is rare (8 MB is roughly 18 hours of
     * three-profile shadow driving) and never drops the current drive.
     */
    private fun rotateLocked() {
        val out = writer ?: return
        val f = file ?: return
        runCatching {
            out.flush()
            out.close()
            val old = File(f.parentFile, "$FILE_NAME.1")
            if (old.exists()) old.delete()
            f.renameTo(old)
            writer = BufferedWriter(FileWriter(f, true)).also {
                it.write(CueRow.HEADER)
                it.newLine()
                it.flush()
            }
            bytes = 0
            unflushed = 0
        }
    }
}
