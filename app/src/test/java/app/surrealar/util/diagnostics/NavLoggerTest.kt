package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class NavLoggerTest {
    @Test fun `screen names come from the resource name`() {
        assertEquals("nav_render_map", NavLogger.screenName("app.surrealar:id/nav_render_map", "Map", 7))
        assertEquals("Map", NavLogger.screenName("2131230999", "Map", 7))
        assertEquals("id_7", NavLogger.screenName(null, null, 7))
    }

    @Test fun `only argument names are logged`() {
        assertEquals("Screen nav_point args=[coordinateId,name]", NavLogger.line("nav_point", listOf("name", "coordinateId")))
        assertEquals("Screen nav_home", NavLogger.line("nav_home", emptyList()))
    }
}