package app.surrealar.gnss.diagnostics.raw

import app.surrealar.gnss.diagnostics.raw.NmeaLineClassifier.ChecksumStatus
import app.surrealar.gnss.diagnostics.raw.NmeaLineClassifier.SentenceType
import app.surrealar.gnss.diagnostics.raw.RawNmeaRecorderTest.Companion.nmea
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sentences from the existing Emlid fixture (reach_nmea_withimu.txt) plus SYNTHETIC edge cases. */
class NmeaLineClassifierTest {

    @Test fun `talker-agnostic types from fixture sentences`() {
        assertEquals(SentenceType.GGA, NmeaLineClassifier.typeOf("\$GNGGA,173025.40,4118.4442077,N,07601.0311556,W,4,25,1.0,396.456,M,-33.145,M,1.4,1116*60"))
        assertEquals(SentenceType.GGA, NmeaLineClassifier.typeOf(nmea("GPGGA,1")))
        assertEquals(SentenceType.RMC, NmeaLineClassifier.typeOf("\$GNRMC,173025.80,A,4118.4442286,N,07601.0311842,W,0.87,72.59,300626,,,D*61"))
        assertEquals(SentenceType.GST, NmeaLineClassifier.typeOf("\$GNGST,173025.80,2.700,,,,0.010,0.010,0.016*6F"))
        assertEquals(SentenceType.ETC, NmeaLineClassifier.typeOf("\$GNETC,173025.40,30,00,268.660,116.146,17.651,6.280,6.280,6.991*66"))
        assertEquals(SentenceType.EBP, NmeaLineClassifier.typeOf("\$GNEBP,4118.3990546,N,07601.0359618,W,374.038,M*17"))
        assertEquals(SentenceType.OTHER, NmeaLineClassifier.typeOf("\$GPGSA,A,3,05,15,18,20,21,29,30,,,,,,1.8,1.0,1.5*3F"))
        assertEquals(SentenceType.OTHER, NmeaLineClassifier.typeOf(nmea("PGGA,1")))      // proprietary, not GGA
        assertEquals(SentenceType.OTHER, NmeaLineClassifier.typeOf("garbage"))
    }

    @Test fun `checksum verification on fixture sentences and SYNTHETIC errors`() {
        assertEquals(ChecksumStatus.VALID, NmeaLineClassifier.checksumOf("\$GNETC,173025.40,30,00,268.660,116.146,17.651,6.280,6.280,6.991*66"))
        assertEquals(ChecksumStatus.VALID, NmeaLineClassifier.checksumOf("\$GNZDA,173025.80,30,06,2026,00,00*71"))
        assertEquals(ChecksumStatus.INVALID, NmeaLineClassifier.checksumOf("\$GNZDA,173025.80,30,06,2026,00,00*72"))
        assertEquals(ChecksumStatus.MALFORMED, NmeaLineClassifier.checksumOf("\$GNZDA,173025.80,30,06,2026,00,00"))
        assertEquals(ChecksumStatus.MALFORMED, NmeaLineClassifier.checksumOf("\$GNZDA,173025.80,30*7"))
        assertEquals(ChecksumStatus.MALFORMED, NmeaLineClassifier.checksumOf("\$GNZDA,173025.80,30*ZZ"))
        assertEquals(ChecksumStatus.MALFORMED, NmeaLineClassifier.checksumOf("\$GNZDA,é*00"))
        assertEquals(ChecksumStatus.MALFORMED, NmeaLineClassifier.checksumOf("GNZDA,1*00"))
        assertEquals(ChecksumStatus.VALID, NmeaLineClassifier.checksumOf(nmea("GNZDA,1").replace(Regex("\\*(..)$")) { "*" + it.groupValues[1].lowercase() }))
    }
}
