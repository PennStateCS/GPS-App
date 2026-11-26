package app.surrealar.ui.toolbar

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import app.surrealar.R
import app.surrealar.gnss.status.GnssStatusInterpreter.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The compact strip stays on one line by applying [ToolbarPriority], measured with real text (native graphics).
 * Widths: a 360 dp phone, a 411 dp phone, and the SM-X940 in portrait (~924 dp) and landscape (~1480 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1480dp-h924dp-xhdpi")
class ToolbarFitTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        .apply { setTheme(R.style.Theme_SurveyingApp) }

    internal class Strip(val root: View, val tokens: Map<String, ToolbarTokens>, val chevron: View, val renderer: GnssToolbarRenderer)

    internal fun strip(battery: Int? = null): Strip = buildStrip(ctx, battery)

    private val full = GnssToolbarState(
        sourceText = "RS4", fixText = "Fix", fixLevel = GnssStatusLevel.SUCCESS, satelliteText = "18/24",
        accuracyText = "±0.01 V ±0.02 m", accuracyCompactText = "±0.01 m", accuracyLevel = GnssStatusLevel.SUCCESS,
        latLonText = "N 435,037.47 · E 2,442,754.19 ftUS", altitudeText = "1,286.74 ftUS", correctionAgeText = "1.2s",
        batteryVisible = true, isExternal = true, isWaiting = false, isStale = false,
        coordLabel = "NE", altitudeLabel = "NAVD88", coordToggleAvailable = true,
    )
    private val warned = full.copy(fixLevel = GnssStatusLevel.WARNING, warnings = listOf(Warning.CORRECTIONS_STALE, Warning.VERTICAL_INVALID))

    private fun layoutAt(s: Strip, widthDp: Int, state: GnssToolbarState = full) = Companion.layoutAt(ctx, s, widthDp, state)

    @Test fun `tablet widths show every compact field, one line`() {
        for (w in listOf(924, 1480)) {
            val s = strip(); layoutAt(s, w)
            assertEquals("$w dp", setOf("src", "fix", "acc", "coord", "alt", "chevron"), s.renderer.visibleTokens())
            val s2 = strip(battery = 12); layoutAt(s2, w, warned)
            assertEquals("$w dp warned", setOf("src", "fix", "acc", "warn", "batt", "coord", "alt", "chevron"), s2.renderer.visibleTokens())
        }
    }

    @Test fun `compact accuracy is horizontal only (vertical, sats, correction age are report-only)`() {
        val s = strip(); layoutAt(s, 1480)
        assertEquals("±0.01 m", s.tokens.getValue("acc").value.text.toString())
        val shown = (s.root as android.view.ViewGroup).allTexts()
        assertFalse(shown.toString(), shown.any { it.contains("1.2s") || it.contains("18/24") || it.contains(" V ") })
    }

    @Test fun `phone width - priority drops elevation, then coordinates, never solution, accuracy, warning or chevron`() {
        for (w in listOf(360, 411)) {
            val s = strip(battery = 12); layoutAt(s, w, warned)
            val v = s.renderer.visibleTokens()
            println("$w dp: visible = $v")
            assertTrue("$w dp keeps critical tokens: $v", v.containsAll(ToolbarPriority.NEVER_DROPPED))
            assertFalse("battery goes before elevation", "batt" in v && "alt" !in v)
            assertFalse("elevation goes before coordinates", "alt" in v && "coord" !in v)
            assertFalse("coordinates go before the source", "src" !in v && "coord" in v)
            // at 360 dp the warning switched to its short text
            if (w == 360) assertEquals("Corr. stale", s.tokens.getValue("warn").value.text.toString())
        }
    }

    @Test fun `priority order - elevation never shown without its coordinates neighbour being considered first`() {
        assertEquals(listOf(ToolbarPriority.Step.HIDE_PREFIX_LABELS, ToolbarPriority.Step.SHORT_WARNING, ToolbarPriority.Step.HIDE_BATTERY,
            ToolbarPriority.Step.HIDE_ELEVATION, ToolbarPriority.Step.HIDE_COORDINATES, ToolbarPriority.Step.HIDE_SOURCE), ToolbarPriority.ORDER)
        assertEquals(setOf("fix", "acc", "warn", "chevron"), ToolbarPriority.NEVER_DROPPED)
    }

    @Test fun `left group anchored left, right group anchored right, no overlap`() {
        for (w in listOf(411, 924, 1480)) {
            val s = strip(); layoutAt(s, w)
            val src = s.tokens.getValue("src").root
            val coordOrAlt = listOf(s.tokens.getValue("coord").root, s.tokens.getValue("alt").root).filter { it.visibility == View.VISIBLE }
            val d = ctx.resources.displayMetrics.density
            assertTrue("$w: source at the left edge (${src.left})", src.left <= (12 * d).toInt())
            assertTrue("$w: chevron at the right edge", s.chevron.right >= s.root.width - (6 * d).toInt())
            val leftEnd = listOf("src", "fix", "acc", "warn", "batt").map { s.tokens.getValue(it).root }
                .filter { it.visibility == View.VISIBLE }.maxOf { it.right }
            val rightStart = (coordOrAlt + s.chevron).minOf { it.left }
            assertTrue("$w: groups overlap ($leftEnd > $rightStart)", leftEnd <= rightStart)
        }
    }

    @Test fun `value changes don't change token widths`() {
        val s = strip(); layoutAt(s, 1480)
        val acc = s.tokens.getValue("acc").value
        acc.text = "±0.01 m"; acc.measure(0, 0); val w1 = acc.measuredWidth
        acc.text = "~±12.34 m"; acc.measure(0, 0); val w2 = acc.measuredWidth
        assertEquals("ACC keeps its width", w1, w2)
        val coord = s.tokens.getValue("coord").value
        val alt = s.tokens.getValue("alt").value
        coord.measure(0, 0); val c1 = coord.measuredWidth
        alt.measure(0, 0); val a1 = alt.measuredWidth
        s.renderer.render(full.copy(latLonText = "N 35,037.47 · E 2,442,754.19 ftUS", altitudeText = "286.74 ftUS"))
        coord.measure(0, 0); alt.measure(0, 0)
        assertTrue("coord shrank", coord.measuredWidth >= c1)
        assertTrue("height shrank", alt.measuredWidth >= a1)
    }

    @Test fun `longest height label and the loading state still fit on the tablet`() {
        for (state in listOf(full.copy(altitudeLabel = "NAPGD2022β", altitudeText = "-1,234.56 ftUS"),
                             full.copy(altitudeLabel = "NAPGD2022β", altitudeText = GnssToolbarStateMapper.HEIGHT_LOADING))) {
            for (w in listOf(924, 1480)) {
                val s = strip(); layoutAt(s, w, state)
                assertEquals("$w dp", setOf("src", "fix", "acc", "coord", "alt", "chevron"), s.renderer.visibleTokens())
                assertEquals("NAPGD2022β", s.tokens.getValue("alt").label.text.toString())
            }
        }
    }

    @Test fun `battery shows only when low`() {
        assertFalse(ToolbarBatteryPolicy.showInCompact(null))
        assertFalse(ToolbarBatteryPolicy.showInCompact(21))
        assertTrue(ToolbarBatteryPolicy.showInCompact(20))
        val s = strip(battery = 80); layoutAt(s, 1480)
        assertFalse("batt" in s.renderer.visibleTokens())
    }

    @Test fun `accessibility - descriptions, polite live region on the warning only, click labels`() {
        val s = strip(); layoutAt(s, 1480, warned.copy(sourceDescription = "Source: RS4 receiver", fixDescription = "Solution: RTK fix",
            accuracyHDescription = "Accuracy plus or minus 1 centimetres"))
        val warn = s.tokens.getValue("warn").root
        assertEquals("Warning: Corrections stale. Also: Vertical accuracy invalid", warn.contentDescription)
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE, ViewCompat.getAccessibilityLiveRegion(warn))
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_NONE, ViewCompat.getAccessibilityLiveRegion(s.tokens.getValue("coord").root))
        assertEquals("Solution: RTK fix", s.tokens.getValue("fix").root.contentDescription)
        assertEquals("Accuracy plus or minus 1 centimetres", s.tokens.getValue("acc").root.contentDescription)
        assertEquals(ctx.getString(R.string.gnss_toolbar_open_report_cd), s.chevron.contentDescription)
        // Touch targets: every token is at least 48 dp tall.
        val min = (48 * ctx.resources.displayMetrics.density).toInt()
        listOf("src", "fix", "acc", "warn", "coord", "alt").forEach { assertTrue(it, s.tokens.getValue(it).root.height >= min) }
        assertTrue(s.chevron.height >= min)
        // Re-rendering the same warning does not touch the live-region text (no repeated announcements).
        val tv = s.tokens.getValue("warn").value
        val before = tv.text
        s.renderer.render(warned.copy(altitudeText = "1,286.75 ftUS"))
        assertTrue("same text instance", before === tv.text)
    }

    @Test fun `taps - toolbar opens the report, coordinates toggle when a grid exists`() {
        val s = strip(); layoutAt(s, 1480)
        var report = 0; var coord = 0
        s.renderer.bindActions(ToolbarActions(onOpenReport = { report++ }, onCoordTap = { coord++ }, onCoordLongPress = {}))
        listOf("src", "fix", "acc", "alt").forEach { s.tokens.getValue(it).root.performClick() }
        s.chevron.performClick(); s.root.performClick()
        s.tokens.getValue("coord").root.performClick()
        assertEquals(6, report); assertEquals(1, coord)
        assertTrue(ToolbarTapRules.coordTapToggles(full))
        assertFalse("nothing to toggle: coordinates open the report", ToolbarTapRules.coordTapToggles(full.copy(coordToggleAvailable = false)))
    }

    private fun android.view.ViewGroup.allTexts(): List<String> = (0 until childCount).flatMap { i ->
        when (val c = getChildAt(i)) {
            is android.view.ViewGroup -> if (c.visibility == View.VISIBLE) c.allTexts() else emptyList()
            is TextView -> if (c.visibility == View.VISIBLE) listOf(c.text.toString()) else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        /** Inflates location_status_bar with a renderer, as MainActivity does (shared with the previews). */
        internal fun buildStrip(ctx: android.content.Context, battery: Int? = null): Strip {
            val host = FrameLayout(ctx)
            val root = LayoutInflater.from(ctx).inflate(R.layout.location_status_bar, host, false)
            host.addView(root)
            fun tok(id: Int): ToolbarTokens {
                val r = root.findViewById<View>(id)
                return ToolbarTokens(r, r.findViewById(R.id.label), r.findViewById(R.id.value),
                    r.findViewById<ImageView>(R.id.icon), r.findViewById<TextView>(R.id.separator))
            }
            val t = mapOf("src" to tok(R.id.token_source), "fix" to tok(R.id.token_fix), "acc" to tok(R.id.token_acc),
                "warn" to tok(R.id.token_warn), "coord" to tok(R.id.token_coord), "alt" to tok(R.id.token_alt), "batt" to tok(R.id.token_batt))
            val chevron = root.findViewById<View>(R.id.toolbar_chevron)
            lateinit var r: GnssToolbarRenderer
            r = GnssToolbarRenderer(t.getValue("src"), t.getValue("fix"), t.getValue("acc"), t.getValue("warn"),
                t.getValue("coord"), t.getValue("alt"), t.getValue("batt"), chevron,
                colorFor = { level -> when (level) {
                    GnssStatusLevel.SUCCESS -> ctx.getColor(R.color.app_success)
                    GnssStatusLevel.WARNING -> ctx.getColor(R.color.app_warning)
                    GnssStatusLevel.ERROR -> ctx.getColor(R.color.app_error)
                    GnssStatusLevel.INFO -> ctx.getColor(R.color.app_info)
                    GnssStatusLevel.NONE -> android.graphics.Color.GRAY
                } },
                onBatteryVisibilityChanged = { visible -> r.renderBattery(visible, battery, false, textOnly = true) },
                strip = root)
            return Strip(root, t, chevron, r)
        }

        internal fun layoutAt(ctx: android.content.Context, s: Strip, widthDp: Int, state: GnssToolbarState) {
            val px = (widthDp * ctx.resources.displayMetrics.density).toInt()
            fun pass() {
                s.root.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                s.root.layout(0, 0, px, s.root.measuredHeight)
            }
            pass()
            s.renderer.render(state)
            pass()
        }
    }
}
