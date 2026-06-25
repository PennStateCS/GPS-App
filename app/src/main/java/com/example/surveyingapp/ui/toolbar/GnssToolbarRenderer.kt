package com.example.surveyingapp.ui.toolbar

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
    private val coord: ToolbarTokens,
    private val alt: ToolbarTokens,
    private val batt: ToolbarTokens,
    private val colorFor: (GnssStatusLevel) -> Int,
    private val onBatteryVisibilityChanged: (Boolean) -> Unit
) {

    fun render(state: GnssToolbarState) {
        // Source label
        source.value.text = state.sourceText
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

        // Separator between SATS and ACC: visible only when both are shown
        sats.separator?.isVisible = satsVisible && accVisible
        acc.separator?.isVisible = false

        // Battery
        batt.icon.contentDescription = if (state.isExternal) "receiver battery" else "device battery"
        onBatteryVisibilityChanged(state.batteryVisible)

        // Coordinates
        coord.value.text = state.latLonText
        coord.root.isVisible = true

        // Altitude (always visible with a placeholder)
        alt.value.text = state.altitudeText
        alt.root.isVisible = true
    }
}
