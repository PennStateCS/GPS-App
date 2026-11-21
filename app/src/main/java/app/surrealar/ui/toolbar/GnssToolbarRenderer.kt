package app.surrealar.ui.toolbar

import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import app.surrealar.R

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

/** What the toolbar's taps and long-presses do (the host decides; the renderer only wires them). */
data class ToolbarActions(
    val onStatus: () -> Unit,
    val onBattery: () -> Unit,
    val onCoordTap: () -> Unit,
    val onCoordLongPress: () -> Unit,
)

/**
 * The only code that touches the toolbar token views. It applies a [GnssToolbarState] (and the
 * battery reading) and does no GNSS decisions — those live in [GnssToolbarStateMapper].
 *
 * The Activity owns flow collection (calling [render] / [renderBattery]), status-level → colour
 * resolution ([colorFor], so this class needs no colour lookups of its own), and whether the battery
 * should show at all ([onBatteryVisibilityChanged] → [renderBattery]).
 */
class GnssToolbarRenderer(
    private val source: ToolbarTokens,
    private val fix: ToolbarTokens,
    private val sats: ToolbarTokens,
    private val acc: ToolbarTokens,
    private val age: ToolbarTokens,
    private val coord: ToolbarTokens,
    private val alt: ToolbarTokens,
    private val batt: ToolbarTokens,
    private val colorFor: (GnssStatusLevel) -> Int,
    private val onBatteryVisibilityChanged: (Boolean) -> Unit,
    /** The strip itself: its width decides which tokens fit on one line. Null = no width fitting. */
    private val strip: View? = null,
) {
    private var lastState: GnssToolbarState? = null
    private var lastStripWidth = 0
    private var batteryLayer: LayerDrawable? = null
    private var batteryFillClip: ClipDrawable? = null

    init {
        setUpStaticViews()
        // Stable widths: each value view is at least as wide as its typical longest value (tabular
        // digits are on in item_token), so ordinary value changes never change a token's width.
        minWidthFor(fix.value, "Single")
        minWidthFor(sats.value, "99/99")
        minWidthFor(acc.value, "~±99.99 m")
        minWidthFor(age.value, "99.9s")
        // Re-fit when the available width changes (rotation, split screen).
        strip?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width != lastStripWidth) { lastStripWidth = v.width; lastState?.let { v.post { render(it) } } }
        }
    }

    /** Labels, battery icon and separators that never change (moved here from MainActivity). */
    private fun setUpStaticViews() {
        val ctx = source.root.context
        val dp = ctx.resources.displayMetrics.density
        source.label.setText(R.string.status_token_src)
        fix.label.setText(R.string.status_token_fix)
        sats.label.setText(R.string.status_token_sats)
        acc.label.setText(R.string.status_token_hacc)
        age.label.setText(R.string.status_token_age)
        coord.label.setText(R.string.status_token_ll)
        alt.label.setText(R.string.status_token_alt)
        age.root.isVisible = false

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

        sats.separator?.isVisible = false
        acc.separator?.isVisible = false
        alt.separator?.isVisible = false
        batt.separator?.isVisible = false
    }

    /** Wires taps and long-press help. SOL/SATS → status, battery → receiver, LL/NE → toggle / copy. */
    fun bindActions(actions: ToolbarActions) {
        fix.root.setOnClickListener { actions.onStatus() }
        sats.root.setOnClickListener { actions.onStatus() }
        batt.root.setOnClickListener { actions.onBattery() }
        coord.root.setOnClickListener { actions.onCoordTap() }
        coord.root.setOnLongClickListener { actions.onCoordLongPress(); true }
        TooltipCompat.setTooltipText(source.root, ToolbarHelp.SOURCE)
        TooltipCompat.setTooltipText(fix.root, ToolbarHelp.SOL)
        TooltipCompat.setTooltipText(acc.root, ToolbarHelp.ACC)
        TooltipCompat.setTooltipText(age.root, ToolbarHelp.AGE)
        TooltipCompat.setTooltipText(sats.root, ToolbarHelp.SATS)
        TooltipCompat.setTooltipText(alt.root, ToolbarHelp.ALT)
        TooltipCompat.setTooltipText(batt.root, ToolbarHelp.BATTERY)
    }

    /** Portrait: the "SRC" label is hidden to save width (the source value stays). */
    fun setPortrait(portrait: Boolean) {
        source.label.isVisible = !portrait
    }

    fun render(state: GnssToolbarState) {
        lastState = state
        // Source label (+ replay icon while an NMEA log replays; no strip colour change)
        source.value.text = state.sourceText
        if (state.isReplay) source.icon.setImageResource(R.drawable.ic_replay_16)
        source.icon.isVisible = state.isReplay
        source.separator?.isVisible = true

        // Fix / SOL (always shown)
        fix.root.isVisible = true
        fix.value.text = state.fixText
        fix.value.setTextColor(colorFor(state.fixLevel))

        // Satellites
        val satsVisible = state.satelliteText != null
        if (satsVisible) {
            sats.value.text = state.satelliteText
            sats.root.isVisible = true
        } else {
            sats.root.isVisible = false
        }

        // Accuracy
        val accVisible = state.accuracyText != null
        if (accVisible) {
            acc.value.text = state.accuracyText
            acc.value.setTextColor(colorFor(state.accuracyLevel))
            acc.root.isVisible = true
        } else {
            acc.root.isVisible = false
        }

        // Correction age (external only)
        val ageVisible = state.correctionAgeText != null
        if (ageVisible) age.value.text = state.correctionAgeText
        age.root.isVisible = ageVisible

        // Separators between visible left-group tokens (SATS | ACC | AGE)
        sats.separator?.isVisible = satsVisible && (accVisible || ageVisible)
        acc.separator?.isVisible = accVisible && ageVisible
        age.separator?.isVisible = false

        // Battery: the host decides whether it shows and calls renderBattery with the reading.
        onBatteryVisibilityChanged(state.batteryVisible)

        // Coordinates ("LL" lat/lon or "NE" grid, per Settings → Coordinates)
        coord.label.text = state.coordLabel
        coord.value.text = state.latLonText
        ratchetWidth(coord.value, state.latLonText, key = "coord:${state.coordLabel}")
        // A lost link keeps the last position on screen, greyed (alpha, not a colour change).
        coord.value.alpha = if (state.isStale) STALE_ALPHA else 1f
        coord.root.isVisible = true

        // Altitude (always visible with a placeholder); label names the height reference
        alt.label.text = state.altitudeLabel
        alt.value.text = state.altitudeText
        ratchetWidth(alt.value, state.altitudeText, key = "alt:${state.altitudeLabel}:${state.altitudeText.substringAfterLast(' ')}")
        alt.value.alpha = if (state.isStale) STALE_ALPHA else 1f
        alt.root.isVisible = true

        // Spoken descriptions (TalkBack reads each token root as one item)
        source.root.contentDescription = state.sourceDescription
        fix.root.contentDescription = state.fixDescription
        sats.root.contentDescription = state.satsDescription
        acc.root.contentDescription = state.accuracyDescription
        age.root.contentDescription = state.ageDescription
        coord.root.contentDescription = state.coordDescription
        alt.root.contentDescription = state.altitudeDescription

        fitToWidth()
    }

    /**
     * The battery token (moved from MainActivity, same drawing and colours). Hidden when [visible] is
     * false or there's no reading; [textOnly] (narrow / portrait) shows the percentage without the graphic.
     */
    fun renderBattery(visible: Boolean, percent: Int?, charging: Boolean?, textOnly: Boolean) {
        batt.root.contentDescription = GnssToolbarStateMapper.batteryDescription(percent, charging)
        if (!visible || percent == null) {
            batt.root.isVisible = false
            alt.separator?.isVisible = false
            return
        }
        val p = percent.coerceIn(0, 100)
        batt.root.isVisible = true
        alt.separator?.isVisible = true
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
            if (inner is android.graphics.drawable.GradientDrawable) {
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

    /**
     * Never wrap onto a second line: when the visible tokens don't fit the strip, hide the lowest
     * priority first — height, coordinates, battery, SATS, AGE, then ACC. SOL and the source are kept.
     */
    private fun fitToWidth() {
        val s = strip ?: return
        val available = s.width - s.paddingStart - s.paddingEnd
        if (available <= 0) return                    // not laid out yet; the layout listener re-renders
        val keep = listOf(source, fix)
        val droppable = listOf(acc, age, sats, batt, coord, alt)   // highest priority first
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun width(t: ToolbarTokens): Int =
            if (!t.root.isVisible) 0 else { t.root.measure(unspecified, unspecified); t.root.measuredWidth }
        var total = (keep + droppable).sumOf { width(it) }
        for (t in droppable.asReversed()) {
            if (total <= available) break
            if (!t.root.isVisible) continue
            total -= width(t)
            t.root.isVisible = false
        }
    }

    private companion object { const val STALE_ALPHA = 0.5f }
}
