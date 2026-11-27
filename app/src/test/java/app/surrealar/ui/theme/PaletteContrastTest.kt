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
            "surfaceContainerLow" to attr(M.attr.colorSurfaceContainerLow),
            "surfaceContainerLowest" to attr(M.attr.colorSurfaceContainerLowest),
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
        assertEquals("the strip stays #2A4158 (Graphite & Cobalt)", 0xFF2A4158.toInt(), strip)
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

    @Test fun `StatusTone resolves to the status roles`() {
        val expected = mapOf(
            StatusTone.SUCCESS to roles[0], StatusTone.WARNING to roles[1], StatusTone.ERROR to roles[2],
            StatusTone.INFO to roles[3], StatusTone.NEUTRAL to roles[4],
        )
        for ((tone, r) in expected) {
            assertEquals("$themeName $tone fg", color(r.fg), tone.foreground(ctx))
            assertEquals("$themeName $tone container", color(r.container), tone.container(ctx))
            assertEquals("$themeName $tone onContainer", color(r.onContainer), tone.onContainer(ctx))
            // Filled badges (coordinate detail, model readiness) are drawn on surface and surfaceContainer cards:
            // the badge text always uses on-container, never white or the plain foreground.
            check("badge $tone text on its container", tone.onContainer(ctx), tone.container(ctx), Wcag.TEXT)
        }
    }

    @Test fun `chart axes labels and series pass on the card surfaces`() {
        for (s in listOf("surface", "surfaceContainer")) {
            val bg = surfaces.getValue(s)
            check("chart_text on $s", color(R.color.chart_text), bg, Wcag.TEXT)
            check("chart_title on $s", color(R.color.chart_title), bg, Wcag.TEXT)
            check("chart_axis on $s", color(R.color.chart_axis), bg, Wcag.NON_TEXT)
            check("chart_used_halo on $s", color(R.color.chart_used_halo), bg, Wcag.NON_TEXT)
            for (id in listOf(R.color.chart_bar_strong, R.color.chart_bar_medium, R.color.chart_bar_weak, R.color.chart_bar_unused,
                R.color.chart_constellation_gps, R.color.chart_constellation_glonass, R.color.chart_constellation_galileo,
                R.color.chart_constellation_beidou, R.color.chart_constellation_qzss, R.color.chart_constellation_sbas,
                R.color.chart_constellation_irnss, R.color.chart_constellation_other))
                check(name(id) + " (series) on $s", color(id), bg, Wcag.NON_TEXT)
        }
        // Series stay distinguishable from each other (not only by hue: the legend names them too).
        val series = listOf(R.color.chart_bar_strong, R.color.chart_bar_medium, R.color.chart_bar_weak, R.color.chart_bar_unused).map(::color)
        assertEquals("$themeName SNR series are distinct", series.size, series.toSet().size)
    }

    @Test fun `AR label overlays and chips pass on their scrims over a white camera frame`() {
        val white = 0xFFFFFFFF.toInt()
        val pill = Wcag.composite(color(R.color.ar_overlay_label_bg), white)
        for (id in listOf(R.color.ar_overlay_text, R.color.ar_overlay_text_secondary, R.color.ar_overlay_model_accent))
            check(name(id) + " on AR label pill over white", color(id), pill, Wcag.TEXT)
        val chip = Wcag.composite(color(R.color.ar_overlay_bg_dark), white)
        for (id in listOf(R.color.ar_overlay_text, R.color.ar_overlay_success, R.color.ar_overlay_warning,
            R.color.ar_overlay_error, R.color.ar_overlay_info)) check(name(id) + " on AR chip (80 pct black) over white", color(id), chip, Wcag.TEXT)
        // Model-linked pin border / arrow against the camera frame itself is not guaranteed (any colour can be behind
        // it); it always sits on or next to the dark pill, where it passes:
        check("ar_overlay_model_accent border on pill", color(R.color.ar_overlay_model_accent), pill, Wcag.NON_TEXT)
    }

    @Test fun `map point labels pass on their halo over a white tile`() {
        val halo = Wcag.composite(color(R.color.map_label_bg), 0xFFFFFFFF.toInt())
        check("map_label_text on 70 pct black halo over white tile", color(R.color.map_label_text), halo, Wcag.TEXT)
    }

    @Test fun `list selection and disabled text`() {
        val pc = attr(M.attr.colorPrimaryContainer)
        val onPc = attr(M.attr.colorOnPrimaryContainer)
        // Coordinate list selected row: name onPrimaryContainer, coordinates onPrimaryContainer at 0xB2 alpha,
        // lat/lon line onSurfaceVariant (unchanged by selection).
        check("selected row name on primaryContainer", onPc, pc, Wcag.TEXT)
        check("selected row coordinates (70 pct) on primaryContainer", Wcag.withAlpha(onPc, 0xB2 / 255f), pc, Wcag.TEXT)
        check("selected row lat/lon onSurfaceVariant on primaryContainer", attr(M.attr.colorOnSurfaceVariant), pc, Wcag.TEXT)
        check("selection accent colorPrimary on surface", attr(A.attr.colorPrimary), surfaces.getValue("surface"), Wcag.NON_TEXT)
        // Disabled text (M3: onSurface at 38 %) is exempt from WCAG 1.4.3; keep it discernible, not a pass/fail target.
        for (s in listOf("surface", "surfaceContainerHigh"))
            check("disabled text (onSurface 38 pct) on $s - WCAG-exempt floor", Wcag.withAlpha(attr(M.attr.colorOnSurface), 0.38f), surfaces.getValue(s), 2.0)
    }

    @Test fun `app bar text and icons pass`() {
        val bar = color(R.color.app_top_app_bar)
        check("app_on_top_app_bar on app_top_app_bar", color(R.color.app_on_top_app_bar), bar, Wcag.TEXT)
    }

    @Test fun `info is cyan and distinct from cobalt and passes on every surface`() {
        val info = color(R.color.status_info)
        val primary = attr(A.attr.colorPrimary)
        assertNotEquals("$themeName info must not be the interactive cobalt", primary, info)
        // Distinct hue: info is cyan (green channel well above red), cobalt is blue (blue >> green).
        val g = (info shr 8) and 0xFF; val r = (info shr 16) and 0xFF
        assertTrue("$themeName info ${Wcag.hex(info)} should be cyan-toned", g > r + 0x30)
        for ((s, bg) in surfaces) check("status_info (cyan) on $s", info, bg, Wcag.TEXT)
        check("status_info on primaryContainer (selected row)", info, attr(M.attr.colorPrimaryContainer), Wcag.TEXT)
        check("toolbar_info (DGPS) on strip", color(R.color.toolbar_info), color(R.color.app_status_strip), Wcag.TEXT)
    }

    @Test fun `overflow icon is theme-tinted and visible on list surfaces`() {
        // ic_more_vert_24 declares a root android:tint theme attribute, so the dots are tinted even when the inflating
        // context ignores app:tint. Resolve that attribute and check it as a meaningful graphic (3:1).
        val parser = ctx.resources.getXml(R.drawable.ic_more_vert_24)
        var tintAttr = 0
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "vector") {
                for (i in 0 until parser.attributeCount) if (parser.getAttributeNameResource(i) == android.R.attr.tint)
                    tintAttr = parser.getAttributeValue(i).removePrefix("?").toInt()
                break
            }
        }
        assertEquals("$themeName ic_more_vert_24 root tint is ?attr/colorOnSurfaceVariant", M.attr.colorOnSurfaceVariant, tintAttr)
        val dots = attr(tintAttr)
        for ((s, bg) in surfaces) check("overflow icon (onSurfaceVariant) on $s", dots, bg, Wcag.NON_TEXT)
        check("overflow icon on selected row (primaryContainer)", dots, attr(M.attr.colorPrimaryContainer), Wcag.NON_TEXT)
        // Map points panel: the same icon tinted app_on_top_app_bar on the dark bar.
        check("overflow icon on map panel bar", color(R.color.app_on_top_app_bar), color(R.color.app_top_app_bar), Wcag.NON_TEXT)
    }

    @Test fun `selected navigation passes`() {
        val primary = attr(A.attr.colorPrimary)
        val navFg = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.nav_item_color)!!
            .getColorForState(intArrayOf(android.R.attr.state_checked), 0)
        assertEquals("$themeName drawer selected item is cobalt", primary, navFg)
        check("drawer selected text on nav_selected_bg", navFg, color(R.color.nav_selected_bg), Wcag.TEXT)
        check("settings selected text (colorPrimary) on dev_category_selected_bg", primary, color(R.color.dev_category_selected_bg), Wcag.TEXT)
        check("settings selected accent bar on sidebar (surfaceVariant)", color(R.color.dev_category_selected_accent),
            surfaces.getValue("surfaceVariant"), Wcag.NON_TEXT)
        check("unselected nav text on drawer (surfaceContainer)", attr(M.attr.colorOnSurfaceVariant), surfaces.getValue("surfaceContainer"), Wcag.TEXT)
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
