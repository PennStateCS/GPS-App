package app.surrealar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AzimuthFormatTest {

    @Test fun `quadrant bearing in each quadrant and on the cardinals`() {
        assertEquals("N", AzimuthFormat.quadrantBearing(0.0))
        assertEquals("N 45°18′ E", AzimuthFormat.quadrantBearing(45.3))   // review example
        assertEquals("E", AzimuthFormat.quadrantBearing(90.0))
        assertEquals("S 45°00′ E", AzimuthFormat.quadrantBearing(135.0))
        assertEquals("S", AzimuthFormat.quadrantBearing(180.0))
        assertEquals("S 45°00′ W", AzimuthFormat.quadrantBearing(225.0))
        assertEquals("W", AzimuthFormat.quadrantBearing(270.0))
        assertEquals("N 45°00′ W", AzimuthFormat.quadrantBearing(315.0))
        assertEquals("N 0°06′ W", AzimuthFormat.quadrantBearing(359.9))
    }

    @Test fun `quadrant angle is measured from north or south toward east or west`() {
        assertEquals("N 22°48′ E", AzimuthFormat.quadrantBearing(22.8))
        assertEquals("S 79°30′ E", AzimuthFormat.quadrantBearing(100.5))
        assertEquals("S 10°15′ W", AzimuthFormat.quadrantBearing(190.25))
        assertEquals("N 89°59′ W", AzimuthFormat.quadrantBearing(270.0 + 1.0 / 60.0))
    }

    @Test fun `negative and over-360 azimuths wrap`() {
        assertEquals("N 45°00′ W", AzimuthFormat.quadrantBearing(-45.0))
        assertEquals("N", AzimuthFormat.quadrantBearing(360.0))
        assertEquals("N 0°30′ E", AzimuthFormat.quadrantBearing(720.5))
        assertEquals("S 45°00′ W", AzimuthFormat.quadrantBearing(-135.0))
        // Rounds to the nearest minute, so just below 360 becomes due north.
        assertEquals("N", AzimuthFormat.quadrantBearing(359.995))
    }

    @Test fun `azimuth text is 0 to 360 and never shows 360`() {
        assertEquals("0°", AzimuthFormat.azimuthText(0.0))
        assertEquals("42°", AzimuthFormat.azimuthText(42.4))
        assertEquals("45.3°", AzimuthFormat.azimuthText(45.3, 1))
        assertEquals("315°", AzimuthFormat.azimuthText(-45.0))
        assertEquals("0°", AzimuthFormat.azimuthText(359.6))
        assertEquals("0.0°", AzimuthFormat.azimuthText(359.96, 1))
        assertEquals("359.9°", AzimuthFormat.azimuthText(359.9, 1))
        assertEquals("0°", AzimuthFormat.azimuthText(360.0))
        assertEquals("0°", AzimuthFormat.azimuthText(-0.0))
        assertEquals("1°", AzimuthFormat.azimuthText(721.0))
    }

    @Test fun `normalize maps into 0 until 360`() {
        assertEquals(350.0, AzimuthFormat.normalize(-10.0), 1e-12)
        assertEquals(0.0, AzimuthFormat.normalize(360.0), 1e-12)
        assertEquals(10.0, AzimuthFormat.normalize(370.0), 1e-12)
    }
}
