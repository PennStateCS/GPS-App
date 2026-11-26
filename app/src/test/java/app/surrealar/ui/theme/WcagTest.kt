package app.surrealar.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class WcagTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun `black on white is 21 to 1`() = assertEquals(21.0, Wcag.contrast(black, white), 1e-9)

    @Test fun `contrast is symmetric and 1 for identical colours`() {
        assertEquals(Wcag.contrast(0xFF155DA8.toInt(), white), Wcag.contrast(white, 0xFF155DA8.toInt()), 1e-12)
        assertEquals(1.0, Wcag.contrast(0xFF0F3554.toInt(), 0xFF0F3554.toInt()), 1e-12)
    }

    @Test fun `reference grey 767676 on white just passes AA`() =
        assertEquals(4.54, Wcag.contrast(0xFF767676.toInt(), white), 0.01)

    @Test fun `known failures from the audit`() {
        assertEquals(3.79, Wcag.contrast(0xFFE65100.toInt(), white), 0.01)          // old light warning text
        assertEquals(3.59, Wcag.contrast(0xFFD32F2F.toInt(), 0xFF101820.toInt()), 0.01) // old dark error
        assertEquals(2.47, Wcag.contrast(0xFF2E7D32.toInt(), 0xFF0F3554.toInt()), 0.01) // old toolbar RTK Fix
    }

    @Test fun `luminance endpoints`() {
        assertEquals(0.0, Wcag.relativeLuminance(black), 1e-12)
        assertEquals(1.0, Wcag.relativeLuminance(white), 1e-12)
    }

    @Test fun `half black over white composites to mid grey`() =  // 0x80 = 128/255 alpha -> 255 * 127/255 = 127
        assertEquals(0xFF7F7F7F.toInt(), Wcag.composite(0x80000000.toInt(), white))

    @Test fun `opaque foreground is unchanged by compositing`() =
        assertEquals(0xFF155DA8.toInt(), Wcag.composite(0xFF155DA8.toInt(), black))

    @Test fun `translucent text is measured after compositing`() {
        val text70 = Wcag.withAlpha(0xFF1A1C1E.toInt(), 0.7f)
        assertEquals(Wcag.contrast(Wcag.composite(text70, white), white), Wcag.contrast(text70, white), 1e-12)
    }
}
