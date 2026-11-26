package app.surrealar.ui.theme

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.gnss.status.GnssStatusInterpreter.Warning
import app.surrealar.ui.common.SatelliteSignalChartView
import app.surrealar.ui.common.SkyplotView
import app.surrealar.ui.settings.categories.ControllerHost
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures
import app.surrealar.ui.settings.receiver.idleMain
import app.surrealar.ui.toolbar.GnssStatusLevel
import app.surrealar.ui.toolbar.GnssToolbarState
import app.surrealar.ui.toolbar.ToolbarFitTest
import app.surrealar.ui.toolbar.report.FakeGnssStatusDeps
import app.surrealar.ui.toolbar.report.GnssReportFixtures
import app.surrealar.ui.toolbar.report.GnssStatusDeps
import app.surrealar.ui.toolbar.report.GnssStatusSheet
import com.google.android.material.color.MaterialColors
import io.github.pennstatecs.geokit.gnss.model.Constellation
import io.github.pennstatecs.geokit.gnss.model.SkyGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Font-scale pass (docs/design-system.md, "Font scaling"): renders representative screens at font scale 1.0, 1.3
 * and 1.5 in light and dark as PNGs under app/build/previews/fontscale/, and audits every rendered tree for
 *
 * - text clipped vertically (a TextView shorter than its laid-out text, not ellipsized),
 * - text clipped horizontally (a line wider than its TextView, not ellipsized),
 * - text cut off by a fixed-size ancestor (outside scrolling containers),
 * - clickable controls smaller than 48 dp (reported; asserted for the controls listed in [touchChecked]).
 *
 * Camera / map imagery cannot be rendered here; the AR and map screens are their control layouts only.
 * Uses only APIs that also exist before phase 2 so the same renders can be produced for a before / after.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FontScalePreviewTest {

    private val outDir = File("build/previews/fontscale").apply { mkdirs() }
    private val scales = listOf(1.0f, 1.3f, 1.5f)

    data class Finding(val screen: String, val kind: String, val detail: String) {
        override fun toString() = "$kind  $screen  $detail"
    }

    private val findings = mutableListOf<Finding>()

    /** Controls that must keep a 48 dp touch target at every font scale (ids, by entry name). */
    private val touchChecked = setOf(
        "btn_overflow", "btn_selected_close", "btnArBack", "btnMapType", "btnMapMyLocation", "btnMapCenter",
        "btn_clear_stakeout", "check_fix_button", "btn_save", "btn_cancel",
    )

    @After fun clear() { GnssStatusDeps.override = null }

    private fun ctx(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SurveyingApp)
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun name(v: View): String = if (v.id == View.NO_ID) v.javaClass.simpleName
        else runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault(v.javaClass.simpleName)

    // ── Rendering ──────────────────────────────────────────────────────────────────────────────

    private fun layoutView(view: View, widthDp: Int, heightDp: Int?) {
        val d = view.resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        val hSpec = if (heightDp != null) View.MeasureSpec.makeMeasureSpec((heightDp * d).toInt(), View.MeasureSpec.EXACTLY)
            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), hSpec)
        view.layout(0, 0, w, view.measuredHeight.coerceIn(1, (3200 * d).toInt()))
    }

    private fun write(view: View, screen: String, tag: String, widthDp: Int, heightDp: Int? = null, alreadyLaidOut: Boolean = false) {
        if (!alreadyLaidOut) layoutView(view, widthDp, heightDp)
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(runCatching { MaterialColors.getColor(view, android.R.attr.colorBackground) }.getOrDefault(Color.WHITE))
        view.draw(canvas)
        val f = File(outDir, "${screen}_$tag.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("preview: ${f.absolutePath}")
        audit(view, "$screen@$tag")
    }

    // ── Audit ──────────────────────────────────────────────────────────────────────────────────

    private fun shown(v: View, root: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur.visibility != View.VISIBLE) return false
            if (cur === root) return true
            cur = cur.parent as? View
        }
        return true
    }

    private fun scrolls(p: ViewGroup) = p is ScrollView || p is NestedScrollView || p is HorizontalScrollView || p is RecyclerView

    private fun audit(root: View, screen: String) {
        val d = root.resources.displayMetrics.density
        fun walk(v: View) {
            if (!shown(v, root)) return
            if (v is TextView && !v.text.isNullOrEmpty() && v.width > 0) checkText(v, root, screen)
            if (v.isClickable && v.width > 0 && name(v) in touchChecked) {
                val minPx = 48 * d - 1
                if (v.width < minPx || v.height < minPx)
                    findings += Finding(screen, "touch<48dp", "${name(v)} ${(v.width / d).toInt()}x${(v.height / d).toInt()} dp")
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    private fun checkText(tv: TextView, root: View, screen: String) {
        val l = tv.layout ?: return
        val ellipsized = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
        val label = "${name(tv)} \"${tv.text.toString().take(40).replace('\n', ' ')}\""
        val needH = l.height + tv.compoundPaddingTop + tv.compoundPaddingBottom
        if (!ellipsized && tv.height + 2 < needH)
            findings += Finding(screen, "clipped-v", "$label view ${tv.height}px < text ${needH}px")
        // Horizontal: a single-line view whose text overflows (multi-line text wraps; Robolectric's per-line widths
        // of wrapped lines are not reliable enough to compare), or a wrapped line that splits a word mid-way
        // (e.g. "Receive" / "r details" when a button is too narrow for one word at a large font scale).
        val availW = tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight
        if (!ellipsized && tv.maxLines == 1 && l.lineCount == 1 && l.getLineWidth(0) > availW + 2)
            findings += Finding(screen, "clipped-h", "$label line wider than ${availW}px")
        val text = l.text
        for (i in 0 until l.lineCount - 1) {
            val end = l.getLineEnd(i)
            // A break with no whitespace on either side and not after a hyphen / slash splits a token
            // ("Receive|r", "±0.|012").
            if (end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace() && text[end - 1] !in "-/–—")
                findings += Finding(screen, "word-split", "$label breaks inside a word at \"${text.subSequence((end - 4).coerceAtLeast(0), (end + 3).coerceAtMost(text.length))}\"")
        }
        // Cut off by a fixed-size ancestor (scrolling containers are fine). Uses the text content box, so a
        // MaterialButton's transparent touch insets may extend past its parent without counting.
        var p = tv.parent as? ViewGroup
        while (p != null) {
            if (scrolls(p)) break
            val r = Rect(tv.compoundPaddingLeft, tv.compoundPaddingTop, tv.width - tv.compoundPaddingRight, tv.height - tv.compoundPaddingBottom)
            p.offsetDescendantRectToMyCoords(tv, r)
            r.offset(tv.scrollX, tv.scrollY)   // the conversion also subtracts the TextView's own (text) scroll
            if (p.clipChildren && (r.bottom > p.height + 2 || r.right > p.width + 2 || r.top < -2 || r.left < -2)) {
                findings += Finding(screen, "clipped-by-parent", "$label in ${name(p)} text box $r vs ${p.width}x${p.height}px")
                break
            }
            if (p === root) break
            p = p.parent as? ViewGroup
        }
    }

    // ── Screens ────────────────────────────────────────────────────────────────────────────────

    private fun inflate(c: Context, layout: Int): View = LayoutInflater.from(c).inflate(layout, FrameLayout(c), false)

    private fun screen(name: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            if (e is UnsatisfiedLinkError || e.cause is UnsatisfiedLinkError) Assume.assumeNoException("native rendering unavailable", e)
            println("preview skipped: $name: $e")
        }
    }

    private fun renderAll(theme: String) {
        for (scale in scales) {
            RuntimeEnvironment.setFontScale(scale)
            val tag = "${(scale * 10).toInt()}x_$theme"   // e.g. 13x_dark = font scale 1.3, dark
            val c = ctx()

            screen("home") { write(inflate(c, R.layout.fragment_home), "home", tag, 411) }

            screen("toolbar") {
                val base = GnssToolbarState(
                    sourceText = "RS4 Pro", fixText = "Fix", fixLevel = GnssStatusLevel.SUCCESS, satelliteText = "18/24",
                    accuracyText = "±0.01 V ±0.02 m", accuracyCompactText = "±0.01 m", accuracyLevel = GnssStatusLevel.SUCCESS,
                    latLonText = "N 435,037.47 · E 2,442,754.19 ftUS", altitudeText = "1,286.74 ftUS", correctionAgeText = "1.2s",
                    batteryVisible = true, isExternal = true, isWaiting = false, isStale = false,
                    coordLabel = "NE", altitudeLabel = "NAVD88", coordToggleAvailable = true,
                )
                val states = mapOf(
                    "fix" to base,
                    "float" to base.copy(fixText = "Float", fixLevel = GnssStatusLevel.WARNING, accuracyLevel = GnssStatusLevel.WARNING,
                        accuracyCompactText = "±0.21 m"),
                    "warning" to base.copy(fixLevel = GnssStatusLevel.WARNING, warnings = listOf(Warning.CORRECTIONS_STALE)),
                )
                for ((n, st) in states) for (w in listOf(411, 924)) {
                    val s = ToolbarFitTest.buildStrip(c, battery = 80)
                    ToolbarFitTest.layoutAt(c, s, w, st)
                    write(s.root, "toolbar_${n}_${w}dp", tag, w, alreadyLaidOut = true)
                }
            }

            screen("report") {
                GnssStatusDeps.override = FakeGnssStatusDeps(
                    frames = MutableStateFlow(GnssReportFixtures.frame(ReceiverUiFixtures.fix(diffAge = 11.4), corr = CorrectionHealth.AGING)),
                    tilt = MutableStateFlow(app.surrealar.receiver.tilt.TiltStatus.NONE.copy(applicable = true)))
                val host = ControllerHost()
                val sheet = GnssStatusSheet()
                sheet.show(host.activity.supportFragmentManager, GnssStatusSheet.TAG)
                host.activity.supportFragmentManager.executePendingTransactions()
                idleMain()
                write(sheet.requireView(), "gnss_report", tag, 411)
                sheet.dismiss(); idleMain()
            }

            screen("settings_receiver") {
                val page = inflate(c, R.layout.content_settings_location)
                listOf(R.id.card_external_receiver_setup, R.id.group_receiver_tilt, R.id.group_advanced_external, R.id.group_receiver_advanced)
                    .forEach { page.findViewById<View>(it)?.visibility = View.VISIBLE }
                write(page, "settings_receiver", tag, 411)
            }

            screen("coordinate_list") {
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                for ((i, n) in listOf("Control point CP-12 north abutment", "Well cap 3", "Hydrant 7").withIndex()) {
                    val row = LayoutInflater.from(c).inflate(R.layout.item_simple_coordinate, col, false)
                    row.findViewById<TextView>(R.id.text_name).text = n
                    row.findViewById<TextView>(R.id.text_coords).text = "N 435,037.47  E 2,442,754.19 ftUS"
                    row.findViewById<TextView>(R.id.text_latlon).text = "41.2453871, -75.8812734 · h 312.45 m"
                    if (i == 0) {
                        row.setBackgroundColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimaryContainer))
                        row.findViewById<TextView>(R.id.text_name).setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnPrimaryContainer))
                    }
                    col.addView(row)
                }
                write(col, "coordinate_list", tag, 411)
            }

            screen("coordinate_detail") {
                val page = inflate(c, R.layout.fragment_coordinate_detail)
                page.findViewById<View>(R.id.row_badges)?.visibility = View.VISIBLE
                for ((id, t) in listOf(R.id.badge_source to "RS4 PRO", R.id.badge_rtk to "FIX", R.id.badge_accuracy to "H ±0.012 m", R.id.badge_extra to "AVERAGED"))
                    page.findViewById<TextView>(id)?.apply { text = t; visibility = View.VISIBLE }
                write(page, "coordinate_detail", tag, 411)
                // Detail rows + survey stat tiles as the fragment inflates them.
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                val tiles = app.surrealar.ui.components.AdaptiveTileRow(c).apply { setPadding(dp(c, 17), dp(c, 4), dp(c, 17), dp(c, 8)) }
                for ((l, v) in listOf("Fix" to "RTK Fix", "H Acc" to "±0.012 m", "V Acc" to "±0.021 m", "Sats" to "18/24")) {
                    val t = LayoutInflater.from(c).inflate(R.layout.item_gnss_stat_tile, tiles, false)
                    t.findViewById<TextView>(R.id.tile_label).text = l
                    t.findViewById<TextView>(R.id.tile_value).text = v
                    tiles.addView(t)
                }
                col.addView(tiles)
                for ((l, v) in listOf("Latitude" to "41° 14' 43.39342\" N", "Longitude" to "75° 52' 52.58424\" W",
                        "Ellipsoidal height" to "312.452 m", "Northing (PA N, ftUS)" to "435,037.471")) {
                    val r = LayoutInflater.from(c).inflate(R.layout.item_coord_detail_row, col, false)
                    r.findViewById<TextView>(R.id.row_label).text = l
                    r.findViewById<TextView>(R.id.row_value).text = v
                    col.addView(r)
                }
                write(col, "coordinate_detail_rows", tag, 411)
            }

            screen("model_list") {
                // One card per row (the real grid sizes its span count to the screen; two fixed 185 dp cards plus
                // margins do not fit side by side in this 411 dp test frame).
                val row = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8)) }
                for (n in listOf("Bridge pier north abutment", "Hydrant")) {
                    val card = LayoutInflater.from(c).inflate(R.layout.item_model, row, false)
                    card.findViewById<TextView>(R.id.text_model_name).text = n
                    card.findViewById<View>(R.id.layout_badges).visibility = View.VISIBLE
                    listOf(R.id.text_badge_readiness, R.id.text_badge_linked, R.id.text_badge_geo).forEach { card.findViewById<View>(it).visibility = View.VISIBLE }
                    row.addView(card)
                }
                write(row, "model_list", tag, 411)
            }

            screen("model_viewer") {
                val page = inflate(c, R.layout.activity_model_viewer)
                page.findViewById<TextView>(R.id.text_model_name)?.text = "Bridge pier"
                write(page, "model_viewer", tag, 411, heightDp = 720)
            }

            screen("dev_raw_nmea") { write(inflate(c, R.layout.dev_page_raw_nmea), "dev_raw_nmea", tag, 411) }

            screen("dev_gnss_charts") {
                val sats = listOf(
                    SkyGeometry(3, Constellation.GPS, 45.0, 60.0, 44.0, true), SkyGeometry(7, Constellation.GPS, 130.0, 35.0, 32.0, true),
                    SkyGeometry(12, Constellation.GPS, 220.0, 15.0, 24.0, true), SkyGeometry(19, Constellation.GPS, 300.0, 70.0, 40.0, false),
                    SkyGeometry(71, Constellation.GLONASS, 80.0, 25.0, 38.0, true), SkyGeometry(5, Constellation.GALILEO, 170.0, 50.0, 41.0, true),
                    SkyGeometry(21, Constellation.BEIDOU, 260.0, 40.0, 33.0, true), SkyGeometry(193, Constellation.QZSS, 330.0, 20.0, 29.0, false),
                    SkyGeometry(131, Constellation.SBAS, 200.0, 30.0, 36.0, false),
                )
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                col.addView(SkyplotView(c).apply { setGeometry(sats) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 420)))
                col.addView(SatelliteSignalChartView(c).apply { setConstellationFilter(Constellation.GPS); setGeometry(sats) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 160)))
                col.addView(SatelliteSignalChartView(c).apply { setGeometry(sats) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 360)))
                write(col, "dev_gnss_charts", tag, 411)
            }

            screen("capture_dialog") {
                val page = inflate(c, R.layout.dialog_capture_coordinate)
                page.findViewById<View>(R.id.section_external)?.visibility = View.VISIBLE
                write(page, "capture_dialog", tag, 360)
            }

            screen("pin_sheet") {
                val page = inflate(c, R.layout.bottom_sheet_pin_inspect)
                page.findViewById<TextView>(R.id.pinName).text = "Control point CP-12"
                page.findViewById<TextView>(R.id.pinLatLon).text = "41.2453871,  -75.8812734"
                page.findViewById<TextView>(R.id.pinAlt).text = "Height:  312.452 m (ellipsoidal)"
                page.findViewById<TextView>(R.id.pinAccuracy).text = "H. Accuracy:  ±0.01 m"
                page.findViewById<TextView>(R.id.pinRtk).apply { text = "RTK:  FIX"; visibility = View.VISIBLE }
                page.findViewById<TextView>(R.id.pinProvider).text = "Source:  RS4 Pro"
                write(page, "pin_sheet", tag, 411)
            }

            screen("ar_models_sheet") { write(inflate(c, R.layout.sheet_ar_models), "ar_models_sheet", tag, 411, heightDp = 560) }

            screen("ar_controls") {
                val page = inflate(c, R.layout.fragment_open_in_ar)
                listOf(R.id.arFloatingToolbar, R.id.chipArQuality).forEach { page.findViewById<View>(it)?.visibility = View.VISIBLE }
                page.findViewById<TextView>(R.id.chipArGnssStatus)?.text = "RS4 Pro · RTK Fix · H ±0.01 m"
                page.findViewById<TextView>(R.id.chipArQuality)?.text = "AR quality: High · H ±0.8 m · heading ±2.1°"
                write(page, "ar_controls", tag, 411, heightDp = 891)
            }

            screen("map_controls") {
                val page = inflate(c, R.layout.fragment_render_map)
                listOf(R.id.layoutMapTools, R.id.stakeout_panel).forEach { page.findViewById<View>(it)?.visibility = View.VISIBLE }
                write(page, "map_controls", tag, 411, heightDp = 891)
            }
        }
        RuntimeEnvironment.setFontScale(1.0f)
    }

    private fun report(theme: String) {
        val f = File(outDir, "findings_$theme.txt")
        f.writeText(findings.joinToString("\n", postfix = "\n"))
        println("font-scale findings ($theme): ${findings.size} -> ${f.absolutePath}")
        findings.forEach { println("font-scale[$theme] $it") }
        val blocking = findings.filter { it.kind != "info" }
        assertTrue("Font-scale problems ($theme):\n" + blocking.joinToString("\n"), blocking.isEmpty())
    }

    @Test fun `render light`() { renderAll("light"); report("light") }

    @Test fun `render dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        renderAll("dark"); report("dark")
    }
}
