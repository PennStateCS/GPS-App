package io.github.pennstatecs.geokit.receiver.emlid.tilt

import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.nmea.sentence.GGA
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel
import io.github.pennstatecs.geokit.receiver.emlid.nmea.ETC
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EmlidNmea
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmlidTiltTrackerTest {

    private val registry = DefaultNmeaRegistry.create(verifyChecksum = true, extraParsers = EmlidNmea.parsers)

    private fun etc(time: String, rest: String): ETC = ETC("GN", time, rest.split(","))
    private fun off(time: String) = etc(time, ",,,,,,,")
    private fun setup(time: String) = etc(time, "10,00,,,,,,")
    private fun align(time: String, note: String = "00") = etc(time, "20,$note,,,,,,")
    private fun comp(time: String, tilt: String = "2.344", note: String = "00") =
        etc(time, "30,$note,172.543,5.621,$tilt,1.013,0.993,1.021")
    private fun fatal(time: String) = etc(time, "0,30,,,,,,")

    private fun tod(hhmmss: String): Double = io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcFields.parseTimeOfDay(hhmmss)!!

    @Test
    fun `no ETC ever - NO_ETC_SEEN and UNKNOWN`() {
        val t = EmlidTiltTracker()
        val a = t.associate(tod("120000.00"), 1_000)
        assertEquals(TiltMatch.NO_ETC_SEEN, a.match)
        assertEquals(EmlidTiltState.UNKNOWN, a.state)
        assertNull(a.reading)
        assertFalse(t.isEtcStreamLive(1_000))
    }

    @Test
    fun `state transitions follow matched epochs`() {
        val t = EmlidTiltTracker()
        val seq = listOf(
            "120000.00" to off("120000.00"),
            "120000.20" to setup("120000.20"),
            "120000.40" to align("120000.40"),
            "120000.60" to comp("120000.60"),
            "120000.80" to comp("120000.80", note = "10"),
            "120001.00" to align("120001.00", note = "20"),  // bad GNSS → back to alignment
            "120001.20" to comp("120001.20"),
            "120001.40" to fatal("120001.40"),
        )
        val expected = listOf(
            EmlidTiltState.OFF, EmlidTiltState.INITIALIZING, EmlidTiltState.ALIGNING, EmlidTiltState.ACTIVE,
            EmlidTiltState.ACTIVE, EmlidTiltState.ALIGNING, EmlidTiltState.ACTIVE, EmlidTiltState.UNAVAILABLE,
        )
        seq.forEachIndexed { i, (time, sentence) ->
            val at = 10_000L + i * 200
            t.onEtc(sentence, at + 5)
            val a = t.associate(tod(time), at)
            assertEquals("epoch $time", TiltMatch.MATCHED, a.match)
            assertEquals("epoch $time", expected[i], a.state)
        }
        val warn = t.associate(tod("120000.80"), 10_800)
        assertTrue(warn.reading!!.hasWarning)
        assertEquals(EtcNotification.FAST_MOTION, warn.reading?.notification)
        assertFalse(t.associate(tod("120000.60"), 10_600).reading!!.hasWarning)
        assertEquals(2.344, t.associate(tod("120000.60"), 10_600).reading!!.tiltDeg!!, 0.0)
    }

    @Test
    fun `latest ETC is never paired with a newer fix`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 1_000)
        val a = t.associate(tod("120000.20"), 1_200)    // the next epoch: its ETC has not arrived yet
        assertEquals(TiltMatch.NOT_MATCHED, a.match)
        assertEquals(EmlidTiltState.UNKNOWN, a.state)
        t.onEtc(comp("120000.20"), 1_210)               // now it has
        assertEquals(EmlidTiltState.ACTIVE, t.associate(tod("120000.20"), 1_200).state)
    }

    @Test
    fun `time tolerance is 50 ms`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.20"), 1_000)
        assertEquals(TiltMatch.MATCHED, t.associate(tod("120000.24"), 1_000).match)
        assertEquals(TiltMatch.NOT_MATCHED, t.associate(tod("120000.30"), 1_000).match)
        assertEquals(TiltMatch.NOT_MATCHED, t.associate(tod("120000.40"), 1_000).match)
    }

    @Test
    fun `same time-of-day received long before the fix is not used`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 1_000)
        t.onEtc(off("120001.00"), 4_000)                 // keeps the stream live
        val a = t.associate(tod("120000.00"), 4_100)    // e.g. 24 h later, or a replayed epoch
        assertEquals(TiltMatch.NOT_MATCHED, a.match)
        assertEquals(EmlidTiltState.UNKNOWN, a.state)
    }

    @Test
    fun `receipt skew limit is 1 s either side`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 5_000)
        assertEquals(TiltMatch.MATCHED, t.associate(tod("120000.00"), 4_000).match)
        assertEquals(TiltMatch.MATCHED, t.associate(tod("120000.00"), 6_000).match)
        assertEquals(TiltMatch.NOT_MATCHED, t.associate(tod("120000.00"), 3_999).match)
        assertEquals(TiltMatch.ETC_STREAM_STALE, t.associate(tod("120000.00"), 7_001).match)
    }

    @Test
    fun `stale ETC stream`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 1_000)
        assertTrue(t.isEtcStreamLive(3_000))
        assertFalse(t.isEtcStreamLive(3_001))
        val a = t.associate(tod("120005.00"), 6_000)
        assertEquals(TiltMatch.ETC_STREAM_STALE, a.match)
        assertEquals(EmlidTiltState.UNKNOWN, a.state)
        assertNotNull(t.latestReading())
    }

    @Test
    fun `no fix time`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 1_000)
        assertEquals(TiltMatch.NO_FIX_TIME, t.associate(null as Double?, 1_000).match)
        assertEquals(TiltMatch.NO_FIX_TIME, t.associate(Double.NaN, 1_000).match)
        val gga = GGA("GN", null, 41.0, -76.0, 4, 20, 1.0, 300.0, -33.0, 1.0, "1")
        assertEquals(TiltMatch.NO_FIX_TIME, t.associate(gga, 1_000).match)
    }

    @Test
    fun `ETC without a time never matches`() {
        val t = EmlidTiltTracker()
        t.onEtc(ETC("GN", null, List(8) { "" }), 1_000)  // doc example: tilt off, no GNSS solution
        assertEquals(TiltMatch.NOT_MATCHED, t.associate(tod("120000.00"), 1_000).match)
    }

    @Test
    fun `midnight wrap`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("235959.99"), 1_000)
        assertEquals(TiltMatch.MATCHED, t.associate(tod("000000.01"), 1_010).match)
    }

    @Test
    fun `conflicting ETCs for one epoch are ambiguous`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 1_000)
        t.onEtc(align("120000.00"), 1_100)
        val a = t.associate(tod("120000.00"), 1_000)
        assertEquals(TiltMatch.AMBIGUOUS, a.match)
        assertEquals(EmlidTiltState.UNKNOWN, a.state)
    }

    @Test
    fun `identical duplicate ETC still matches`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 1_000)
        t.onEtc(comp("120000.00"), 1_050)
        assertEquals(TiltMatch.MATCHED, t.associate(tod("120000.00"), 1_000).match)
    }

    @Test
    fun `malformed matching ETC is UNKNOWN`() {
        val t = EmlidTiltTracker()
        t.onEtc(etc("120000.00", "30,00,172.5,5.6,,1.0,1.0,1.0"), 1_000)
        val a = t.associate(tod("120000.00"), 1_000)
        assertEquals(TiltMatch.MALFORMED, a.match)
        assertEquals(EmlidTiltState.UNKNOWN, a.state)
        assertNotNull(a.reading!!.malformedReason)
        t.onEtc(etc("120000.20", "40,00,,,,,,"), 1_200)  // undocumented state code
        assertEquals(TiltMatch.MALFORMED, t.associate(tod("120000.20"), 1_200).match)
    }

    @Test
    fun `reset forgets everything`() {
        val t = EmlidTiltTracker()
        t.onEtc(comp("120000.00"), 1_000)
        t.reset()
        assertEquals(TiltMatch.NO_ETC_SEEN, t.associate(tod("120000.00"), 1_000).match)
        assertNull(t.latestReading())
        assertNull(t.lastEtcReceivedAtMs())
    }

    @Test
    fun `capacity evicts oldest epochs`() {
        val t = EmlidTiltTracker(capacity = 2)
        t.onEtc(comp("120000.00"), 1_000)
        t.onEtc(comp("120000.20"), 1_200)
        t.onEtc(comp("120000.40"), 1_400)
        assertEquals(TiltMatch.NOT_MATCHED, t.associate(tod("120000.00"), 1_000).match)
        assertEquals(TiltMatch.MATCHED, t.associate(tod("120000.20"), 1_200).match)
    }

    @Test
    fun `onSentence ignores non-ETC`() {
        val t = EmlidTiltTracker()
        val gga = registry.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")!!
        assertFalse(t.onSentence(gga, 1_000))
        assertTrue(t.onSentence(comp("123519.00"), 1_000))
    }

    // ── Real captures, replayed line by line; each GGA associated when the next GGA arrives ──

    private data class Replay(val states: List<EmlidTiltAssociation>)

    private fun replay(resource: String, lineMs: Long = 10): Replay {
        val t = EmlidTiltTracker()
        val out = mutableListOf<EmlidTiltAssociation>()
        var pending: Pair<GGA, Long>? = null
        var now = 0L
        (javaClass.getResourceAsStream(resource) ?: error("missing $resource")).bufferedReader().useLines { seq ->
            seq.forEach { line ->
                now += lineMs
                when (val s = registry.parse(line)) {
                    is GGA -> {
                        pending?.let { (g, at) -> out += t.associate(g, at) }
                        pending = s to now
                    }
                    null -> Unit
                    else -> t.onSentence(s, now)
                }
            }
        }
        pending?.let { (g, at) -> out += t.associate(g, at) }
        return Replay(out)
    }

    @Test
    fun `withimu capture - every GGA matches a compensating ETC`() {
        val r = replay("/nmea/reach_nmea_withimu.txt")
        assertEquals(898, r.states.size)
        assertTrue(r.states.all { it.match == TiltMatch.MATCHED && it.state == EmlidTiltState.ACTIVE })
    }

    @Test
    fun `plain RS4 capture - every GGA matches tilt OFF and classifies as ANTENNA`() {
        val r = replay("/nmea/reach_nmea_capture.txt")
        assertEquals(885, r.states.size)
        assertTrue(r.states.all { it.match == TiltMatch.MATCHED && it.state == EmlidTiltState.OFF })
        val ref = EmlidPositionReference.classify(
            EmlidReachModel.RS4, CompensatedNmeaOutput.UNKNOWN, null, r.states.last(),
        )
        assertEquals(PositionReference.ANTENNA, ref.reference)
    }

    @Test
    fun `RS2+ capture - no ETC, no tilt state, ANTENNA as today`() {
        val r = replay("/nmea/reach_nmea_rs2_capture.txt")
        assertEquals(181, r.states.size)
        assertTrue(r.states.all { it.match == TiltMatch.NO_ETC_SEEN && it.state == EmlidTiltState.UNKNOWN })
        val ref = EmlidPositionReference.classify(
            EmlidReachModel.RS2_PLUS, CompensatedNmeaOutput.UNKNOWN, null, r.states.last(),
        )
        assertEquals(PositionReference.ANTENNA, ref.reference)
        assertFalse(ref.antennaMayBeTilted)
    }

    @Test
    fun `withimu capture is UNKNOWN unless compensated output is declared`() {
        val last = replay("/nmea/reach_nmea_withimu.txt").states.last()
        for (model in listOf(EmlidReachModel.RS4, EmlidReachModel.RS4_PRO, null)) {
            assertEquals(
                PositionReference.UNKNOWN,
                EmlidPositionReference.classify(model, CompensatedNmeaOutput.UNKNOWN, "34.10", last).reference,
            )
        }
    }
}
