package io.github.pennstatecs.geokit.gnss.bus.adapters

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaFields
import io.github.pennstatecs.geokit.gnss.nmea.parse.SentenceParser
import io.github.pennstatecs.geokit.gnss.nmea.sentence.FlaggedNmeaSentence
import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vendor extension mechanism: extra parsers, per-tag counts, latest sentence and sticky flags. */
class NmeaFuserExtraSentenceTest {

    /** A made-up proprietary sentence `$PXXYZ,<lat>,<N|S>,<payload>` (talker "PX", tag "XYZ"). */
    private data class Xyz(override val talker: String, val lat: Double?, val payload: String?) : FlaggedNmeaSentence {
        override val tag: String = "XYZ"
        override val diagnosticFlags: Set<String> get() = if (payload != null) setOf("payload") else emptySet()
    }

    private class XyzParser : SentenceParser<Xyz> {
        override val tag: String = "XYZ"
        override fun parse(talker: String, fields: List<String>): Xyz =
            Xyz(talker, NmeaFields.latitude(fields.getOrNull(1), fields.getOrNull(2)), fields.getOrNull(3)?.ifBlank { null })
    }

    private val gga = "\$GPGGA,123519.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,2.0,0001"

    private fun fuser(extra: Map<String, SentenceParser<out NmeaSentence>>, fixes: MutableList<Fix>) =
        NmeaFuser(Provider.EXTERNAL_TCP, DefaultNmeaRegistry.create(verifyChecksum = false, extraParsers = extra),
            onFix = { fixes += it }, onGsv = { }, clock = { 0L })

    @Test
    fun `standard registry has no vendor parsers`() {
        assertEquals(setOf("GGA", "RMC", "GSA", "GSV", "ZDA", "GST", "ACCURACY"), DefaultNmeaRegistry.standardParsers().keys)
        assertNull(DefaultNmeaRegistry.create(verifyChecksum = false).parse("\$PXXYZ,4807.038,N,a"))
    }

    @Test
    fun `extra sentences are counted with latest and sticky flags, and never change fixes`() {
        val withExtra = mutableListOf<Fix>()
        val f = fuser(mapOf("XYZ" to XyzParser()), withExtra)
        f.accept(gga)
        f.accept("\$PXXYZ,4807.038,N,first")
        f.accept("\$PXXYZ,4807.038,S,")
        val stats = f.extraSentenceStats
        assertEquals(2, stats.count("XYZ"))
        assertTrue(stats.seen("XYZ"))
        assertFalse(stats.seen("ABC"))
        assertEquals(0, stats.count("ABC"))
        val latest = stats.latest<Xyz>("XYZ")!!
        assertEquals(-48.1173, latest.lat!!, 1e-4)
        assertNull("latest has no payload", latest.payload)
        assertTrue("flag from the first sentence is remembered", stats.flagSeen("XYZ", "payload"))

        val plain = mutableListOf<Fix>()
        val p = fuser(emptyMap(), plain)
        listOf(gga, "\$PXXYZ,4807.038,N,first", "\$PXXYZ,4807.038,S,").forEach(p::accept)
        assertEquals("extra parsers never change fixes", plain, withExtra)
        assertEquals(NmeaFuser.ExtraSentenceStats.EMPTY, p.extraSentenceStats)
    }

    @Test
    fun `snapshot is a copy and reset clears it`() {
        val f = fuser(mapOf("XYZ" to XyzParser()), mutableListOf())
        f.accept("\$PXXYZ,4807.038,N,x")
        val before = f.extraSentenceStats
        f.accept("\$PXXYZ,4807.038,N,y")
        assertEquals("earlier snapshot unchanged", 1, before.count("XYZ"))
        f.reset()
        assertEquals(NmeaFuser.ExtraSentenceStats.EMPTY, f.extraSentenceStats)
    }

    @Test
    fun `an extra parser can replace a standard tag`() {
        val custom = object : SentenceParser<NmeaSentence> {
            override val tag = "ZDA"
            override fun parse(talker: String, fields: List<String>): NmeaSentence =
                Xyz(talker, null, "zda")
        }
        val r = DefaultNmeaRegistry.create(verifyChecksum = false, extraParsers = mapOf("ZDA" to custom))
        assertTrue(r.parse("\$GPZDA,123519.00,30,06,2026,00,00") is Xyz)
    }
}
