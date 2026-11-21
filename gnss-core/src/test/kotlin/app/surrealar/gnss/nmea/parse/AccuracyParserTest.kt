package app.surrealar.gnss.nmea.parse

import app.surrealar.gnss.nmea.sentence.ACCURACY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyParserTest {

    private val registry = DefaultNmeaRegistry.create(verifyChecksum = true)

    @Test fun `parses the on-device MediaTek sentence (checksum verified)`() {
        val s = registry.parse("\$GNACCURACY,10.5,0.0,0.2,12.4*15")
        assertTrue(s is ACCURACY)
        s as ACCURACY
        assertEquals("GN", s.talker)
        assertEquals(10.5, s.hAccM!!, 0.0)
        assertEquals(0.2, s.speedAccMps!!, 0.0)
        assertEquals(12.4, s.vAccM!!, 0.0)
    }

    @Test fun `zero or blank accuracies are unknown, not 0`() {
        val p = AccuracyParser()
        assertNull(p.parse("GN", listOf("GNACCURACY", "0.0", "0.0", "0.2", "")))
        val onlyV = p.parse("GN", listOf("GNACCURACY", "", "0.0", "0.2", "15.5"))!!
        assertNull(onlyV.hAccM)
        assertEquals(15.5, onlyV.vAccM!!, 0.0)
    }
}
