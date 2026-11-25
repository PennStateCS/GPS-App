package app.surrealar.ui.settings.receiver

import android.content.Context
import app.surrealar.R
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.ReceiverSelectionState
import app.surrealar.settings.model.AntennaSetup
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.ProfileOrigin
import app.surrealar.ui.settings.AntennaHeightInput
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo

/** One row of the receiver details: label, value, where the value comes from, optional note. */
data class DetailRow(val label: Int, val value: String, val origin: ValueOrigin?, val note: String? = null)
data class DetailSection(val title: Int, val rows: List<DetailRow>)

/** What the details sheet reads. */
data class DetailsInputs(
    val state: ReceiverSelectionState,
    val deviceInfo: ReceiverDeviceInfo?,
    val battery: ReceiverBattery?,
    val corrections: ReceiverCorrections?,
    val capabilities: ReceiverCapabilities,
    val fix: Fix?,
    val coordinates: CoordinateSystemSettings,
    val receiverSettings: GnssReceiverSettings,
    val compensated: CompensatedOutputSetting,
    val nmeaLinesPerSecond: Double,
)

/**
 * Builds the receiver details sections. Every row is tagged "Reported by receiver" (what the receiver itself
 * reported), "Set by you" / "Default" (app settings) or "Unknown". Receiver reports are used only while the
 * receiver at the saved host is the active external source (the same rule as identification).
 */
object ReceiverDetailsPresenter {

    fun sections(ctx: Context, i: DetailsInputs): List<DetailSection> {
        val s = i.state
        val external = s.selectedSource == LocationSourceType.EXTERNAL
        val live = external && s.activeProvider == ProviderChoice.EXTERNAL_TCP
        val info = i.deviceInfo?.takeIf { live && s.identity != null }
        val unknown = ctx.getString(R.string.details_unknown)
        val notReported = ctx.getString(R.string.details_not_reported)
        fun reported(v: String?) = if (v.isNullOrBlank()) DetailRowValue(notReported, ValueOrigin.UNKNOWN) else DetailRowValue(v, ValueOrigin.REPORTED)

        val out = mutableListOf<DetailSection>()

        // ── Receiver
        val receiver = mutableListOf<DetailRow>()
        receiver += DetailRow(R.string.details_source,
            ctx.getString(if (external) R.string.receiver_source_external else R.string.receiver_source_tablet), ValueOrigin.SET_BY_USER)
        if (external) {
            val identity = s.identity
            val model = if (identity?.modelVerified == true) reported(identity.reportedModel) else reported(null)
            receiver += DetailRow(R.string.details_model, model.value, model.origin)
            receiver += DetailRow(R.string.details_profile, s.profile.label, when (s.profileOrigin) {
                ProfileOrigin.USER -> ValueOrigin.SET_BY_USER
                ProfileOrigin.DEFAULT -> ValueOrigin.DEFAULT
                ProfileOrigin.RECEIVER -> if (identity?.modelVerified == true) ValueOrigin.REPORTED else ValueOrigin.EARLIER_REPORT
            })
            reported(info?.serial).let { receiver += DetailRow(R.string.details_serial, it.value, it.origin) }
            reported(info?.firmware).let { receiver += DetailRow(R.string.details_firmware, it.value, it.origin) }
            if (i.capabilities.hasBattery) {
                val b = i.battery?.takeIf { live }
                receiver += if (b == null) DetailRow(R.string.details_battery, notReported, ValueOrigin.UNKNOWN)
                else DetailRow(R.string.details_battery, ctx.getString(
                    if (b.isCharging) R.string.details_battery_value_charging else R.string.details_battery_value, b.percent), ValueOrigin.REPORTED)
            }
        }
        out += DetailSection(R.string.details_section_receiver, receiver)

        // ── RTK corrections (receiver reports; correction age also from NMEA differential age)
        if (external) {
            val c = i.corrections?.takeIf { live }
            val rows = mutableListOf<DetailRow>()
            if (i.capabilities.hasCorrectionsStatus) {
                rows += if (c == null) DetailRow(R.string.details_corrections, notReported, ValueOrigin.UNKNOWN)
                else DetailRow(R.string.details_corrections, when {
                    !c.isReceiving -> ctx.getString(R.string.details_corrections_not_receiving)
                    c.channel.isNullOrBlank() -> ctx.getString(R.string.details_corrections_receiving)
                    else -> ctx.getString(R.string.details_corrections_receiving_channel, c.channel)
                }, ValueOrigin.REPORTED)
            }
            val fix = i.fix?.takeIf { live }
            val diffAge = fix?.diffAgeS
            val age = c?.ageS ?: diffAge
            rows += if (age == null) DetailRow(R.string.details_correction_age, notReported, ValueOrigin.UNKNOWN)
            else DetailRow(R.string.details_correction_age, ctx.getString(R.string.details_correction_age_value, age), ValueOrigin.REPORTED)
            val baseline = c?.baselineKm
            rows += if (baseline == null) DetailRow(R.string.details_baseline, notReported, ValueOrigin.UNKNOWN)
            else DetailRow(R.string.details_baseline, ctx.getString(R.string.details_baseline_value, baseline), ValueOrigin.REPORTED)
            out += DetailSection(R.string.details_section_corrections, rows)
        }

        // ── Coordinates and heights (app settings)
        val defaults = CoordinateSystemSettings()
        val coords = mutableListOf<DetailRow>()
        if (external) {
            coords += DetailRow(R.string.details_receiver_datum, i.coordinates.externalReceiverDatum.label,
                setting(i.coordinates.externalReceiverDatum == defaults.externalReceiverDatum))
        }
        coords += DetailRow(R.string.details_vertical, i.coordinates.vertical.label, setting(i.coordinates.vertical == defaults.vertical))
        if (external) coords += DetailRow(R.string.details_receiver_h_model, ctx.getString(R.string.details_receiver_h_model_value), ValueOrigin.UNKNOWN)
        out += DetailSection(R.string.details_section_coordinates, coords)

        // ── Antenna and tilt
        if (external) {
            val setup = AntennaSetup.resolve(i.receiverSettings, s.profile)
            val antenna = mutableListOf<DetailRow>()
            antenna += DetailRow(R.string.details_pole_height,
                ctx.getString(R.string.details_meters, AntennaHeightInput.format(i.receiverSettings.poleHeightM)),
                setting(i.receiverSettings.poleHeightM == GnssReceiverSettings().poleHeightM))
            antenna += if (setup.offsetFromProfile || i.receiverSettings.phaseCenterOffsetOverrideM != null || s.profile.phaseCenterOffsetM != null)
                DetailRow(R.string.details_pco, ctx.getString(R.string.details_meters, AntennaHeightInput.format(setup.phaseCenterOffsetM)),
                    if (setup.offsetFromProfile) ValueOrigin.DEFAULT else ValueOrigin.SET_BY_USER,
                    note = if (setup.offsetFromProfile) s.profile.label else null)
            else DetailRow(R.string.details_pco, unknown, ValueOrigin.UNKNOWN)
            antenna += DetailRow(R.string.details_compensated, i.compensated.label,
                if (i.compensated == CompensatedOutputSetting.UNKNOWN) ValueOrigin.UNKNOWN else ValueOrigin.SET_BY_USER,
                note = ctx.getString(R.string.details_tilt_note))
            out += DetailSection(R.string.details_section_antenna, antenna)
        }

        // ── Troubleshooting
        val trouble = mutableListOf<DetailRow>()
        val card = ReceiverCardPresenter.connection(s)
        trouble += DetailRow(R.string.details_link, ReceiverCardBinder.stateTextOf(ctx, card), null)
        val failed = s.attempt as? ConnectionAttempt.Failed
        trouble += DetailRow(R.string.details_last_error, failed?.error?.message ?: ctx.getString(R.string.details_none), null)
        trouble += DetailRow(R.string.details_nmea_rate,
            ctx.getString(R.string.details_nmea_rate_value, i.nmeaLinesPerSecond), null)
        out += DetailSection(R.string.details_section_troubleshooting, trouble)
        return out
    }

