package app.surrealar.ui.toolbar

import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import app.surrealar.R
import app.surrealar.gnss.status.GnssStatusInterpreter

/**
 * View holder for one toolbar status "token" (label + value, optional icon/separator).
 * Owned and constructed by the host Activity from its inflated toolbar layout.
 */
data class ToolbarTokens(
    val root: View,
    val label: TextView,
    val value: TextView,
    val icon: ImageView,
    val separator: TextView? = null
)

/**
 * What the toolbar's taps do (the host decides; the renderer only wires them).
 *
 * - [onOpenReport]: everywhere on the toolbar (source, solution, accuracy, warning, battery, height, chevron and the
 *   empty space) — opens the GNSS Status report.
 * - [onCoordTap]: the coordinates token. The host toggles lat/lon ↔ grid when a grid system is configured (the
 *   long-standing behaviour), otherwise it opens the report too.
 * - [onCoordLongPress]: copy the shown position.
 */
data class ToolbarActions(
    val onOpenReport: () -> Unit,
    val onCoordTap: () -> Unit,
    val onCoordLongPress: () -> Unit,
)

/**
 * Tap rules (pure, tested): the coordinates keep the long-standing lat/lon ↔ grid toggle when a grid system is
 * configured; with nothing to toggle they open the GNSS Status report like the rest of the toolbar. The report also
 * has a labelled "Show lat/lon / grid in toolbar" button for the same toggle.
 */
object ToolbarTapRules {
    fun coordTapToggles(state: GnssToolbarState?): Boolean = state?.coordToggleAvailable == true
}

/**
 * Narrow-width priority for the compact toolbar (pure, tested). When the visible tokens do not fit on one line the
 * renderer applies these steps IN ORDER until they fit. Never dropped: the solution, horizontal accuracy, the active
 * warning (critical) and the chevron. Height references are never abbreviated (a height without its reference is
 * ambiguous): the elevation is shown with its full label or not at all.
 */
object ToolbarPriority {
    enum class Step {
        /** Hide the redundant "SRC" / "SOL" prefixes. */
        HIDE_PREFIX_LABELS,
        /** Warning text → its short form ("Corrections stale" → "Corr. stale"); the warning itself stays. */
        SHORT_WARNING,
        /** Drop the low-battery token (the report still shows the battery). */
        HIDE_BATTERY,
        /** Drop the elevation (value + height reference) together. */
        HIDE_ELEVATION,
        /** Drop the coordinates. */
        HIDE_COORDINATES,
        /** Drop the source name (the report and the Home card name it). */
        HIDE_SOURCE,
    }

    val ORDER: List<Step> = Step.entries

    /** Tokens no step may hide. */
    val NEVER_DROPPED = setOf("fix", "acc", "warn", "chevron")
}

/**
 * Receiver battery in the compact toolbar: shown only when low, from the existing battery graphic's thresholds (the
 * fill turns red at ≤ 15 %, and below 20 % charging keeps the warning colour). Normal levels and charging state are
 * in the GNSS Status report.
 */
object ToolbarBatteryPolicy {
    const val LOW_PERCENT = 20
    fun showInCompact(percent: Int?): Boolean = percent != null && percent in 0..LOW_PERCENT
}

/**
 * The only code that touches the toolbar token views. It applies a [GnssToolbarState] (and the battery reading) and
 * makes no GNSS decisions — those live in [GnssToolbarStateMapper] and the shared
 * [app.surrealar.gnss.status.GnssStatusInterpreter].
 *
 * Layout (location_status_bar.xml): LEFT group anchored left — source, solution, horizontal accuracy, warning, low
 * battery; RIGHT group anchored right — coordinates (LL/NE), elevation (height reference + value), chevron. One line
 * always; [ToolbarPriority] decides what goes at narrow widths.
 */
