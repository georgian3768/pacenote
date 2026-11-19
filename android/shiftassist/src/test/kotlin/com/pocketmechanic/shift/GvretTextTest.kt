package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The reverse capture is only as good as this parser: a line silently dropped
 * is a frame that cannot be in the diff, and a line half-read is a frame with
 * the wrong bytes. Both failure modes look like "no reverse signal on the bus".
 */
class GvretTextTest {

    @Test
    @DisplayName("the firmware's canonical text line parses")
    fun parsesCanonical() {
        val frame = GvretText.parse("123456 - 120 S 0 8 21 4e 00 00 00 00 00 00")!!

        assertEquals(123456L, frame.micros)
        assertEquals(0x120, frame.id)
        assertFalse(frame.extended)
        assertEquals(0, frame.bus)
        assertEquals(8, frame.length)
        assertEquals(listOf(0x21, 0x4e, 0, 0, 0, 0, 0, 0), frame.data)
    }

    @Test
    @DisplayName("a binary keepalive reply prefixed to a frame does not hide it")
    fun toleratesBinaryPrefix() {
        // The ESP answers F1 09 with F1 09 DE AD and no newline, so those four
        // bytes are glued to the next text frame.
        val line = "\u00F1\u0009\u00DE\u00AD7654321 - 318 S 0 8 00 04 00 00 00 00 00 00"
        val frame = GvretText.parse(line)

        assertEquals(0x318, frame?.id)
        assertEquals(listOf(0, 4, 0, 0, 0, 0, 0, 0), frame?.data)
    }

    @Test
    @DisplayName("uppercase and two-digit hex are the same frame")
    fun acceptsHexCaseAndPadding() {
        val frame = GvretText.parse("42 - 1AC S 0 3 AB 0C 7F")!!
        assertEquals(0x1AC, frame.id)
        assertEquals(listOf(0xAB, 0x0C, 0x7F), frame.data)
    }

    @Test
    @DisplayName("extended ids and their X marker survive")
    fun parsesExtended() {
        val frame = GvretText.parse("900 - 18DAF110 X 0 2 03 22")!!
        assertTrue(frame.extended)
        assertEquals(0x18DAF110, frame.id)
    }

    @Test
    @DisplayName("a zero-length frame is valid")
    fun parsesEmptyPayload() {
        val frame = GvretText.parse("7 - 3B8 S 0 0")!!
        assertEquals(0, frame.length)
        assertTrue(frame.data.isEmpty())
    }

    @Test
    @DisplayName("a row is re-emitted in the firmware's spelling")
    fun formatsCanonically() {
        val frame = GvretFrame(99, 0x124, false, 0, 2, listOf(0x0A, 0xFF))
        assertEquals("99 - 124 S 0 2 a ff", frame.format())
    }

    @Test
    @DisplayName("doubtful lines are rejected, never half-parsed")
    fun rejectsMalformed() {
        assertNull(GvretText.parse(""))
        assertNull(GvretText.parse("   "))
        assertNull(GvretText.parse("Done with init"))                  // console text
        assertNull(GvretText.parse("123 - 120 S 0 8 01 02 03"))        // length says 8, has 3
        assertNull(GvretText.parse("123 - 120 S 0 8 01 02 03 04 05 06 07 08 09")) // too many
        assertNull(GvretText.parse("123 - 120 Z 0 1 01"))              // no such direction
        assertNull(GvretText.parse("123 - 120 S 0 9 01 02 03 04 05 06 07 08 09")) // classic CAN
        assertNull(GvretText.parse("123 - 120 S 0 0 01"))              // len 0 with a byte
        assertNull(GvretText.parse("123 - 120 S 0 1 gg"))              // not hex
    }
}

/**
 * Marker placement is the contract with the analysis script: everything between
 * `# phase N` and `# phase R` is neutral, everything after `# phase R` is the
 * reverse attempt. No timestamps are trusted for the split.
 */
class GvretCaptureLogTest {

    private fun frame(id: Int) =
        GvretFrame(1, id, false, 0, 1, listOf(0x00))

    @Test
    @DisplayName("header, markers and rows keep their order")
    fun writesInOrder() {
        val log = GvretCaptureLog(listOf("Pacenote reverse capture", "started now"))
        log.comment("phase N - neutral")
        assertTrue(log.append(frame(0x120)))
        assertTrue(log.append(frame(0x124)))
        log.comment("phase R - reverse")
        assertTrue(log.append(frame(0x318)))

        assertEquals(
            listOf(
                "# Pacenote reverse capture",
                "# started now",
                "# phase N - neutral",
                "1 - 120 S 0 1 0",
                "1 - 124 S 0 1 0",
                "# phase R - reverse",
                "1 - 318 S 0 1 0",
            ),
            log.text().trimEnd('\n').lines(),
        )
    }

    @Test
    @DisplayName("counts separate stored frames from dropped lines")
    fun countsFramesAndJunk() {
        val log = GvretCaptureLog()
        log.append(frame(0x120))
        log.drop()
        log.drop()
        log.append(frame(0x120))

        val stats = log.stats()
        assertEquals(2, stats.frames)
        assertEquals(2, stats.dropped)
        assertFalse(stats.truncated)
    }

    @Test
    @DisplayName("the memory cap is recorded in the file, not silent")
    fun capsAndSaysSo() {
        val log = GvretCaptureLog(maxFrames = 2)
        assertTrue(log.append(frame(0x1)))
        assertTrue(log.append(frame(0x2)))
        assertFalse(log.append(frame(0x3)))
        assertFalse(log.append(frame(0x4)))

        val stats = log.stats()
        assertEquals(2, stats.frames)
        assertTrue(stats.truncated)
        assertEquals(1, log.text().lines().count { it.startsWith("# capture limit reached") })
    }
}
