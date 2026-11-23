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
import org.junit.Assert.assertThrows
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

    private fun customParser(tag: String) = object : SentenceParser<NmeaSentence> {
        override val tag = tag
        override fun parse(talker: String, fields: List<String>): NmeaSentence = Xyz(talker, null, tag.lowercase())
    }

    @Test
    fun `an extra parser can replace a standard tag when the override is requested`() {
        val r = DefaultNmeaRegistry.create(
            verifyChecksum = false, extraParsers = mapOf("ZDA" to customParser("ZDA")), allowStandardOverride = setOf("ZDA"),
        )
        assertTrue(r.parse("\$GPZDA,123519.00,30,06,2026,00,00") is Xyz)
    }

    @Test
    fun `an extra parser with a standard tag is rejected unless the override is requested`() {
        for (tag in DefaultNmeaRegistry.standardParsers().keys) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                DefaultNmeaRegistry.create(extraParsers = mapOf(tag to customParser(tag)))
            }
            assertTrue(e.message!!, e.message!!.contains(tag) && e.message!!.contains("allowStandardOverride"))
        }
    }

    @Test
    fun `tag collisions are detected ignoring case`() {
        assertThrows(IllegalArgumentException::class.java) {
            DefaultNmeaRegistry.create(extraParsers = mapOf("gga" to customParser("gga")))
        }
    }

    @Test
    fun `an override request must name a standard tag that has an extra parser`() {
        // Not a standard tag (typo or proprietary tag): rejected rather than silently ignored.
        assertThrows(IllegalArgumentException::class.java) {
            DefaultNmeaRegistry.create(extraParsers = mapOf("XYZ" to XyzParser()), allowStandardOverride = setOf("XYZ"))
        }
        // Standard tag, but nothing replaces it.
        assertThrows(IllegalArgumentException::class.java) {
            DefaultNmeaRegistry.create(allowStandardOverride = setOf("GST"))
        }
    }

    @Test
    fun `an override replaces only the named tag and leaves the other standard parsers`() {
        val r = DefaultNmeaRegistry.create(
            verifyChecksum = false, extraParsers = mapOf("GST" to customParser("GST")), allowStandardOverride = setOf("gst"),
        )
        assertTrue(r.parse("\$GPGST,123519.00,1.0,0.5,0.4,30.0,0.02,0.03,0.05") is Xyz)
        assertFalse(r.parse("\$GPZDA,123519.00,30,06,2026,00,00") is Xyz)
    }

    @Test
    fun `a lowercase override key really replaces the standard parser`() {
        val r = DefaultNmeaRegistry.create(
            verifyChecksum = false, extraParsers = mapOf("gst" to customParser("GST")), allowStandardOverride = setOf("GST"),
        )
        assertTrue(r.parse("\$GPGST,123519.00,1.0,0.5,0.4,30.0,0.02,0.03,0.05") is Xyz)
    }

    @Test
    fun `non-colliding proprietary parsers need no override`() {
        val r = DefaultNmeaRegistry.create(verifyChecksum = false, extraParsers = mapOf("XYZ" to XyzParser()))
        assertTrue(r.parse("\$PXXYZ,4118.40,N,abc") is Xyz)
    }

    @Test
    fun `replay applies the same collision rule`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        assertThrows(IllegalArgumentException::class.java) {
            io.github.pennstatecs.geokit.gnss.replay.ReplayAdapter(scope, extraParsers = mapOf("RMC" to customParser("RMC")))
        }
        io.github.pennstatecs.geokit.gnss.replay.ReplayAdapter(
            scope, extraParsers = mapOf("RMC" to customParser("RMC")), allowStandardOverride = setOf("RMC"),
        )
    }
}
