package app.surrealar.ui.toolbar

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible

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
 * Applies a [GnssToolbarState] to the toolbar status tokens. **Pure view updates only** — it does
 * not collect flows, access repositories, or make GNSS decisions (those live in
 * [GnssToolbarStateMapper]).
 *
 * Extracted from `MainActivity` so the toolbar rendering is isolated from lifecycle/collection.
 * The Activity still owns:
 *  - flow collection and calling [render],
 *  - status-level → color resolution (passed as [colorFor], so this class needs no Context),
 *  - battery polling lifecycle (invoked via [onBatteryVisibilityChanged]; the Job stays in the
 *    Activity).
 *
 * Wording/visibility rules are preserved exactly from the previous in-Activity implementation.
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

    init {
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

    fun render(state: GnssToolbarState) {
        lastState = state
        // Source label (+ replay icon while an NMEA log replays; no strip colour change)
        source.value.text = state.sourceText
        if (state.isReplay) source.icon.setImageResource(app.surrealar.R.drawable.ic_replay_16)
        source.icon.isVisible = state.isReplay
        source.separator?.isVisible = true

        // Fix / SOL (always shown)
        fix.root.isVisible = true
        fix.label.text = "SOL"
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

        // Battery (its spoken description is set with the battery reading, see renderBattery)
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
