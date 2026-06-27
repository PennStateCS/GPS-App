package app.surrealar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorUtilsTest {

    private val DELTA = 0.001f

    @Test
    fun `argbIntToRgba extracts channels in RGBA order`() {
        // ARGB: A=0xFF, R=0x40, G=0x80, B=0xC0
        val argb = (0xFF shl 24) or (0x40 shl 16) or (0x80 shl 8) or 0xC0
        val rgba = argbIntToRgba(argb)
        assertEquals(4, rgba.size)
        assertEquals(0x40 / 255f, rgba[0], DELTA) // R
        assertEquals(0x80 / 255f, rgba[1], DELTA) // G
        assertEquals(0xC0 / 255f, rgba[2], DELTA) // B
        assertEquals(1.0f,        rgba[3], DELTA) // A = 255/255
    }

    @Test
    fun `argbIntToRgba enforces minimum alpha of 0_2 when stored alpha is zero`() {
        // Fully transparent color: A=0x00, R=0xFF, G=0x00, B=0x00
        val argb = (0x00 shl 24) or (0xFF shl 16) or (0x00 shl 8) or 0x00
        val rgba = argbIntToRgba(argb)
        assertEquals(0.2f, rgba[3], DELTA)
    }

    @Test
    fun `argbIntToRgba preserves alpha above minimum`() {
        // A=0x80 (≈ 0.502), which is above the 0.2 minimum
        val argb = (0x80 shl 24) or (0x10 shl 16) or (0x20 shl 8) or 0x30
        val rgba = argbIntToRgba(argb)
        assertEquals(0x80 / 255f, rgba[3], DELTA)
    }

    @Test
    fun `argbIntToRgba channels are normalised to 0_0 to 1_0 range`() {
        val argb = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
        val rgba = argbIntToRgba(argb)
        rgba.forEach { channel ->
            assert(channel in 0f..1f) { "Channel $channel out of range" }
        }
    }
}
