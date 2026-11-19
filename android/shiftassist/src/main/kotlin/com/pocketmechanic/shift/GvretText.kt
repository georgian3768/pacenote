package com.pocketmechanic.shift

/**
 * One frame from the text GVRET stream ESP32RET writes to its telnet port (23).
 *
 * The firmware's own `sprintf` (commbuffer.cpp) emits
 *
 *     <micros> - <id hex> S|X <bus> <length> <b0 hex> ... <bN hex>
 *
 * with lowercase, unpadded hex. SavvyCAN speaks the same text dialect, which is
 * why this capture is a file SavvyCAN can open unchanged.
 */
data class GvretFrame(
    val micros: Long,
    val id: Int,
    val extended: Boolean,
    val bus: Int,
    val length: Int,
    val data: List<Int>,
) {
    /** Canonical firmware spelling: what goes in the capture file. */
    fun format(): String {
        // `length` must be bound before buildString: inside the builder it
        // resolves to StringBuilder.length, which silently writes the byte
        // offset instead of the frame length.
        val frameLength = length
        return buildString {
            append(micros)
            append(" - ")
            append(id.toString(16))
            append(if (extended) " X " else " S ")
            append(bus)
            append(' ')
            append(frameLength)
            data.forEach { byte ->
                append(' ')
                append(byte.toString(16))
            }
        }
    }
}

/**
 * Parser for that stream.
 *
 * Written to survive the one thing the wire actually does: the keepalive reply
 * (`F1 09 DE AD`) is four binary bytes with no newline, so it prefixes the next
 * text frame (`"\u00F1\u0009\u00DE\u00AD123456 - 120 S 0 8 ..."`). The line is
 * therefore searched for a frame instead of matched from the first character,
 * and a line that only holds junk is rejected rather than half-parsed.
 *
 * Kept out of the app module so the format has tests: this parser either reads
 * the reverse capture correctly or the capture is a misleading file.
 */
object GvretText {

    /**
     * A frame anywhere in the line. Decimal micros (32-bit counter), 1-8 hex
     * digits for the id, S/X, one-digit bus, one-digit length and then that many
     * one-or-two-digit hex bytes.
     */
    private val FRAME = Regex(
        """(\d{1,10})\s*-\s*([0-9A-Fa-f]{1,8})\s+([SX])\s+(\d)\s+(\d)((?:\s+[0-9A-Fa-f]{1,2})+)?\s*$"""
    )

    fun parse(line: String): GvretFrame? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val match = FRAME.find(text) ?: return null

        val micros = match.groupValues[1].toLongOrNull() ?: return null
        val id = match.groupValues[2].toLongOrNull(16) ?: return null
        if (id !in 0..0x1FFFFFFFL) return null
        val extended = match.groupValues[3] == "X"
        val bus = match.groupValues[4].toInt()
        val length = match.groupValues[5].toInt()
        if (length > 8) return null

        val rawData = match.groupValues[6]
        val data = if (rawData.isBlank()) {
            emptyList()
        } else {
            rawData.trim().split(Regex("\\s+")).map { token -> token.toIntOrNull(16) ?: return null }
        }
        if (data.size != length) return null
        if (data.any { it !in 0..255 }) return null

        return GvretFrame(
            micros = micros,
            id = id.toInt(),
            extended = extended,
            bus = bus,
            length = length,
            data = data,
        )
    }
}

/**
 * Append-only capture file: header comment lines, `# phase N` / `# phase R`
 * markers, and the raw frames in arrival order.
 *
 * The markers are comments, and they sit *between* the rows they separate, so
 * analysis is a single sequential pass - no timestamps have to be trusted and
 * no clock has to agree between the ESP32 and the phone. Thread-safe because the
 * socket reader appends frames while the UI thread writes the reverse marker.
 */
class GvretCaptureLog(
    header: List<String> = emptyList(),
    private val maxFrames: Int = MAX_FRAMES,
) {
    data class Stats(val frames: Int, val dropped: Int, val truncated: Boolean)

    companion object {
        /**
         * Hard ceiling on rows held in memory. A reverse capture is seconds, not
         * minutes; 500k frames is ~20 minutes of a busy 500 kbps bus and roughly
         * 30 MB of text, which is already more than this screen should ever hold.
         */
        const val MAX_FRAMES = 500_000
    }

    private val lock = Any()
    private val headerLines = header.toList()
    private val body = StringBuilder()
    private var frames = 0
    private var dropped = 0
    private var truncated = false

    /** A `#` line at the current position, e.g. `phase R - ...`. */
    fun comment(text: String) {
        synchronized(lock) {
            body.append("# ").append(text).append('\n')
        }
    }

    /** Stores one canonical row; false once [maxFrames] is reached. */
    fun append(frame: GvretFrame): Boolean {
        synchronized(lock) {
            if (frames >= maxFrames) {
                if (!truncated) {
                    truncated = true
                    body.append("# capture limit reached at ").append(maxFrames)
                        .append(" frames - later frames were not stored\n")
                }
                return false
            }
            body.append(frame.format()).append('\n')
            frames++
            return true
        }
    }

    /** A non-blank line that was not a frame (binary keepalive reply, noise). */
    fun drop() {
        synchronized(lock) {
            dropped++
        }
    }

    fun stats(): Stats = synchronized(lock) { Stats(frames, dropped, truncated) }

    /** Header first, then the body in arrival order. */
    fun text(): String = synchronized(lock) {
        buildString {
            headerLines.forEach { append("# ").append(it).append('\n') }
            append(body)
        }
    }
}
