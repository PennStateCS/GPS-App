package app.surrealar.ui.toolbar

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import app.surrealar.R
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.gnss.status.GnssStatusInterpreter.Warning
import app.surrealar.ui.settings.categories.ControllerHost
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures
import app.surrealar.ui.settings.receiver.idleMain
import app.surrealar.ui.toolbar.report.FakeGnssStatusDeps
import app.surrealar.ui.toolbar.report.GnssReportFixtures
import app.surrealar.ui.toolbar.report.GnssStatusDeps
import app.surrealar.ui.toolbar.report.GnssStatusSheet
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Layout previews (not assertions): the compact toolbar at phone (360 / 411 dp) and tablet (924 / 1280 dp) widths in
 * several states, and the GNSS Status report at phone and tablet widths, light and dark, as PNGs under
 * app/build/previews/. Skips (assumption failure) when native rendering is not available.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class GnssToolbarPreviewTest {

    private val outDir = File("build/previews").apply { mkdirs() }

    @After fun clear() { GnssStatusDeps.override = null }

    private fun write(view: View, name: String, widthDp: Int, measure: Boolean = true) {
        val d = view.resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        if (measure) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, w, view.measuredHeight.coerceIn(1, (2400 * d).toInt()))
        }
        val h = view.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(runCatching { MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface) }.getOrDefault(Color.WHITE))
        view.draw(canvas)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("preview: ${File(outDir, "$name.png").absolutePath}")
    }

    private val base = GnssToolbarState(
        sourceText = "RS4 Pro", fixText = "Fix", fixLevel = GnssStatusLevel.SUCCESS, satelliteText = "18/24",
        accuracyText = "±0.01 V ±0.02 m", accuracyCompactText = "±0.01 m", accuracyLevel = GnssStatusLevel.SUCCESS,
        latLonText = "N 435,037.47 · E 2,442,754.19 ftUS", altitudeText = "1,286.74 ftUS", correctionAgeText = "1.2s",
        batteryVisible = true, isExternal = true, isWaiting = false, isStale = false,
        coordLabel = "NE", altitudeLabel = "NAVD88", coordToggleAvailable = true,
    )

    private fun states() = mapOf(
        "fix" to base,
        "stale_corrections" to base.copy(fixLevel = GnssStatusLevel.WARNING, warnings = listOf(Warning.CORRECTIONS_STALE)),
        "aging_reference" to base.copy(fixLevel = GnssStatusLevel.WARNING, accuracyLevel = GnssStatusLevel.WARNING,
            accuracyCompactText = "±0.08 m", warnings = listOf(Warning.REFERENCE_UNCONFIRMED, Warning.CORRECTIONS_AGING)),
        "lost" to base.copy(fixText = "Fix (lost 12s)", fixLevel = GnssStatusLevel.NONE, accuracyText = null, accuracyCompactText = null,
            isWaiting = true, isStale = true),
        "connecting" to GnssToolbarStateMapper.waiting(app.surrealar.domain.model.LocationSourceType.EXTERNAL, "RS4 Pro"),
    )

    private fun renderToolbars(theme: String) {
        try {
            val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SurveyingApp)
            for ((name, state) in states()) for (w in listOf(360, 411, 924, 1280)) {
                val s = ToolbarFitTest.buildStrip(ctx, battery = if (name == "stale_corrections") 12 else 80)
                ToolbarFitTest.layoutAt(ctx, s, w, state)
                write(s.root, "gnss_toolbar_${name}_${w}dp_$theme", w, measure = false)
            }
        } catch (e: Throwable) {
            Assume.assumeNoException("native rendering unavailable", e)
        }
    }

    private fun renderReport(theme: String) {
        try {
            GnssStatusDeps.override = FakeGnssStatusDeps(
                frames = MutableStateFlow(GnssReportFixtures.frame(ReceiverUiFixtures.fix(diffAge = 11.4), corr = CorrectionHealth.AGING)),
                tilt = MutableStateFlow(app.surrealar.receiver.tilt.TiltStatus.NONE.copy(applicable = true)))
            val host = ControllerHost()
            for (w in listOf(411, 900)) {
                val sheet = GnssStatusSheet()
                sheet.show(host.activity.supportFragmentManager, GnssStatusSheet.TAG)
                host.activity.supportFragmentManager.executePendingTransactions()
                idleMain()
                write(sheet.requireView(), "gnss_report_${w}dp_$theme", w)
                sheet.dismiss(); idleMain()
            }
        } catch (e: Throwable) {
            Assume.assumeNoException("native rendering unavailable", e)
        }
    }

    @Test fun `render light`() { renderToolbars("light"); renderReport("light") }

    @Test fun `render dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        renderToolbars("dark"); renderReport("dark")
    }
}
