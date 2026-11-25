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
import app.surrealar.receiver.tilt.PositionReferencePolicy
import app.surrealar.ui.settings.AntennaHeightInput
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.receiver.emlid.EmlidMapping
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo

/**
 * One row of the receiver details: label, value, where the value comes from, optional note. [labelText] = a label the
 * receiver itself named (vendor details); it replaces the [label] resource (0 then).
 */
data class DetailRow(val label: Int, val value: String, val origin: ValueOrigin?, val note: String? = null, val labelText: String? = null) {
    fun labelOf(ctx: Context): String = labelText ?: ctx.getString(label)
}

/** [collapsible] = the sheet shows only the title until the user expands it (receiver-reported vendor details). */
data class DetailSection(val title: Int, val rows: List<DetailRow>, val collapsible: Boolean = false)

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
            // Recognised model: the friendly name, with the receiver's own string underneath ("ReachRS2+").
            val recognized = identity?.recognizedProfile?.takeIf { identity.modelVerified }
            if (recognized != null) receiver += DetailRow(R.string.details_model, recognized.label, ValueOrigin.REPORTED,
                note = identity.reportedModel?.takeIf { it.isNotBlank() && it != recognized.label }
                    ?.let { ctx.getString(R.string.details_model_reported_as, it) })
            else {
                val model = if (identity?.modelVerified == true) reported(identity.reportedModel) else reported(null)
                receiver += DetailRow(R.string.details_model, model.value, model.origin)
            }
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
            // L9: RTK fix quality from the live fix (every external receiver; the NMEA stream reports it).
            val liveFix = i.fix?.takeIf { live && it.provider != Provider.INTERNAL && validLatLon(it) }
            rows += if (liveFix == null) DetailRow(R.string.details_fix_quality, ctx.getString(R.string.receiver_fix_waiting), ValueOrigin.UNKNOWN)
            else {
                val quality = fixText(ctx, ReceiverCardPresenter.fixOf(liveFix.rtkStatus))
                val age = liveFix.diffAgeS
                DetailRow(R.string.details_fix_quality,
                    if (age == null) quality else ctx.getString(R.string.details_fix_quality_age, quality, age), ValueOrigin.REPORTED)
            }
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
            // L9: tilt only exists on the RS4 / RS4 Pro. The stored value does not say who set it: the default
            // (Unknown) is labelled Default, Enabled / Disabled can only have been chosen by the user.
            if (PositionReferencePolicy.appliesTo(s.profile)) {
                antenna += DetailRow(R.string.details_compensated, i.compensated.label,
                    setting(i.compensated == CompensatedOutputSetting.DEFAULT),
                    note = ctx.getString(R.string.details_tilt_note))
            }
            out += DetailSection(R.string.details_section_antenna, antenna)
        }

        // ── Receiver-reported details (M4: what the old device-info panel showed; collapsible)
        if (external) out += DetailSection(R.string.details_section_vendor, vendorRows(ctx, info, i.battery?.takeIf { live }), collapsible = true)

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
                appendLine("${r.labelOf(ctx)}: ${r.value}$tag")
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

    /**
     * Uptime, temperature, power and storage as the receiver reports them (Emlid device info / battery), plus the
     * vendor's own extra fields, including the receiver's self-test summary (Emlid device.self_tests). Never the
     * address, host name or the raw HTTP response.
     */
    fun vendorRows(ctx: Context, info: ReceiverDeviceInfo?, battery: ReceiverBattery?): List<DetailRow> {
        val rows = mutableListOf<DetailRow>()
        fun add(label: Int, v: String?) { if (!v.isNullOrBlank()) rows += DetailRow(label, v, ValueOrigin.REPORTED) }
        add(R.string.details_uptime, info?.uptimeText ?: info?.uptimeS?.let { formatUptime(it) })
        add(R.string.details_temperature, battery?.temperatureC?.let { ctx.getString(R.string.details_temperature_value, it) })
        add(R.string.details_voltage, battery?.voltageV?.let { ctx.getString(R.string.details_voltage_value, it) })
        add(R.string.details_current, battery?.currentA?.let { ctx.getString(R.string.details_current_value, it) })
        add(R.string.details_charger, battery?.chargerStatus)
        add(R.string.details_storage, info?.storage?.let {
            ctx.getString(R.string.details_storage_value, it.usedMb, it.totalMb, it.availableMb)
        })
        val extras = (info?.details.orEmpty() + battery?.details.orEmpty()).filterKeys { it != EmlidMapping.RAW_RESPONSE }
        for ((k, v) in extras) if (v.isNotBlank()) rows += DetailRow(0, v, ValueOrigin.REPORTED, labelText = k)
        if (rows.isEmpty()) rows += DetailRow(R.string.details_vendor_none, ctx.getString(R.string.details_not_reported), ValueOrigin.UNKNOWN)
        return rows
    }

    /** "2d 3h 4m" / "3h 4m" / "4m" (as the Developer page shows it). */
    fun formatUptime(seconds: Long): String {
        val days = seconds / 86400; val h = (seconds % 86400) / 3600; val m = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    private fun fixText(ctx: Context, f: CardFix): String = ctx.getString(when (f) {
        CardFix.NO_FIX -> R.string.receiver_fix_none
        CardFix.SINGLE -> R.string.receiver_fix_single
        CardFix.DGPS -> R.string.receiver_fix_dgps
        CardFix.FLOAT -> R.string.receiver_fix_float
        CardFix.FIX -> R.string.receiver_fix_fix
        CardFix.DEAD_RECKONING -> R.string.receiver_fix_dr
    })

    /** A valid position (fix liveness = valid lat/lon, never wall-clock age). */
    private fun validLatLon(f: Fix): Boolean {
        val lat = f.latDeg
        val lon = f.lonDeg
        return !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
    }

    private fun setting(isDefault: Boolean) = if (isDefault) ValueOrigin.DEFAULT else ValueOrigin.SET_BY_USER

    private data class DetailRowValue(val value: String, val origin: ValueOrigin)

}
