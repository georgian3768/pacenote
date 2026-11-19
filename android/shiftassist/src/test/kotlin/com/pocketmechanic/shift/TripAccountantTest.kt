package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Trip arithmetic. The rules under test are the ones a wrong answer would hide
 * on the road: distance, gap handling, and the eco score staying in range.
 */
class TripAccountantTest {

    private fun frame(tMs: Long, speed: Double?, rpm: Int? = 2000, distRaw: Long? = null) =
        TripFrame(tMs, speed, rpm, canActive = true, engineRunning = true, distRaw = distRaw)

    @Test
    fun `constant 36 kph for 10 s is 0_1 km`() {
        val a = TripAccountant()
        for (i in 0..10) a.update(frame(i * 1000L, 36.0))
        val s = a.finish(10_000)
        assertEquals(0.1, s.distanceKm, 0.002)
    }

    @Test
    fun `interleaved frames without a speed do not lose distance`() {
        // The bench runs synthetic frames while the ESP32 keeps streaming
        // rpm-null/speed-null frames; distance must not restart at every gap.
        val a = TripAccountant()
        for (i in 0..10) {
            a.update(frame(i * 1000L, 36.0))
            a.update(frame(i * 1000L + 100, null))
            a.update(frame(i * 1000L + 200, null))
        }
        val s = a.finish(10_000)
        assertEquals(0.1, s.distanceKm, 0.002)
    }

    @Test
    fun `a reconnect gap does not invent distance`() {
        val a = TripAccountant()
        a.update(frame(0, 36.0))
        a.update(frame(30_000, 36.0)) // 30 s hole: stream was down
        val s = a.finish(30_000)
        assertEquals(0.0, s.distanceKm, 1e-9)
    }

    @Test
    fun `idle time needs zero speed and the engine running`() {
        val a = TripAccountant()
        a.update(frame(0, 0.0))
        a.update(frame(1000, 0.0))
        a.update(frame(2000, 0.0))
        a.update(TripFrame(3000, 0.0, 700, canActive = true, engineRunning = false))
        val s = a.finish(3000)
        // dt only exists from the second frame, and the engine-off frame is
        // excluded: 1000 + 1000.
        assertEquals(2_000, s.idleMs)
        assertEquals(0.0, s.distanceKm, 1e-9)
    }

    @Test
    fun `over-rev time accumulates only above 95 percent of the redline`() {
        val a = TripAccountant(redlineRpm = 6100)
        a.update(frame(0, 50.0, rpm = 2000))
        a.update(frame(1000, 50.0, rpm = 5800)) // 5800 >= 5795
        a.update(frame(2000, 50.0, rpm = 6000))
        a.update(frame(3000, 50.0, rpm = 4000))
        val s = a.finish(3000)
        assertEquals(2_000, s.overRevMs)
        assertEquals(6000, s.maxRpm)
    }

    @Test
    fun `hard accelerations are counted once per cooldown`() {
        val a = TripAccountant()
        // 0 -> 36 kph in 1 s is 10 m/s^2: one event, even though it lasts frames.
        a.update(frame(0, 0.0))
        a.update(frame(1000, 36.0))
        a.update(frame(2000, 60.0))
        a.update(frame(5000, 80.0))
        val s = a.finish(5000)
        assertEquals(1, s.hardAccelCount)
    }

    @Test
    fun `braking frames never count as hard acceleration`() {
        // A panic stop's speed signal lag used to read as acceleration; the
        // brake bit is the one unambiguous "this is not a pull" signal.
        val a = TripAccountant()
        a.update(TripFrame(0, 0.0, 2000, canActive = true, brake = true))
        a.update(TripFrame(1000, 36.0, 2000, canActive = true, brake = true))
        a.update(TripFrame(2000, 72.0, 2000, canActive = true, brake = true))
        assertEquals(0, a.finish(2000).hardAccelCount)
    }

    @Test
    fun `single frame speed noise is not a hard acceleration`() {
        // 13 Hz stream, +/-1 km/h quantisation: the old frame-to-frame rule
        // counted 657 of these on one drive.
        val a = TripAccountant()
        val speeds = listOf(50.0, 50.0, 51.0, 50.0, 51.0, 50.0, 51.0, 50.0, 51.0)
        speeds.forEachIndexed { i, v -> a.update(frame(i * 100L, v)) }
        assertEquals(0, a.finish(800).hardAccelCount)
    }

    @Test
    fun `dist_raw is unwrapped across its 8192 modulo`() {
        val a = TripAccountant()
        val raw = listOf(8000L, 8100L, 50L, 100L, 150L) // wrap at 8192
        raw.forEachIndexed { i, v -> a.update(frame(i * 1000L, 36.0, distRaw = v)) }
        // 100 + (8192 - 8100 + 50) + 50 + 50 = 342.
        assertEquals(342, a.finish(4_000).distRawDelta)
    }

    @Test
    fun `dist_raw outliers are ignored instead of poisoning the delta`() {
        val a = TripAccountant()
        listOf(0L, 100L, 16_384L, 200L).forEachIndexed { i, v ->
            a.update(frame(i * 1000L, 36.0, distRaw = v))
        }
        assertEquals(200, a.finish(3_000).distRawDelta)
    }

