package app.surrealar.ui.toolbar

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.surrealar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Step 4: the strip stays on one line by dropping low-priority tokens, measured with real text
 * (native graphics). Widths: a ~360 dp phone, and the SM-X940 in portrait (~924 dp) and landscape (~1480 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1480dp-h924dp-xhdpi")
class ToolbarFitTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        .apply { setTheme(R.style.Theme_SurveyingApp) }

    private class Strip(val root: View, val tokens: Map<String, ToolbarTokens>, val renderer: GnssToolbarRenderer)

    private fun strip(): Strip {
        val host = FrameLayout(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.location_status_bar, host, false)
        host.addView(root)
        fun tok(id: Int): ToolbarTokens {
            val r = root.findViewById<View>(id)
            return ToolbarTokens(r, r.findViewById(R.id.label), r.findViewById(R.id.value),
                r.findViewById<ImageView>(R.id.icon), r.findViewById<TextView>(R.id.separator))
        }
        val t = mapOf("src" to tok(R.id.token_source), "fix" to tok(R.id.token_fix), "sats" to tok(R.id.token_sats),
            "acc" to tok(R.id.token_acc), "age" to tok(R.id.token_age), "coord" to tok(R.id.token_coord),
            "alt" to tok(R.id.token_alt), "batt" to tok(R.id.token_batt))
        t.getValue("src").label.text = "SRC"; t.getValue("sats").label.text = "SATS"; t.getValue("acc").label.text = "H"
        t.getValue("age").label.text = "AGE"
        val r = GnssToolbarRenderer(t.getValue("src"), t.getValue("fix"), t.getValue("sats"), t.getValue("acc"),
            t.getValue("age"), t.getValue("coord"), t.getValue("alt"), t.getValue("batt"),
            colorFor = { 0 }, onBatteryVisibilityChanged = { t.getValue("batt").root.visibility = if (it) View.VISIBLE else View.GONE },
            strip = root)
        return Strip(root, t, r)
    }

    private val full = GnssToolbarState(
        sourceText = "RS2+", fixText = "Fixed", fixLevel = GnssStatusLevel.SUCCESS, satelliteText = "18/24",
        accuracyText = "±0.01 m", accuracyLevel = GnssStatusLevel.SUCCESS, latLonText = "41.234567, -76.123456",
        altitudeText = "422.81 m", correctionAgeText = "1.2s", batteryVisible = true,
        isExternal = true, isWaiting = false, isStale = false,
        coordLabel = "LL", altitudeLabel = "NAVD88",
    )

    private fun layoutAt(s: Strip, widthDp: Int) {
        val px = (widthDp * ctx.resources.displayMetrics.density).toInt()
        s.root.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        s.root.layout(0, 0, px, s.root.measuredHeight)
        s.renderer.render(full)
    }

    private fun visible(s: Strip) = s.tokens.filterValues { it.root.visibility == View.VISIBLE }.keys

    @Test fun `phone width keeps the high-priority tokens and drops from height up`() {
        val s = strip(); layoutAt(s, 360)
        val v = visible(s)
        println("360 dp: visible = $v")
        assertTrue("source and SOL always kept", v.containsAll(listOf("src", "fix")))
        assertFalse("height goes first", "alt" in v)
        assertFalse("coordinates before SATS", "coord" in v && "sats" !in v)
    }

    @Test fun `SM-X940 widths show everything`() {
        for (w in listOf(924, 1480)) {
            val s = strip(); layoutAt(s, w)
            val v = visible(s)
            println("$w dp: visible = $v")
            assertEquals("$w dp", setOf("src", "fix", "sats", "acc", "age", "coord", "alt", "batt"), v)
        }
    }

    @Test fun `value changes don't change token widths`() {
        val s = strip(); layoutAt(s, 1480)
        val acc = s.tokens.getValue("acc").value
        acc.text = "±0.01 m"; acc.measure(0, 0); val w1 = acc.measuredWidth
        acc.text = "~±12.34 m"; acc.measure(0, 0); val w2 = acc.measuredWidth
        assertEquals("ACC keeps its width", w1, w2)
    }
    @Test fun `coordinates and height keep their width when a value loses a digit or its sign`() {
        val s = strip(); layoutAt(s, 1480)
        val coord = s.tokens.getValue("coord").value
        val alt = s.tokens.getValue("alt").value
        coord.measure(0, 0); val c1 = coord.measuredWidth
        alt.measure(0, 0); val a1 = alt.measuredWidth
        s.renderer.render(full.copy(latLonText = "41.234567, 76.123456", altitudeText = "22.81 m"))
        coord.measure(0, 0); alt.measure(0, 0)
        assertTrue("coord shrank: $c1 → ${coord.measuredWidth}", coord.measuredWidth >= c1)
        assertTrue("height shrank: $a1 → ${alt.measuredWidth}", alt.measuredWidth >= a1)
        assertTrue("height min width covers a sign and the digits", alt.minWidth > 0)
    }
}