class GnssToolbarRenderer(
    private val source: ToolbarTokens,
    private val fix: ToolbarTokens,
    private val acc: ToolbarTokens,
    private val warn: ToolbarTokens,
    private val coord: ToolbarTokens,
    private val alt: ToolbarTokens,
    private val batt: ToolbarTokens,
    private val chevron: View,
    private val colorFor: (GnssStatusLevel) -> Int,
    private val onBatteryVisibilityChanged: (Boolean) -> Unit,
    /** The strip itself: its width decides which tokens fit on one line. Null = no width fitting. */
    private val strip: View? = null,
) {
    private var lastState: GnssToolbarState? = null
    private var lastStripWidth = 0
    private var batteryLayer: LayerDrawable? = null
    private var batteryFillClip: ClipDrawable? = null
    private var portrait = false
    private var coordToggleLabelled: Boolean? = null
    /** Last warning text set (only a real change touches the live-region view, so TalkBack is not spammed). */
    private var shownWarning: GnssStatusInterpreter.Warning? = null
    private var shownWarningShort = false
    /** The warning uses its short text at this width (set by the fit, cleared when the width changes). */
    private var shortWarning = false

    init {
        setUpStaticViews()
        // Stable widths: each value view is at least as wide as its typical longest value (tabular
        // digits are on in item_token), so ordinary value changes never change a token's width.
        minWidthFor(fix.value, "Single")
        minWidthFor(acc.value, "~±99.99 m")
        // Re-fit when the available width changes (rotation, split screen).
        strip?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width != lastStripWidth) {
                lastStripWidth = v.width; shortWarning = false
                lastState?.let { v.post { render(it) } }
            }
        }
    }

    /** Labels, icons and separators that never change. */
    private fun setUpStaticViews() {
        val ctx = source.root.context
        val dp = ctx.resources.displayMetrics.density
        source.label.setText(R.string.status_token_src)
        fix.label.setText(R.string.status_token_fix)
        acc.label.setText(R.string.status_token_hacc)
        coord.label.setText(R.string.status_token_ll)
        alt.label.setText(R.string.status_token_alt)

        // Warning token: icon + text on a filled pill (colour + icon + words: never colour alone).
        warn.label.isVisible = false
        warn.icon.setImageResource(R.drawable.ic_readiness_warn)
        warn.icon.isVisible = true
        warn.value.setPadding(0, 0, 0, 0)
        (warn.value.layoutParams as? LinearLayout.LayoutParams)?.let { it.marginStart = (2 * dp).toInt(); warn.value.layoutParams = it }
        warn.root.minimumHeight = (48 * dp).toInt()
        warn.root.isVisible = false
        ViewCompat.setAccessibilityLiveRegion(warn.root, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)

        batt.label.text = ""
        batt.icon.setImageResource(R.drawable.battery_level)
        androidx.core.widget.ImageViewCompat.setImageTintList(batt.icon, null)
        batt.icon.scaleType = ImageView.ScaleType.FIT_XY
        batt.icon.adjustViewBounds = false
        batt.icon.minimumWidth = (32 * dp).toInt()
        batt.icon.setPadding(0, 0, 0, 0)
        batt.root.minimumWidth = (48 * dp).toInt()
        batt.icon.isVisible = true
        // Battery graphic to the right of the percentage.
        (batt.root as? LinearLayout)?.let { container ->
            container.removeView(batt.icon)
            container.addView(batt.icon, container.indexOfChild(batt.label) + 1)
            (batt.icon.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = (32 * dp).toInt(); lp.height = (12 * dp).toInt()
                lp.setMargins((10 * dp).toInt(), 0, 0, 0)
                batt.icon.layoutParams = lp
            }
        }
        batt.root.isVisible = false

        acc.separator?.isVisible = false
        warn.separator?.isVisible = false
        alt.separator?.isVisible = false
        batt.separator?.isVisible = false
        chevron.contentDescription = ctx.getString(R.string.gnss_toolbar_open_report_cd)
    }

    /** Wires taps (report / coordinate toggle) and long-press help. */
    fun bindActions(actions: ToolbarActions) {
        val openLabel = source.root.context.getString(R.string.gnss_toolbar_open_report_action)
        for (v in listOf(source.root, fix.root, acc.root, warn.root, alt.root, batt.root, chevron)) {
            v.setOnClickListener { actions.onOpenReport() }
            clickLabel(v, openLabel)
        }
        strip?.setOnClickListener { actions.onOpenReport() }
        coord.root.setOnClickListener { actions.onCoordTap() }
        coord.root.setOnLongClickListener { actions.onCoordLongPress(); true }
        TooltipCompat.setTooltipText(source.root, ToolbarHelp.SOURCE)
        TooltipCompat.setTooltipText(fix.root, ToolbarHelp.SOL)
        TooltipCompat.setTooltipText(acc.root, ToolbarHelp.ACC)
        TooltipCompat.setTooltipText(warn.root, ToolbarHelp.WARNING)
        TooltipCompat.setTooltipText(alt.root, ToolbarHelp.ALT)
        TooltipCompat.setTooltipText(batt.root, ToolbarHelp.BATTERY)
        TooltipCompat.setTooltipText(chevron, ToolbarHelp.REPORT)
    }

    /** TalkBack's "double-tap to …" wording for a token's click. */
    private fun clickLabel(v: View, label: String) {
        ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK, label))
            }
        })
    }

    /** Portrait: the "SRC" label is hidden to save width (the source value stays). */
    fun setPortrait(portrait: Boolean) {
        this.portrait = portrait
        source.label.isVisible = !portrait
        lastState?.let { render(it) }
    }

    fun render(state: GnssToolbarState) {
        lastState = state
        // Source label (+ replay icon while an NMEA log replays; no strip colour change)
        source.root.isVisible = true
        source.label.isVisible = !portrait
        fix.label.isVisible = true
        source.value.text = state.sourceText
        if (state.isReplay) source.icon.setImageResource(R.drawable.ic_replay_16)
        source.icon.isVisible = state.isReplay
        source.separator?.isVisible = true

        // Fix / SOL (always shown)
        fix.root.isVisible = true
        fix.value.text = state.fixText
        fix.value.setTextColor(colorFor(state.fixLevel))

        // Horizontal accuracy only (vertical accuracy is in the report)
        val accText = state.compactAccuracyText
        if (accText != null) {
            acc.value.text = accText
            acc.value.setTextColor(colorFor(state.accuracyLevel))
            acc.root.isVisible = true
        } else acc.root.isVisible = false
        fix.separator?.isVisible = acc.root.isVisible

        renderWarning(state.primaryWarning, short = shortWarning)

        // Battery: the host decides whether a receiver battery may show and calls renderBattery with the reading.
        onBatteryVisibilityChanged(state.batteryVisible)

        // Coordinates ("LL" lat/lon or "NE" grid, per Settings → Coordinates)
        coord.label.text = state.coordLabel
        coord.value.text = state.latLonText
        ratchetWidth(coord.value, state.latLonText, key = "coord:${state.coordLabel}")
        // A lost link keeps the last position on screen, greyed (alpha, not a colour change) and the solution says
        // "(lost Ns)"; the spoken text says "last known … not for capture".
        coord.value.alpha = if (state.isStale) STALE_ALPHA else 1f
        coord.root.isVisible = true

        // Elevation (always visible with a placeholder); label names the height reference
        alt.label.text = state.altitudeLabel
        alt.value.text = state.altitudeText
        ratchetWidth(alt.value, state.altitudeText, key = "alt:${state.altitudeLabel}:${state.altitudeText.substringAfterLast(' ')}")
        alt.value.alpha = if (state.isStale) STALE_ALPHA else 1f
        alt.root.isVisible = true
        chevron.isVisible = true

        // Spoken descriptions (TalkBack reads each token root as one item)
        source.root.contentDescription = state.sourceDescription
        fix.root.contentDescription = state.fixDescription
        acc.root.contentDescription = state.accuracyHDescription ?: state.accuracyDescription
        coord.root.contentDescription = state.coordDescription
        alt.root.contentDescription = state.altitudeDescription
        if (coordToggleLabelled != state.coordToggleAvailable) {
            coordToggleLabelled = state.coordToggleAvailable
            clickLabel(coord.root, coord.root.context.getString(
                if (state.coordToggleAvailable) R.string.gnss_toolbar_toggle_coords_action else R.string.gnss_toolbar_open_report_action))
        }

        fitToWidth()
    }

    /**
     * The warning token. Its text (and spoken description) changes only when the warning itself changes, so the
     * polite live region announces a warning when it appears or escalates — never on every 250 ms render.
     */
    private fun renderWarning(w: GnssStatusInterpreter.Warning?, short: Boolean) {
        if (w == null) {
            if (warn.root.isVisible) warn.root.isVisible = false
            shownWarning = null
            return
        }
        if (w != shownWarning || short != shownWarningShort) {
            warn.value.text = if (short) w.shortText else w.text
            val critical = w.severity == GnssStatusInterpreter.Severity.WARNING
            val ctx = warn.root.context
            val bg = ctx.getColor(if (critical) R.color.gnss_toolbar_warning_critical_bg else R.color.gnss_toolbar_warning_caution_bg)
            val fg = ctx.getColor(if (critical) R.color.gnss_toolbar_warning_critical_fg else R.color.gnss_toolbar_warning_caution_fg)
            warn.value.setTextColor(fg)
            androidx.core.widget.ImageViewCompat.setImageTintList(warn.icon, ColorStateList.valueOf(fg))
            warn.value.background = null
            val dp = ctx.resources.displayMetrics.density
            (warn.root as? LinearLayout)?.let { row ->
                // The pill is the token's own background, inset so the 48 dp touch target stays.
                val pill = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(bg) }
                // Inset 6 dp on the left so the pill does not touch the accuracy value.
                row.background = android.graphics.drawable.InsetDrawable(pill, (6 * dp).toInt(), (12 * dp).toInt(), 0, (12 * dp).toInt())
                row.setPadding((14 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            }
            if (w != shownWarning) warn.root.contentDescription = lastState?.warningDescription ?: "Warning: ${w.text}"
            shownWarning = w
            shownWarningShort = short
        } else {
            // Same warning: refresh the spoken list (secondary warnings) without retriggering the live region.
            val cd = lastState?.warningDescription
            if (cd != null && warn.root.contentDescription?.toString() != cd) warn.root.contentDescription = cd
        }
        if (!warn.root.isVisible) warn.root.isVisible = true
    }

    /**
     * The battery token (same drawing and colours as before), shown in the compact toolbar only when low
     * ([ToolbarBatteryPolicy]). Hidden when [visible] is false or there's no reading; [textOnly] (narrow / portrait)
     * shows the percentage without the graphic.
     */
    fun renderBattery(visible: Boolean, percent: Int?, charging: Boolean?, textOnly: Boolean) {
        batt.root.contentDescription = "Low " + GnssToolbarStateMapper.batteryDescription(percent, charging).replaceFirstChar { it.lowercase() }
        if (!visible || !ToolbarBatteryPolicy.showInCompact(percent) || percent == null) {
            batt.root.isVisible = false
            return
        }
        val p = percent.coerceIn(0, 100)
        batt.root.isVisible = true
        batt.value.text = "$p%"
        batt.icon.isVisible = !textOnly
        if (!textOnly) drawBattery(p, charging)
        fitToWidth()
    }

    private fun drawBattery(percentage: Int, charging: Boolean?) {
        if (batteryLayer == null) {
            val base = batt.icon.drawable ?: return
            batteryLayer = (base.mutate() as? LayerDrawable)
            batt.icon.setImageDrawable(batteryLayer)
            batteryFillClip = (batteryLayer?.findDrawableByLayerId(R.id.battery_fill) as? ClipDrawable)
                ?: run {
                    var found: ClipDrawable? = null
                    try {
                        val count = batteryLayer?.numberOfLayers ?: 0
                        for (i in 0 until count) {
                            val d = batteryLayer?.getDrawable(i)
                            if (d is ClipDrawable) { found = d; break }
                        }
                    } catch (_: Exception) {}
                    found
                }
        }

        batteryFillClip?.level = percentage * 100

        // Charging turns the fill green, except below 20 %: a critical level keeps its warning
        // color and only gains the bolt, so plugging in never hides a nearly-dead battery.
        val fillColor = when {
            charging == true && percentage >= 20 -> 0xFF4CAF50.toInt()
            percentage <= 15 -> 0xFFF44336.toInt()
            percentage <= 30 -> 0xFFFFC107.toInt()
            else -> 0xFF4CAF50.toInt()
        }

        try {
            batteryLayer?.findDrawableByLayerId(R.id.battery_bolt)?.alpha = if (charging == true) 0xFF else 0x00
        } catch (_: Exception) {}

        val clip = batteryFillClip
        val inner = try { clip?.drawable } catch (_: Exception) { null }
        var tinted = false
        try {
            if (inner is GradientDrawable) {
                inner.setColor(fillColor); tinted = true
            }
        } catch (_: Exception) {}
        try {
            if (inner != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTint(inner, fillColor); tinted = true
            }
        } catch (_: Exception) {}
        if (!tinted) {
            try { clip?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it, fillColor) } } catch (_: Exception) {}
        }

        try {
            batteryLayer?.findDrawableByLayerId(R.id.battery_bg)?.alpha = if (percentage <= 15) 0x66 else 0x22
        } catch (_: Exception) {}

        batteryLayer?.invalidateSelf()
        batt.icon.invalidate()
    }

    // ── Stable widths ──────────────────────────────────────────────────────────

    private fun minWidthFor(view: TextView, template: String) {
        view.minWidth = maxOf(view.minWidth, kotlin.math.ceil(view.paint.measureText(template)).toInt())
    }

    private val ratchetKeys = mutableMapOf<TextView, String>()

    /**
     * Coordinates and height: the minimum width follows the current format (every digit measured as
     * '0'; all digits are the same width with tabular figures) and only grows, so a value that gains
     * or loses a digit, or a sign, doesn't make the strip jump. Reset when the format changes
     * (LL ↔ NE, height reference or unit).
     */
    private fun ratchetWidth(view: TextView, text: String, key: String) {
        if (ratchetKeys[view] != key) { ratchetKeys[view] = key; view.minWidth = 0 }
        if (text == "--") return
        val template = text.replace(Regex("[0-9]"), "0")
        minWidthFor(view, if (text.contains('-')) template else "-$template")
    }

    // ── One line, priority drop ───────────────────────────────────────────────

    /** Never wrap onto a second line: apply [ToolbarPriority.ORDER] until the visible tokens fit. */
    private fun fitToWidth() {
        val s = strip ?: return
        val available = s.width - s.paddingStart - s.paddingEnd
        if (available <= 0) return                    // not laid out yet; the layout listener re-renders
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun width(v: View): Int = if (!v.isVisible) 0 else { v.measure(unspecified, unspecified); v.measuredWidth }
        val all = listOf(source.root, fix.root, acc.root, warn.root, batt.root, coord.root, alt.root, chevron)
        val gap = (GROUP_GAP_DP * s.resources.displayMetrics.density).toInt()
        fun total() = all.sumOf { width(it) } + gap
        coord.separator?.isVisible = alt.root.isVisible
        for (step in ToolbarPriority.ORDER) {
            if (total() <= available) break
            when (step) {
                ToolbarPriority.Step.HIDE_PREFIX_LABELS -> { source.label.isVisible = false; fix.label.isVisible = false }
                // Sticky until the width changes, so the warning text (a live region) does not flip every render.
                ToolbarPriority.Step.SHORT_WARNING -> lastState?.primaryWarning?.let { shortWarning = true; renderWarning(it, short = true) }
                ToolbarPriority.Step.HIDE_BATTERY -> batt.root.isVisible = false
                ToolbarPriority.Step.HIDE_ELEVATION -> { alt.root.isVisible = false; coord.separator?.isVisible = false }
                ToolbarPriority.Step.HIDE_COORDINATES -> coord.root.isVisible = false
                ToolbarPriority.Step.HIDE_SOURCE -> source.root.isVisible = false
            }
        }
        coord.separator?.isVisible = coord.root.isVisible && alt.root.isVisible
    }

    /** Test/preview hook: which tokens are visible after the last fit, by name. */
    internal fun visibleTokens(): Set<String> = buildSet {
        if (source.root.isVisible) add("src"); if (fix.root.isVisible) add("fix"); if (acc.root.isVisible) add("acc")
        if (warn.root.isVisible) add("warn"); if (batt.root.isVisible) add("batt"); if (coord.root.isVisible) add("coord")
        if (alt.root.isVisible) add("alt"); if (chevron.isVisible) add("chevron")
    }

    private companion object {
        const val STALE_ALPHA = 0.5f
        /** Minimum flexible space kept between the left and right groups. */
        const val GROUP_GAP_DP = 8
    }
}