    @Test
    fun `dist_raw is cross checked, never trusted`() {
        val a = TripAccountant()
        for (i in 0..10) a.update(frame(i * 1000L, 36.0, distRaw = i * 100L))
        val s = a.finish(10_000)
        assertEquals(1_000, s.distRawDelta)
        // 1000 raw units over 0.1 km.
        assertEquals(10_000.0, s.distRawRatio!!, 1.0)
    }

    @Test
    fun `eco score stays in range and punishes over-rev`() {
        val clean = TripAccountant.ecoScore(600_000, 5.0, 480_000, 60_000, 0, 0, 60.0)
        val ragged = TripAccountant.ecoScore(600_000, 5.0, 480_000, 60_000, 240_000, 10, 130.0)
        assertTrue(clean in 0..100 && ragged in 0..100)
        assertTrue(ragged < clean, "clean=$clean ragged=$ragged")
        assertEquals(0, TripAccountant.ecoScore(1, 0.0, 1, 1, 1000, 5, 200.0))
    }

    @Test
    fun `distance reminder fires once per interval`() {
        val r = DistanceReminder(intervalKm = 500.0, odometerKm = 0.0)
        r.addTrip(300.0)
        assertNull(r.crossed())
        r.addTrip(250.0)
        assertEquals(500.0, r.crossed()!!, 1e-9)
        assertNull(r.crossed(), "must not fire twice for the same mark")
        r.addTrip(10.0)
        assertNull(r.crossed())
        // 560 km driven, next mark is 1000.
        assertEquals(440.0, r.remainingKm(), 1e-9)
    }

    @Test
    fun `live eco score is null until the first frame arrives`() {
        val a = TripAccountant()
        assertNull(a.liveEcoScore(0))
        assertNull(a.liveEcoScore(60_000), "no startedAt, so there is nothing to score")
        a.update(frame(1_000, 30.0))
        assertNotNull(a.liveEcoScore(1_000))
    }

    @Test
    fun `live eco score is in range once frames are fed`() {
        val a = TripAccountant()
        a.update(frame(0, 0.0, rpm = 800))
        assertTrue(a.liveEcoScore(0)!! in 0..100)
        a.update(frame(1_000, 40.0, rpm = 3000))
        assertTrue(a.liveEcoScore(1_000)!! in 0..100)
        // Even a very long idling stretch must stay inside the scale.
        assertTrue(a.liveEcoScore(600_000)!! in 0..100)
    }

    @Test
    fun `live eco score equals the saved score at the same timestamp`() {
        // The live badge and the stored row are the same formula; if they ever
        // disagree, the driver is told one number and the database keeps another.
        val frames = listOf(
            frame(0, 0.0, rpm = 800),
            frame(1_000, 0.0, rpm = 800), // idle
            frame(2_000, 10.0, rpm = 2_000),
            frame(3_000, 30.0, rpm = 3_000), // hard pull
            frame(4_000, 50.0, rpm = 5_000),
            frame(5_000, 55.0, rpm = 6_200), // over 95 % of the default redline
            frame(6_000, 60.0, rpm = 3_000),
        )
        val saved = TripAccountant()
        val live = TripAccountant()
        frames.forEach { f ->
            saved.update(f)
            live.update(f)
        }
        val at = 7_000L
        val liveScore = live.liveEcoScore(at)
        assertNotNull(liveScore)
        assertEquals(saved.finish(at).ecoScore, liveScore!!)
    }

    @Test
    fun `live eco score punishes over-rev while the trip is still running`() {
        // Over-rev is anything at or above 95 % of redline, not the redline
        // itself, so the clean run stays under that threshold.
        val clean = TripAccountant(redlineRpm = 6000)
        val revved = TripAccountant(redlineRpm = 6000)
        val times = listOf(0L, 1_000L, 2_000L, 3_000L)
        times.forEach { t ->
            clean.update(frame(t, 50.0, rpm = 5_600))
            revved.update(frame(t, 50.0, rpm = 6_500))
        }
        val cleanScore = clean.liveEcoScore(3_000)!!
        val revvedScore = revved.liveEcoScore(3_000)!!
        assertTrue(cleanScore > revvedScore, "clean=$cleanScore revved=$revvedScore")
    }

    @Test
    fun `live eco score is a pure getter`() {
        val a = TripAccountant()
        for (i in 0..5) a.update(frame(i * 1_000L, 40.0, rpm = 2_500))
        val first = a.liveEcoScore(6_000)
        val second = a.liveEcoScore(6_000)
        assertEquals(first, second, "reading the score must not accumulate state")
    }

    @Test
    fun `summary of an empty trip is not NaN`() {
        val a = TripAccountant()
        val s = a.finish(0)
        assertNotNull(s)
        assertEquals(0.0, s.distanceKm, 1e-9)
        assertEquals(0, s.avgRpm)
        assertTrue(s.ecoScore in 0..100)
    }
}
