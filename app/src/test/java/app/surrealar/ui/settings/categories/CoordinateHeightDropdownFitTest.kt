package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.surrealar.R
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The height-reference field on Settings → Coordinates is 280 dp wide. The survey labels name the geoid
 * model ("Orthometric height H (receiver geoid, model unknown)"), so every label must show in full
 * (at most two lines, nothing ellipsized), measured with real text (native graphics) on the SM-X940.
 * Final visual check on the tablet is still listed in the Phase 1 checklist.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1480dp-h924dp-xhdpi")
class CoordinateHeightDropdownFitTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        .apply { setTheme(R.style.Theme_SurveyingApp) }

    @Test fun `every height label fits the 280dp field without clipping`() {
        val host = FrameLayout(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.content_settings_coordinate_display, host, false)
        host.addView(root)
        val field = root.findViewById<AutoCompleteTextView>(R.id.spinner_coord_vertical)
        assertEquals(2, field.maxLines)
        val widthPx = (1480 * ctx.resources.displayMetrics.density).toInt()
        // Phase 2 labels are part of the sweep (entries), named here so dropping one fails loudly.
        assertTrue(VerticalReference.entries.containsAll(listOf(VerticalReference.EGM2008, VerticalReference.EGM96)))
        // Phase 3 beta label (shown only with the NSRS 2022 preview on) is part of the sweep too.
        assertTrue(VerticalReference.entries.contains(VerticalReference.GEOID2022))
        for (v in VerticalReference.entries) {
            field.setText(v.label, false)
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, widthPx, root.measuredHeight)
            val layout = checkNotNull(field.layout) { "no text layout for ${v.label}" }
            println("${v.label}: ${layout.lineCount} line(s) in ${field.width}px")
            assertTrue("${v.label} needs ${layout.lineCount} lines", layout.lineCount <= 2)
            for (line in 0 until layout.lineCount) {
                assertEquals("${v.label} is ellipsized", 0, layout.getEllipsisCount(line))
            }
        }
    }

    @Test fun `every system label fits the single-line 280dp system field (incl SPCS2022 beta)`() {
        val host = FrameLayout(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.content_settings_coordinate_display, host, false)
        host.addView(root)
        val field = root.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal)
        val widthPx = (1480 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(app.surrealar.settings.model.HorizontalSystem.entries.contains(app.surrealar.settings.model.HorizontalSystem.SPCS2022))
        // Datum field texts shown for SPCS2022 (the zone's frame) are measured in the datum field below.
        for (h in app.surrealar.settings.model.HorizontalSystem.entries) {
            field.setText(h.label, false)
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, widthPx, root.measuredHeight)
            val available = field.width - field.totalPaddingLeft - field.totalPaddingRight
            val textPx = field.paint.measureText(h.label)
            println("${h.label}: ${textPx}px of ${available}px")
            assertTrue("${h.label} needs ${textPx}px, field has ${available}px", textPx <= available)
        }
    }

    @Test fun `every datum label fits the single-line 280dp datum field`() {
        val host = FrameLayout(ctx)
        val root = LayoutInflater.from(ctx).inflate(R.layout.content_settings_coordinate_display, host, false)
        host.addView(root)
        val field = root.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum)
        val widthPx = (1480 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(OutputDatum.entries.contains(OutputDatum.ITRF2020))
        // Phase 4 WP X: the 2022 beta datums are entries; SPCS2022 shows its zone's frame in this field.
        assertTrue(OutputDatum.entries.contains(OutputDatum.NATRF2022) && OutputDatum.entries.contains(OutputDatum.MATRF2022))
        val texts = OutputDatum.entries.map { it.label } + CoordinatesSettingsController.SPCS2022_ZONE_FRAME +
            listOf("NATRF2022", "PATRF2022", "CATRF2022", "MATRF2022").map { "$it (zone frame, beta)" }
        for (label in texts) {
            val d = object { val label = label }
            field.setText(d.label, false)
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, widthPx, root.measuredHeight)
            val available = field.width - field.totalPaddingLeft - field.totalPaddingRight
            val textPx = field.paint.measureText(d.label)
            println("${d.label}: ${textPx}px of ${available}px")
            assertTrue("${d.label} needs ${textPx}px, field has ${available}px", textPx <= available)
        }
    }
}