    /**
     * A plain-text summary for support (Copy summary). Never includes the receiver's host, port, name or any
     * credential: model, firmware, serial, connection state, fix, NMEA rate and settings only.
     */
    fun summary(ctx: Context, sections: List<DetailSection>): String = buildString {
        appendLine(ctx.getString(R.string.details_title))
        for (sec in sections) {
            appendLine()
            appendLine(ctx.getString(sec.title))
            for (r in sec.rows) {
                if (r.label == R.string.details_name) continue
                val tag = r.origin?.let { " [${originText(ctx, it)}]" }.orEmpty()
                appendLine("${ctx.getString(r.label)}: ${r.value}$tag")
            }
        }
    }.trimEnd()

    fun originText(ctx: Context, o: ValueOrigin): String = ctx.getString(when (o) {
        ValueOrigin.REPORTED -> R.string.receiver_origin_reported
        ValueOrigin.SET_BY_USER -> R.string.receiver_origin_user
        ValueOrigin.DEFAULT -> R.string.receiver_origin_default
        ValueOrigin.EARLIER_REPORT -> R.string.receiver_origin_earlier
        ValueOrigin.UNKNOWN -> R.string.receiver_origin_unknown
    })

    private fun setting(isDefault: Boolean) = if (isDefault) ValueOrigin.DEFAULT else ValueOrigin.SET_BY_USER

    private data class DetailRowValue(val value: String, val origin: ValueOrigin)

}
