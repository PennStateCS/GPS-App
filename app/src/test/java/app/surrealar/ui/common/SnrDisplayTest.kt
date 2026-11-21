package app.surrealar.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnrDisplayTest {
    @Test fun `rounds instead of truncating`() {
        assertEquals(26, SnrDisplay.db(25.9))
        assertEquals(28, SnrDisplay.db(28.3))   // SM-X940 decimal SNR
    }

    @Test fun `Emlid 51-52 dB-Hz is not clipped at 50`() {
        assertEquals(52, SnrDisplay.db(52.0))
        assertEquals(SnrDisplay.MAX_DB, SnrDisplay.db(70.0))
    }

    @Test fun `unknown or non-positive SNR is null, not 0`() {
        assertNull(SnrDisplay.db(null))
        assertNull(SnrDisplay.db(0.0))
        assertNull(SnrDisplay.db(Double.NaN))
    }
}
