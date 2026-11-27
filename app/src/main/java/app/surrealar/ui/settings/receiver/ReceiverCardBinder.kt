package app.surrealar.ui.settings.receiver

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import app.surrealar.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors

/**
 * Binds a [ReceiverCardModel] to `view_receiver_card.xml`. Colours come from theme roles only (light/dark). Only end
 * states (Connected / In use / Failed / Unavailable) are announced for accessibility, once each: never the first bind,
 * Selected / Testing / Connecting / Reconnecting, nor a Reconnecting -> Connected flap back to the state last announced.
 */
class ReceiverCardBinder(
    private val root: View,
    /** Test seam: speaks an announcement (default: View.announceForAccessibility). */
    private val announce: (String) -> Unit = { root.announceForAccessibility(it) },
) {
    private val ctx = root.context
    val source: TextView = root.findViewById(R.id.text_receiver_source)
    val name: TextView = root.findViewById(R.id.text_receiver_name)
    val address: TextView = root.findViewById(R.id.text_receiver_address)
    val modelGroup: View = root.findViewById(R.id.group_receiver_model)
    val model: TextView = root.findViewById(R.id.text_receiver_model)
    val modelOrigin: TextView = root.findViewById(R.id.text_receiver_model_origin)
    val stateChip: Chip = root.findViewById(R.id.chip_receiver_state)
    val fixChip: Chip = root.findViewById(R.id.chip_receiver_fix)
    val satsChip: Chip = root.findViewById(R.id.chip_receiver_sats)
    val batteryChip: Chip = root.findViewById(R.id.chip_receiver_battery)
    val attempt: TextView = root.findViewById(R.id.text_receiver_attempt)
    val failure: TextView = root.findViewById(R.id.text_receiver_failure)
    val mismatchGroup: View = root.findViewById(R.id.group_receiver_mismatch)
    val mismatchText: TextView = root.findViewById(R.id.text_receiver_mismatch)
    val mismatchUse: MaterialButton = root.findViewById(R.id.btn_mismatch_use)
    val mismatchKeep: MaterialButton = root.findViewById(R.id.btn_mismatch_keep)
    val change: MaterialButton = root.findViewById(R.id.btn_change_receiver)
    val details: MaterialButton = root.findViewById(R.id.btn_receiver_details)
    val retry: MaterialButton = root.findViewById(R.id.btn_retry_receiver)

    private var bound = false
    /** The end state last announced (or shown on the first bind). */
    private var lastEndState: CardConnection? = null

    fun bind(m: ReceiverCardModel) {
        source.setText(if (m.external) R.string.receiver_source_external else R.string.receiver_source_tablet)
        // No name known (the host is the name): show host:port once instead of the host twice.
        val nameIsHost = m.target != null && m.receiverName == m.target.host
        name.text = when {
            !m.external -> ctx.getString(R.string.receiver_source_tablet_name)
            nameIsHost -> m.target.toString()
            m.receiverName != null -> m.receiverName
            else -> ctx.getString(R.string.receiver_none_saved)
        }
        // The saved address is shown on the user's own screen (never in copied summaries).
        val addr = m.target?.toString()?.takeIf { !nameIsHost }
        address.isVisible = !m.external || addr != null
        address.text = if (!m.external) ctx.getString(R.string.receiver_source_tablet_desc) else addr

        modelGroup.isVisible = m.modelLabel != null
        model.text = m.modelLabel
        val originText = m.modelOrigin?.let { modelOriginLabel(it) }
        modelOrigin.text = originText
        modelGroup.contentDescription = if (m.modelLabel != null && originText != null)
            ctx.getString(R.string.receiver_model_origin_cd, m.modelLabel, originText) else null

        // Connection state chip.
        val stateText = if (m.streaming) ctx.getString(R.string.receiver_state_streaming) else stateText(m.connection)
        stateChip.text = stateText
        stateChip.contentDescription = ctx.getString(R.string.receiver_state_cd, stateText)
        val (bg, fg) = when (m.connection) {
            CardConnection.Connected, CardConnection.DeviceGnss ->
                com.google.android.material.R.attr.colorPrimaryContainer to com.google.android.material.R.attr.colorOnPrimaryContainer
            CardConnection.Testing, CardConnection.Connecting, CardConnection.Reconnecting ->
                com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
            CardConnection.Selected ->
                com.google.android.material.R.attr.colorSurfaceVariant to com.google.android.material.R.attr.colorOnSurfaceVariant
            CardConnection.Unavailable, is CardConnection.Failed ->
                com.google.android.material.R.attr.colorErrorContainer to com.google.android.material.R.attr.colorOnErrorContainer
        }
        tint(stateChip, bg, fg)
        if (isEndState(m.connection)) {
            if (bound && shouldAnnounce(lastEndState, m.connection)) announce(ctx.getString(R.string.receiver_announce, stateText))
            lastEndState = m.connection
        }
        bound = true

        // Fix + satellites.
        val fixText = m.fix?.let { fixLabel(it) } ?: ctx.getString(R.string.receiver_fix_waiting)
        fixChip.text = fixText
        fixChip.contentDescription = ctx.getString(R.string.receiver_fix_cd, fixText)
        val (fbg, ffg) = when (m.fix) {
            // Semantic status roles, as everywhere else (docs/design-system.md): Fix = success, Float = warning,
            // DGPS = info. Colour mapping only; the CardFix classification is unchanged.
            CardFix.FIX -> R.attr.colorStatusSuccessContainer to R.attr.colorOnStatusSuccessContainer
            CardFix.FLOAT -> R.attr.colorStatusWarningContainer to R.attr.colorOnStatusWarningContainer
            CardFix.DGPS -> R.attr.colorStatusInfoContainer to R.attr.colorOnStatusInfoContainer
            else -> com.google.android.material.R.attr.colorSurfaceVariant to com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        tint(fixChip, fbg, ffg)
        val used = m.satsUsed
        val vis = m.satsVisible
        satsChip.isVisible = used != null && vis != null
        if (used != null && vis != null) {
            satsChip.text = ctx.getString(R.string.receiver_sats, used, vis)
            satsChip.contentDescription = ctx.getString(R.string.receiver_sats_cd, used, vis)
        }
        val pct = m.batteryPercent
        batteryChip.isVisible = pct != null
        if (pct != null) {
            batteryChip.text = ctx.getString(if (m.batteryCharging) R.string.receiver_battery_charging else R.string.receiver_battery, pct)
            batteryChip.contentDescription = batteryChip.text
            val low = pct < 20
            tint(batteryChip,
                if (low) com.google.android.material.R.attr.colorErrorContainer else com.google.android.material.R.attr.colorSurfaceVariant,
                if (low) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnSurfaceVariant)
        }

        // A test / switch of another receiver: its own line (the chips keep showing the active source).
        val line = m.attemptLine
        attempt.isVisible = line is CardAttempt.Testing || line is CardAttempt.Connecting
        when (line) {
            is CardAttempt.Testing -> attempt.text = ctx.getString(R.string.receiver_attempt_testing, line.target.toString())
            is CardAttempt.Connecting -> attempt.text = ctx.getString(R.string.receiver_attempt_connecting, line.target.toString())
            else -> Unit
        }
        // A failed switch to another receiver: inline note (the current source keeps running).
        val f = m.failedSwitch
        failure.isVisible = f != null
        if (f != null) failure.text = if (f.keepsCurrent) ctx.getString(R.string.receiver_switch_failed_still_using,
            f.target.toString(), ReceiverUi.shortReason(ctx, f.error), currentSourceName(m))
        else ctx.getString(R.string.receiver_switch_failed, f.target.toString(), f.error.message)
        if (m.connection is CardConnection.Failed) {
            failure.isVisible = true
            failure.text = m.connection.error.message
        }
        // The selection could not be saved: the active source works now but may not survive a restart.
        m.selectionSaveError?.let { err ->
            failure.isVisible = true
            failure.text = err
        }

        // Mismatch banner.
        val mm = m.mismatch
        mismatchGroup.isVisible = mm != null
        if (mm != null) {
            mismatchText.text = ctx.getString(R.string.receiver_mismatch, mm.reportedModel, mm.currentProfile.label)
            mismatchUse.text = ctx.getString(R.string.receiver_mismatch_use, mm.reportedProfile.shortLabel)
        }

        retry.isVisible = m.canRetry
        change.isEnabled = true
    }

    fun stateText(c: CardConnection): String = stateTextOf(ctx, c)

    /** "Still using ...": the receiver's name (or host:port), or tablet GNSS. */
    private fun currentSourceName(m: ReceiverCardModel): String = when {
        !m.external -> ctx.getString(R.string.receiver_source_tablet)
        m.receiverName != null && m.target != null && m.receiverName == m.target.host -> m.target.toString()
        m.receiverName != null -> m.receiverName
        else -> ctx.getString(R.string.receiver_source_external)
    }

    /**
     * The model line's tag: what kind of identity the label is. Only a model the connected receiver reported is
     * "Receiver-confirmed"; a profile is never presented as a verified identity.
     */
    fun modelOriginLabel(o: ValueOrigin): String = ctx.getString(when (o) {
        ValueOrigin.REPORTED -> R.string.receiver_model_confirmed
        ValueOrigin.SET_BY_USER -> R.string.receiver_model_profile_user
        ValueOrigin.DEFAULT -> R.string.receiver_model_profile_default
        ValueOrigin.EARLIER_REPORT -> R.string.receiver_model_previous
        ValueOrigin.UNKNOWN -> R.string.receiver_model_unknown
    })

    fun originLabel(o: ValueOrigin): String = ctx.getString(when (o) {
        ValueOrigin.REPORTED -> R.string.receiver_origin_reported
        ValueOrigin.SET_BY_USER -> R.string.receiver_origin_user
        ValueOrigin.DEFAULT -> R.string.receiver_origin_default
        ValueOrigin.EARLIER_REPORT -> R.string.receiver_origin_earlier
        ValueOrigin.UNKNOWN -> R.string.receiver_origin_unknown
    })

    private fun fixLabel(f: CardFix): String = ctx.getString(when (f) {
        CardFix.NO_FIX -> R.string.receiver_fix_none
        CardFix.SINGLE -> R.string.receiver_fix_single
        CardFix.DGPS -> R.string.receiver_fix_dgps
        CardFix.FLOAT -> R.string.receiver_fix_float
        CardFix.FIX -> R.string.receiver_fix_fix
        CardFix.DEAD_RECKONING -> R.string.receiver_fix_dr
    })

    private fun tint(chip: Chip, bgAttr: Int, fgAttr: Int) {
        chip.chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(chip, bgAttr))
        chip.setTextColor(MaterialColors.getColor(chip, fgAttr))
    }

    companion object {
        /** States the user waits for (announced); Testing / Connecting / Reconnecting are transient. */
        fun isEndState(c: CardConnection): Boolean = when (c) {
            CardConnection.Connected, CardConnection.DeviceGnss, CardConnection.Unavailable, is CardConnection.Failed -> true
            CardConnection.Selected, CardConnection.Testing, CardConnection.Connecting, CardConnection.Reconnecting -> false
        }

        /** Announce [current] only when it is an end state other than the one last announced. */
        fun shouldAnnounce(lastEnd: CardConnection?, current: CardConnection): Boolean =
            isEndState(current) && current != lastEnd

        fun stateTextOf(ctx: android.content.Context, c: CardConnection): String = when (c) {
            CardConnection.DeviceGnss -> ctx.getString(R.string.receiver_state_device)
            CardConnection.Testing -> ctx.getString(R.string.receiver_state_testing)
            CardConnection.Connecting -> ctx.getString(R.string.receiver_state_connecting)
            CardConnection.Connected -> ctx.getString(R.string.receiver_state_connected)
            CardConnection.Reconnecting -> ctx.getString(R.string.receiver_state_reconnecting)
            CardConnection.Unavailable -> ctx.getString(R.string.receiver_state_unavailable)
            CardConnection.Selected -> ctx.getString(R.string.receiver_state_selected)
            is CardConnection.Failed -> ctx.getString(R.string.receiver_state_failed, ReceiverUi.shortReason(ctx, c.error))
        }
    }
}
