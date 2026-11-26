package app.surrealar.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import app.surrealar.R
import app.surrealar.gnss.diagnostics.NmeaLogStats
import app.surrealar.ui.components.FixBadgeView
import com.google.android.material.color.MaterialColors
import io.github.pennstatecs.geokit.gnss.model.FixSnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Previews (not assertions) for the phase-1 design-system components, light and dark, as PNGs under
 * app/build/previews/: the semantic palette swatches, the type scale, FixBadgeView in its three quality states,
 * the (legacy) Settings layout and the Model viewer chrome. Skips when native rendering is unavailable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DesignSystemPreviewTest {

    private val outDir = File("build/previews").apply { mkdirs() }

    private fun write(view: View, name: String, widthDp: Int, heightDp: Int? = null) {
        val d = view.resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        val hSpec = if (heightDp != null) View.MeasureSpec.makeMeasureSpec((heightDp * d).toInt(), View.MeasureSpec.EXACTLY)
            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), hSpec)
        val h = view.measuredHeight.coerceIn(1, (2400 * d).toInt())
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(runCatching { MaterialColors.getColor(view, android.R.attr.colorBackground) }.getOrDefault(Color.WHITE))
        view.draw(canvas)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("preview: ${File(outDir, "$name.png").absolutePath}")
    }

    private fun ctx() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SurveyingApp)

    private fun dp(c: android.content.Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun palette(theme: String) {
        val c = ctx()
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16)) }
        val roles = listOf(
            Triple("Success · RTK Fix", R.attr.colorStatusSuccess, R.attr.colorStatusSuccessContainer to R.attr.colorOnStatusSuccessContainer),
            Triple("Warning · Float / aging", R.attr.colorStatusWarning, R.attr.colorStatusWarningContainer to R.attr.colorOnStatusWarningContainer),
            Triple("Error · No fix", R.attr.colorStatusError, R.attr.colorStatusErrorContainer to R.attr.colorOnStatusErrorContainer),
            Triple("Info · DGPS", R.attr.colorStatusInfo, R.attr.colorStatusInfoContainer to R.attr.colorOnStatusInfoContainer),
            Triple("Neutral · Single", R.attr.colorStatusNeutral, R.attr.colorStatusNeutralContainer to R.attr.colorOnStatusNeutralContainer),
        )
        for ((label, fg, cont) in roles) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 4), 0, dp(c, 4)) }
            row.addView(TextView(c).apply {
                text = "$label  ±0.012 m"
                setTextAppearance(R.style.TextAppearance_App_Reading)
                setTextColor(MaterialColors.getColor(col, fg))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(c).apply {
                text = label.substringBefore(" ·")
                setTextAppearance(R.style.TextAppearance_App_Supporting)
                setTextColor(MaterialColors.getColor(col, cont.second))
                setBackgroundColor(MaterialColors.getColor(col, cont.first))
                setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2))
            })
            col.addView(row)
        }
        write(col, "design_palette_$theme", 411)
    }

    private fun typeScale(theme: String) {
        val c = ctx()
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16)) }
        for ((style, sample) in listOf(
            R.style.TextAppearance_App_PageTitle to "Page title 24",
            R.style.TextAppearance_App_SectionHeading to "Section heading 18",
            R.style.TextAppearance_App_Body to "Body 16 — primary content",
            R.style.TextAppearance_App_Secondary to "Secondary 14 — descriptions",
            R.style.TextAppearance_App_Reading to "Reading 14  N 435,037.47  E 2,442,754.19",
            R.style.TextAppearance_App_ReadingCompact to "Compact 13  ±0.012 m  1.2 s  18/24",
            R.style.TextAppearance_App_Supporting to "Supporting 12 — captions, hints",
            R.style.TextAppearance_App_Mono to "\$GNGGA,123519,4807.038,N,01131.000,E,4,18,0.7",
        )) col.addView(TextView(c).apply { setTextAppearance(style); text = sample; setPadding(0, dp(c, 4), 0, dp(c, 4)) })
        write(col, "design_type_scale_$theme", 411)
    }

    private fun fixBadge(theme: String) {
        val c = ctx()
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16)) }
        for ((sats, hdop, rate) in listOf(Triple(18, 0.7, 12.0), Triple(6, 1.6, 3.0), Triple(3, 4.2, 0.5))) {
            val badge = FixBadgeView(c)
            badge.updateFixData(FixSnapshot(timestampMillis = 1_700_000_000_000L, timestampSource = TimestampSource.NMEA_ZDA,
                lat = 41.2, lon = -75.9, altMsl = null, geoidSeparation = null, altEllipsoidal = null, speedMps = null,
                courseDeg = null, satsUsed = sats, hdop = hdop, vDop = null, pDop = null, satellitesInView = 24,
                horizontalAccuracyM = null, verticalAccuracyM = null, correctionAgeS = null, correctionStationId = null,
                multipathIndex = null, rtkStatus = null, stdLatM = null, stdLonM = null, stdAltM = null))
            badge.updateStreamHealth(NmeaLogStats(linesPerSecond = rate, totalLines = 1000, parseErrors = if (rate < 1) 80 else 2))
            col.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(c, 8) })
        }
        write(col, "fix_badge_$theme", 411)
    }

    private fun settings(theme: String) {
        val c = ctx()
        val page = LayoutInflater.from(c).inflate(R.layout.fragment_settings, FrameLayout(c), false)
        listOf(R.id.group_external_config, R.id.import_progress_container).forEach { id -> page.findViewById<View>(id)?.visibility = View.VISIBLE }
        page.findViewById<TextView>(R.id.text_import_progress)?.text = "Importing 42 / 120 coordinates…"
        write(page, "settings_legacy_$theme", 411)
    }

    private fun modelViewer(theme: String) {
        val c = ctx()
        val page = LayoutInflater.from(c).inflate(R.layout.activity_model_viewer, FrameLayout(c), false)
        page.findViewById<TextView>(R.id.text_loading)?.text = "Now loading Bridge pier.glb"
        page.findViewById<TextView>(R.id.text_model_name)?.text = "Bridge pier"
        page.findViewById<TextView>(R.id.text_model_filename)?.text = "bridge_pier.glb"
        write(page, "model_viewer_$theme", 411, heightDp = 720)
    }

    private fun renderAll(theme: String) {
        for ((name, f) in listOf<Pair<String, (String) -> Unit>>("palette" to ::palette, "type" to ::typeScale,
            "fixBadge" to ::fixBadge, "settings" to ::settings, "modelViewer" to ::modelViewer)) {
            try { f(theme) } catch (e: Throwable) {
                if (e is UnsatisfiedLinkError || e.cause is UnsatisfiedLinkError) Assume.assumeNoException("native rendering unavailable", e)
                println("preview skipped: $name $theme: $e")
            }
        }
    }

    @Test fun `render light`() = renderAll("light")

    @Test fun `render dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        renderAll("dark")
    }
}
