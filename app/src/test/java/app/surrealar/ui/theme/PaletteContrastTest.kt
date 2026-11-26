package app.surrealar.ui.theme

import android.app.Application
import android.content.Context
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import app.surrealar.R
import app.surrealar.ui.toolbar.GnssStatusLevel
import app.surrealar.ui.toolbar.GnssToolbarColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.appcompat.R as A
import com.google.android.material.R as M

/**
 * WCAG checks on the colours the app actually resolves (resources + Theme.SurveyingApp), run in the light theme
 * ([PaletteContrastLightTest]) and the night theme ([PaletteContrastNightTest]). Thresholds: text 4.5:1, component
 * boundaries / indicators 3:1. Each check prints one "contrast[...]" line (the table in docs/design-system.md).
 */
abstract class PaletteContrastChecks {

    protected abstract val themeName: String

    private val ctx: Context by lazy { ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SurveyingApp) }

    private fun attr(id: Int): Int {
        val tv = TypedValue()
        assertTrue("$themeName: theme attribute ${name(id)} does not resolve", ctx.theme.resolveAttribute(id, tv, true))
        return if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
    }

    private fun name(id: Int) = runCatching { ctx.resources.getResourceEntryName(id) }.getOrDefault(id.toString())
    private fun color(id: Int) = ctx.getColor(id)

    private fun check(what: String, fg: Int, bg: Int, min: Double) {
        val r = Wcag.contrast(fg, bg)
        println("contrast[$themeName] %-56s %s on %s = %5.2f (min %.1f)".format(what, Wcag.hex(fg), Wcag.hex(bg), r, min))
        assertTrue("$themeName: $what ${Wcag.hex(fg)} on ${Wcag.hex(bg)} = " + "%.2f < %.1f".format(r, min), r >= min)
    }

    private val surfaces by lazy {
        linkedMapOf(
            "surface" to attr(M.attr.colorSurface),
            "background" to attr(android.R.attr.colorBackground),
            "surfaceContainer" to attr(M.attr.colorSurfaceContainer),
            "surfaceContainerHigh" to attr(M.attr.colorSurfaceContainerHigh),
            "surfaceContainerHighest" to attr(M.attr.colorSurfaceContainerHighest),
            "surfaceVariant" to attr(M.attr.colorSurfaceVariant),
        )
    }

    private class Role(val name: String, val fg: Int, val container: Int, val onContainer: Int,
                       val fgAttr: Int, val containerAttr: Int, val onContainerAttr: Int)

    private val roles = listOf(
        Role("success", R.color.status_success, R.color.status_success_container, R.color.status_on_success_container,
            R.attr.colorStatusSuccess, R.attr.colorStatusSuccessContainer, R.attr.colorOnStatusSuccessContainer),
        Role("warning", R.color.status_warning, R.color.status_warning_container, R.color.status_on_warning_container,
            R.attr.colorStatusWarning, R.attr.colorStatusWarningContainer, R.attr.colorOnStatusWarningContainer),
        Role("error", R.color.status_error, R.color.status_error_container, R.color.status_on_error_container,
            R.attr.colorStatusError, R.attr.colorStatusErrorContainer, R.attr.colorOnStatusErrorContainer),
        Role("info", R.color.status_info, R.color.status_info_container, R.color.status_on_info_container,
            R.attr.colorStatusInfo, R.attr.colorStatusInfoContainer, R.attr.colorOnStatusInfoContainer),
        Role("neutral", R.color.status_neutral, R.color.status_neutral_container, R.color.status_on_neutral_container,
            R.attr.colorStatusNeutral, R.attr.colorStatusNeutralContainer, R.attr.colorOnStatusNeutralContainer),
    )

    @Test fun `status attributes resolve to the status colours`() {
        for (r in roles) {
            assertEquals("$themeName ${r.name} fg", color(r.fg), attr(r.fgAttr))
            assertEquals("$themeName ${r.name} container", color(r.container), attr(r.containerAttr))
            assertEquals("$themeName ${r.name} onContainer", color(r.onContainer), attr(r.onContainerAttr))
        }
        // Legacy names alias the roles.
        assertEquals(color(R.color.status_success), color(R.color.app_success))
        assertEquals(color(R.color.status_warning), color(R.color.app_warning))
        assertEquals(color(R.color.status_error), color(R.color.app_error))
        assertEquals(color(R.color.status_info), color(R.color.app_info))
    }

    @Test fun `status foregrounds pass text contrast on every surface`() {
        for (r in roles) for ((s, bg) in surfaces) check("status_${r.name} on $s", color(r.fg), bg, Wcag.TEXT)
    }

    @Test fun `on-container text passes on its container`() {
        for (r in roles) check("status_on_${r.name}_container on its container", color(r.onContainer), color(r.container), Wcag.TEXT)
    }

    @Test fun `RTK chip colours pass on surface`() {
        for (id in listOf(R.color.status_fix, R.color.status_float, R.color.status_dgps, R.color.status_single, R.color.status_no_fix))
            check(name(id) + " on surface", color(id), surfaces.getValue("surface"), Wcag.TEXT)
    }

    @Test fun `material text roles pass`() {
        val onSurface = attr(M.attr.colorOnSurface)
        val onVariant = attr(M.attr.colorOnSurfaceVariant)
        for ((s, bg) in surfaces) {
            check("colorOnSurface on $s", onSurface, bg, Wcag.TEXT)
            check("colorOnSurfaceVariant (secondary) on $s", onVariant, bg, Wcag.TEXT)
            // Settings.Row.Subtitle / CaptureMetricLabel draw onSurface at android:alpha 0.7.
            check("colorOnSurface at alpha 0.7 on $s", Wcag.withAlpha(onSurface, 0.7f), bg, Wcag.TEXT)
        }
        val surface = surfaces.getValue("surface")
        check("colorPrimary on surface", attr(A.attr.colorPrimary), surface, Wcag.TEXT)
        check("colorPrimary on background", attr(A.attr.colorPrimary), surfaces.getValue("background"), Wcag.TEXT)
        check("colorSecondary on surface", attr(M.attr.colorSecondary), surface, Wcag.TEXT)
        check("colorError on surface", attr(A.attr.colorError), surface, Wcag.TEXT)
        check("colorTertiary on surface", attr(M.attr.colorTertiary), surface, Wcag.TEXT)
        check("colorOnPrimary on colorPrimary", attr(M.attr.colorOnPrimary), attr(A.attr.colorPrimary), Wcag.TEXT)
        check("colorOnSecondary on colorSecondary", attr(M.attr.colorOnSecondary), attr(M.attr.colorSecondary), Wcag.TEXT)
        check("colorOnError on colorError", attr(M.attr.colorOnError), attr(A.attr.colorError), Wcag.TEXT)
        check("colorOnPrimaryContainer on its container", attr(M.attr.colorOnPrimaryContainer), attr(M.attr.colorPrimaryContainer), Wcag.TEXT)
        check("colorOnSecondaryContainer on its container", attr(M.attr.colorOnSecondaryContainer), attr(M.attr.colorSecondaryContainer), Wcag.TEXT)
        check("colorOnErrorContainer on its container", attr(M.attr.colorOnErrorContainer), attr(M.attr.colorErrorContainer), Wcag.TEXT)
        check("colorOnTertiaryContainer on its container", attr(M.attr.colorOnTertiaryContainer), attr(M.attr.colorTertiaryContainer), Wcag.TEXT)
    }

    @Test fun `component boundaries and indicators pass`() {
        for (s in listOf("surface", "background")) check("colorOutline on $s", attr(M.attr.colorOutline), surfaces.getValue(s), Wcag.NON_TEXT)
        check("colorPrimary (selected, focus) on surface", attr(A.attr.colorPrimary), surfaces.getValue("surface"), Wcag.NON_TEXT)
        for (r in roles) check("status_${r.name} dot on surface", color(r.fg), surfaces.getValue("surface"), Wcag.NON_TEXT)
    }

    @Test fun `GNSS toolbar roles pass on the navy strip`() {
        val strip = color(R.color.app_status_strip)
        assertEquals("the strip stays #0F3554", 0xFF0F3554.toInt(), strip)
        for (id in listOf(R.color.toolbar_on_primary, R.color.toolbar_on_secondary, R.color.toolbar_success,
            R.color.toolbar_warning, R.color.toolbar_error, R.color.toolbar_info, R.color.app_on_status_strip,
            R.color.app_on_status_strip_variant)) check(name(id) + " on strip", color(id), strip, Wcag.TEXT)
        check("app_status_separator on strip", color(R.color.app_status_separator), strip, Wcag.NON_TEXT)
        for (level in GnssStatusLevel.entries) check("toolbar level $level on strip", GnssToolbarColors.color(ctx, level), strip, Wcag.TEXT)
    }

    @Test fun `GNSS toolbar warning pills pass`() {
        val strip = color(R.color.app_status_strip)
        val critBg = color(R.color.gnss_toolbar_warning_critical_bg)
        val cautBg = color(R.color.gnss_toolbar_warning_caution_bg)
        check("critical pill text on pill", color(R.color.gnss_toolbar_warning_critical_fg), critBg, Wcag.TEXT)
        check("caution pill text on pill", color(R.color.gnss_toolbar_warning_caution_fg), cautBg, Wcag.TEXT)
        // Pill edge on navy: the critical pill is outlined (its red fill alone is only 2.3:1); the amber fill passes alone.
        check("critical pill outline on strip", color(R.color.gnss_toolbar_warning_critical_stroke), strip, Wcag.NON_TEXT)
        check("caution pill fill on strip", cautBg, strip, Wcag.NON_TEXT)
        // Filled pills stay distinct from the coloured status text.
        for (id in listOf(R.color.toolbar_warning, R.color.toolbar_error)) {
            assertNotEquals(color(id), critBg)
            assertNotEquals(color(id), cautBg)
        }
    }

    @Test fun `AR overlay status text passes on the heaviest scrim over a white camera frame`() {
        val scrim = Wcag.composite(color(R.color.ar_overlay_bg_heavy), 0xFFFFFFFF.toInt())
        for (id in listOf(R.color.ar_overlay_success, R.color.ar_overlay_warning, R.color.ar_overlay_error,
            R.color.ar_overlay_info, R.color.ar_overlay_text)) check(name(id) + " on 73 pct black scrim over white", color(id), scrim, Wcag.TEXT)
    }

    @Test fun `model viewer canvas text passes`() {
        val canvas = color(R.color.model_viewer_canvas)
        check("model_viewer_on_canvas", color(R.color.model_viewer_on_canvas), canvas, Wcag.TEXT)
        check("model_viewer_on_canvas_error", color(R.color.model_viewer_on_canvas_error), canvas, Wcag.TEXT)
        check("model_viewer_on_canvas_accent (spinner)", color(R.color.model_viewer_on_canvas_accent), canvas, Wcag.NON_TEXT)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaletteContrastLightTest : PaletteContrastChecks() {
    override val themeName = "light"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "night")
class PaletteContrastNightTest : PaletteContrastChecks() {
    override val themeName = "night"
}
