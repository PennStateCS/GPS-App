package io.github.pennstatecs.geokit.receiver.emlid.nmea

import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Emlid Reach captures (geokit-gnss's shared `nmea/` fixtures) replayed with [EmlidNmea.parsers]
 * registered: EBP/ETC are counted, the IMU flag and latest ETC come through [EmlidNmeaDiagnostics],
 * and the fixes are exactly those of a plain registry without the Emlid parsers.
 */
class EmlidNmeaCaptureReplayTest {

    private fun replay(resource: String, emlid: Boolean = true): Pair<List<Fix>, NmeaFuser> {
        val stream = javaClass.getResourceAsStream(resource) ?: error("Missing test fixture: $resource")
        val fixes = mutableListOf<Fix>()
        val fuser = NmeaFuser(
            provider = Provider.EXTERNAL_TCP,
            registry = DefaultNmeaRegistry.create(
                verifyChecksum = true,
                extraParsers = if (emlid) EmlidNmea.parsers else emptyMap(),
            ),
            onFix = { fixes.add(it) },
            onGsv = { },
            clock = { 0L },   // fixed clock: the two runs must be comparable fix for fix
        )
        stream.bufferedReader().useLines { seq -> seq.forEach { fuser.accept(it) } }
        fuser.flush()
        return fixes to fuser
    }

    @Test
    fun `RS4 capture sees EBP and timestamp-only ETC`() {
        val (_, fuser) = replay("/nmea/reach_nmea_capture.txt")
        val d = EmlidNmeaDiagnostics.from(fuser.extraSentenceStats)
        assertTrue("EBP seen", d.ebpSeen)
        assertTrue("ETC seen", d.etcSeen)
        assertFalse("no IMU/orientation in the plain capture", d.imuOrientationSeen)
    }

    @Test
    fun `RS4 IMU capture flags orientation and keeps the latest ETC`() {
        val (_, fuser) = replay("/nmea/reach_nmea_withimu.txt")
        val d = EmlidNmeaDiagnostics.from(fuser.extraSentenceStats)
        assertTrue("IMU/orientation should be detected", d.imuOrientationSeen)
        val etc = d.latestEtc
        assertNotNull(etc)
        assertTrue("latest ETC has raw data fields", etc!!.hasOrientationData)
    }

    @Test
    fun `RS2 capture has EBP but no ETC`() {
        val (_, fuser) = replay("/nmea/reach_nmea_rs2_capture.txt")
        val d = EmlidNmeaDiagnostics.from(fuser.extraSentenceStats)
        assertFalse("RS2 has no ETC", d.etcSeen)
        assertFalse("RS2 has no IMU orientation", d.imuOrientationSeen)
        assertTrue("EBP seen", d.ebpSeen)
    }

    @Test
    fun `Emlid parsers never change the fixes`() {
        for (f in listOf("reach_nmea_capture", "reach_nmea_withimu", "reach_nmea_rs2_capture")) {
            val (with, withFuser) = replay("/nmea/$f.txt", emlid = true)
            val (without, withoutFuser) = replay("/nmea/$f.txt", emlid = false)
            assertEquals(f, without, with)
            assertEquals(f, withoutFuser.timingStats, withFuser.timingStats)
            assertEquals(f, NmeaFuser.ExtraSentenceStats.EMPTY, withoutFuser.extraSentenceStats)
        }
    }
}
