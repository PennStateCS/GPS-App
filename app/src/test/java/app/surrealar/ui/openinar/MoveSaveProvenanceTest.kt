package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.ar.model.ModelMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveSaveProvenanceTest {

    @Test fun `accuracy buckets`() {
        assertEquals("<=0.5m", MoveSaveProvenance.hAccBucket(0.4))
        assertEquals("<=2m", MoveSaveProvenance.hAccBucket(2.0))
        assertEquals(">5m", MoveSaveProvenance.hAccBucket(9.0))
        assertEquals("unknown", MoveSaveProvenance.hAccBucket(null))
        assertEquals("unknown", MoveSaveProvenance.hAccBucket(Double.NaN))
        assertEquals("<=10deg", MoveSaveProvenance.yawBucket(7.0))
        assertEquals(">15deg", MoveSaveProvenance.yawBucket(20.0))
    }

    @Test fun `log line has the provenance and move size but no coordinates`() {
        val line = MoveSaveProvenance(qualityGood = true, hAccM = 1.234567, yawAccDeg = 3.21, placedByTimeout = false)
            .logLine("abc", ModelMove(eastM = 3f, northM = 4f, upM = -0.25f, yawDeg = 12.34f), storedHorizM = 5.0)
        assertTrue(line, "arQuality=GOOD" in line)
        assertTrue(line, "hAcc=<=2m" in line)
        assertTrue(line, "yawAcc=<=5deg" in line)
        assertTrue(line, "placedByTimeout=false" in line)
        assertTrue(line, "moveHorizM=5.00" in line)
        assertTrue(line, "moveUpM=-0.25" in line)
        assertTrue(line, "turnDeg=12.3" in line)
        assertFalse("exact accuracy is bucketed", "1.23" in line)
        listOf("lat", "lon", "alt").forEach { assertFalse(line, it in line.lowercase()) }
    }
}
