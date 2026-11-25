package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import io.github.pennstatecs.geokit.gnss.accuracy.AccuracyEstimator
import io.github.pennstatecs.geokit.gnss.accuracy.UereTable
import app.surrealar.gnss.format.GnssStatusFormatter
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import java.util.Locale

/** Result of mapping live GNSS inputs: either render a state, or ignore this fix (with a reason). */
sealed interface ToolbarMapResult {
    data class Render(val state: GnssToolbarState) : ToolbarMapResult
    data class Ignore(val reason: String) : ToolbarMapResult
}

/** External receiver connection phase, read from the adapter's existing connection state. */
enum class ExternalLinkPhase { CONNECTING, CONNECTED }

/** Neutral link status the toolbar explains when there's no position. */
enum class LinkStatus { CONNECTING, CONNECTED_NO_DATA, STREAMING, LOCATION_OFF, ACQUIRING }

/**
 * What the toolbar knows about the link besides the fix itself (all read-only).
 *
 * @property externalPhase external receiver phase; null for internal GNSS.
 * @property lastRawNmeaAtMs monotonic time (ms) of the last raw NMEA line from the active source, or null.
 * @property locationEnabled internal GNSS: location permission granted and the location setting on.
 */
data class ToolbarLinkInputs(
    val externalPhase: ExternalLinkPhase? = null,
    val lastRawNmeaAtMs: Long? = null,
    val locationEnabled: Boolean = true,
)

/** The last fix shown for the current source and receiver, kept to explain a lost link. */
data class LastFix(
    val fix: Fix,
    /** Monotonic time (ms) it was last current. */
    val atMs: Long,
    val coords: app.surrealar.coords.ConvertedPosition?,
)

/**
 * Pure mapper: turns the current GNSS inputs into a [GnssToolbarState]. Holds ALL the display
 * decision logic that used to live in `MainActivity.updateStatusTokens` so it can be unit-tested
 * without an Activity. No Android view, Context, or color lookups here.
 *
 * Source-switch protections live here too:
 *  - wrong-provider fixes (internal fix while RS2+ selected during the connecting window) → Ignore
 *  - invalid coordinates → Render(waiting) (never leave older values on screen)
 *  - no current-provider fix → Render(a no-fix state that says why: connecting, no data, lost, …)
 *
 * Liveness is NOT judged here by comparing the fix's GNSS timestamp with the device clock (a skewed
 * tablet clock froze the toolbar). The input is `FixSwitchboard.currentFix`, which the GNSS feed
 * sets to null when fixes stop arriving (receive-time watchdog) — that null renders "waiting".
 */
object GnssToolbarStateMapper {

    private val uereTable = UereTable()

    /**
     * Accuracy colour. External receivers are graded for survey work (RTK: green ≤ 0.30 m, amber
     * ≤ 1 m). Internal phone/tablet GNSS can never reach that, so grading it the same way made it
     * permanently red; it gets phone-scale thresholds instead (green ≤ 5 m, amber ≤ 15 m).
     */
    internal fun accuracyLevel(hAccM: Double, isInternal: Boolean): GnssStatusLevel =
        if (isInternal) when {
            hAccM <= INTERNAL_GOOD_M -> GnssStatusLevel.SUCCESS
            hAccM <= INTERNAL_FAIR_M -> GnssStatusLevel.WARNING
            else                     -> GnssStatusLevel.ERROR
        } else when {
            hAccM <= 0.30 -> GnssStatusLevel.SUCCESS
            hAccM <= 1.0  -> GnssStatusLevel.WARNING
            else          -> GnssStatusLevel.ERROR
        }

    internal const val INTERNAL_GOOD_M = 5.0
    internal const val INTERNAL_FAIR_M = 15.0

    /** Raw NMEA older than this means the connected receiver has stopped sending. */
    internal const val RAW_STALE_MS = 3_000L

    /** A lost position stays on screen (greyed) this long, then falls back to "--". */
    const val STALE_KEEP_MS = 60_000L

    /** NMEA replay fixes ([Provider.REPLAY]) are shown whichever source is selected. */
    fun fixMatchesSource(source: LocationSourceType, fix: Fix): Boolean =
        if (fix.provider == Provider.REPLAY) true
        else if (source == LocationSourceType.INTERNAL) fix.provider == Provider.INTERNAL
        else fix.provider != Provider.INTERNAL

    /** Display state while there is no current-provider fix and nothing more is known. */
    fun waiting(source: LocationSourceType, externalLabel: String = GnssStatusFormatter.EXTERNAL_FALLBACK_LABEL): GnssToolbarState {
        val isInternal = source == LocationSourceType.INTERNAL
        return GnssToolbarState(
            sourceText = GnssStatusFormatter.formatSource(isInternal, externalLabel),
            fixText = if (isInternal) "No fix" else "Connecting…",
            fixLevel = GnssStatusLevel.NONE,
            satelliteText = null,
            accuracyText = null,
            accuracyLevel = GnssStatusLevel.NONE,
            latLonText = "--",
            altitudeText = "--",
            correctionAgeText = null,
            batteryVisible = !isInternal,
            isExternal = !isInternal,
            isWaiting = true,
            isStale = false,
            sourceDescription = sourceDescription(isInternal, externalLabel, isReplay = false),
            fixDescription = if (isInternal) "No fix" else "Connecting to receiver",
            coordDescription = "Position not available",
            altitudeDescription = "Height not available",
        )
    }

    /**
     * Shown while the toolbar pipeline is recovering from an error (it resubscribes with backoff).
     * Uses the existing ERROR level; live fields are cleared rather than left stale.
     */
    fun error(source: LocationSourceType, externalLabel: String = GnssStatusFormatter.EXTERNAL_FALLBACK_LABEL): GnssToolbarState =
        waiting(source, externalLabel).copy(fixText = "Error", fixLevel = GnssStatusLevel.ERROR,
            fixDescription = "GNSS status error, retrying")

    /** The link status for [source] at [nowMs] (monotonic). */
    fun linkStatus(source: LocationSourceType, link: ToolbarLinkInputs, nowMs: Long): LinkStatus {
        if (source == LocationSourceType.INTERNAL) {
            return if (link.locationEnabled) LinkStatus.ACQUIRING else LinkStatus.LOCATION_OFF
        }
        if (link.externalPhase != ExternalLinkPhase.CONNECTED) return LinkStatus.CONNECTING
        val raw = link.lastRawNmeaAtMs
        return if (raw != null && nowMs - raw <= RAW_STALE_MS) LinkStatus.STREAMING else LinkStatus.CONNECTED_NO_DATA
    }

    /**
     * No current fix: say why. "Location off" (internal: permission or setting), "Connecting…",
     * "No data 12s" (receiver connected but silent), "No fix" (data flowing / acquiring), or — when
     * the link was lost after a fix within [STALE_KEEP_MS] — the last position greyed out with
     * "<status> (lost 12s)" and no accuracy/satellites. All in the neutral style (no new colours).
     * The last fix is only ever this source's (and receiver's); see ToolbarPipeline's memory.
     */
    fun noFix(
        source: LocationSourceType,
        externalLabel: String,
        link: ToolbarLinkInputs,
        last: LastFix?,
        nowMs: Long,
    ): GnssToolbarState {
        val base = waiting(source, externalLabel)
        val status = linkStatus(source, link, nowMs)
        if (status == LinkStatus.LOCATION_OFF) return base.copy(fixText = "Location off", fixDescription = "Location is off")

        val usable = last?.takeIf { fixMatchesSource(source, it.fix) && nowMs - it.atMs in 0..STALE_KEEP_MS }
        if (usable != null && status != LinkStatus.STREAMING) {
            val shown = (map(source, usable.fix, SkySnapshot(), externalLabel, usable.coords) as? ToolbarMapResult.Render)?.state
            if (shown != null) {
                return shown.copy(
                    fixText = "${shown.fixText} (lost ${seconds(nowMs - usable.atMs)}s)",
                    fixLevel = GnssStatusLevel.NONE,
                    satelliteText = null,
                    accuracyText = null,
                    accuracyLevel = GnssStatusLevel.NONE,
                    isWaiting = true,
                    isStale = true,
                    fixDescription = "Position lost ${seconds(nowMs - usable.atMs)} seconds ago, showing the last position",
                    satsDescription = null,
                    accuracyDescription = null,
                    ageDescription = null,
                    coordDescription = "Last known " + shown.coordDescription.replaceFirstChar { it.lowercase() },
                    altitudeDescription = "Last known " + shown.altitudeDescription.replaceFirstChar { it.lowercase() },
                )
            }
        }
        val silentS = link.lastRawNmeaAtMs?.let { seconds(nowMs - it) }
        val (text, spoken) = when (status) {
            LinkStatus.CONNECTING -> "Connecting…" to "Connecting to receiver"
            LinkStatus.CONNECTED_NO_DATA -> (silentS?.let { "No data ${it}s" } ?: "No data") to
                (silentS?.let { "Receiver connected, no data for $it seconds" } ?: "Receiver connected, no data")
            else -> "No fix" to "No fix"
        }
        return base.copy(fixText = text, fixDescription = spoken)
    }

    private fun seconds(ms: Long): Long = ms.coerceAtLeast(0L) / 1_000L

    /** Source label while an NMEA log replays. */
    const val REPLAY_LABEL = "REPLAY"

    /** [s] marked as replay: REPLAY source, no receiver battery, no correction age. */
    private fun replay(s: GnssToolbarState) = s.copy(
        sourceText = REPLAY_LABEL, isReplay = true, batteryVisible = false, correctionAgeText = null,
        sourceDescription = sourceDescription(isInternal = false, label = "", isReplay = true), ageDescription = null)

    // ── Spoken descriptions (TalkBack) ─────────────────────────────────────────

    internal fun sourceDescription(isInternal: Boolean, label: String, isReplay: Boolean): String = when {
        isReplay -> "Source: NMEA replay"
        isInternal -> "Source: device GNSS"
        else -> "Source: $label receiver"
    }

    internal fun fixDescription(status: RtkStatus, isInternal: Boolean): String = "Solution: " + when {
        isInternal -> if (status == RtkStatus.NONE || status == RtkStatus.INVALID) "no fix" else "single, no corrections"
        else -> when (status) {
            RtkStatus.FIX -> "RTK fix"
            RtkStatus.FLOAT -> "RTK float"
            RtkStatus.DGPS -> "DGPS"
            RtkStatus.SINGLE -> "single, no corrections"
            RtkStatus.DEAD_RECKONING -> "dead reckoning"
            RtkStatus.NONE, RtkStatus.INVALID -> "no fix"
        }
    }

    /**
     * "Accuracy plus or minus 1.5 centimetres, estimated" (centimetres under 1 m, else metres), with
     * ", vertical plus or minus 3 centimetres" when [vAccM] is given.
     */
    internal fun accuracyDescription(hAccM: Double, estimated: Boolean, vAccM: Double? = null): String {
        fun amount(m: Double) = if (m < 1.0) "${trim1(m * 100.0)} centimetres" else String.format(Locale.US, "%.2f metres", m)
        return "Accuracy plus or minus ${amount(hAccM)}" + (if (estimated) ", estimated" else "") +
            (vAccM?.let { ", vertical plus or minus ${amount(it)}" } ?: "")
    }

    internal fun ageDescription(ageS: Double): String = "Correction age ${trim1(ageS)} seconds"

    internal fun satsDescription(used: Int, visible: Int): String = "Satellites $used used of $visible"

    /** Battery token description, e.g. "Receiver battery 80 percent, charging". */
    fun batteryDescription(percent: Int?, charging: Boolean?): String =
        if (percent == null) "Receiver battery unknown"
        else "Receiver battery $percent percent" + if (charging == true) ", charging" else ""

    private fun trim1(v: Double): String {
        val r = Math.round(v * 10.0) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%.1f", r)
    }

    /** Toolbar height value while the height model's data is loading (never a stale or substituted value). */
    const val HEIGHT_LOADING = "loading"

    /** Longest toolbar height label allowed (the token is sized for "EGM2008" / "NAPGD2022β"). */
    const val MAX_HEIGHT_LABEL = 10

    /**
     * The toolbar's short height label: the converted position's vertical label ("NAVD88", "EGM2008", "h",
     * "H rcvr"), except GEOID2022 (beta) which is "NAPGD2022β" ([app.surrealar.coords.Geoid2022Labels.TOOLBAR_LABEL])
     * instead of the longer "NAPGD2022 beta".
     */
    fun toolbarHeightLabel(p: app.surrealar.coords.ConvertedPosition): String =
        if (p.vertical == app.surrealar.settings.model.VerticalReference.GEOID2022) app.surrealar.coords.Geoid2022Labels.TOOLBAR_LABEL
        else p.verticalLabel

    /** Spoken form of the height label: the beta mark read as "beta". */
    private fun toolbarHeightSpoken(p: app.surrealar.coords.ConvertedPosition): String =
        if (p.vertical == app.surrealar.settings.model.VerticalReference.GEOID2022) app.surrealar.coords.Geoid2022Labels.VERTICAL_LABEL
        else p.verticalLabel

    fun map(
        selectedSource: LocationSourceType,
        fix: Fix?,
        sky: SkySnapshot,
        externalLabel: String = GnssStatusFormatter.EXTERNAL_FALLBACK_LABEL,
        /** The fix expressed in the user's coordinate system (null = legacy lat/lon + MSL display). */
        coords: app.surrealar.coords.ConvertedPosition? = null,
        /** Link state used to explain a missing fix. */
        link: ToolbarLinkInputs = ToolbarLinkInputs(),
        /** Last fix for this source/receiver, to keep a lost position on screen (greyed). */
        last: LastFix? = null,
        /** Monotonic now (ms). */
        nowMs: Long = 0L,
        /** Toolbar-only override: show lat/lon even when a grid system is configured (tap on LL/NE). */
        preferLatLon: Boolean = false,
        /** The active provider is NMEA replay (even before its first fix). */
        replayActive: Boolean = false,
    ): ToolbarMapResult {
        if (fix == null) {
            val s = noFix(selectedSource, externalLabel, link, last, nowMs)
            return ToolbarMapResult.Render(if (replayActive) replay(s).copy(fixText = "No fix", isStale = false) else s)
        }
        if (!fixMatchesSource(selectedSource, fix)) return ToolbarMapResult.Ignore("wrong-provider")
        if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) {
            return ToolbarMapResult.Render(waiting(selectedSource, externalLabel))
        }

        val isInternal = selectedSource == LocationSourceType.INTERNAL

        // --- FIX / SOL --- (label wording is shared via GnssStatusFormatter; level stays here)
        val fixText = GnssStatusFormatter.formatFixStatus(fix.rtkStatus, isInternal)
        val fixLevel = if (isInternal) {
            when (fix.rtkStatus) {
                RtkStatus.NONE, RtkStatus.INVALID -> GnssStatusLevel.ERROR
                else -> GnssStatusLevel.SUCCESS
            }
        } else {
            when (fix.rtkStatus) {
                RtkStatus.NONE           -> GnssStatusLevel.ERROR
                RtkStatus.SINGLE         -> GnssStatusLevel.WARNING
                RtkStatus.DGPS           -> GnssStatusLevel.INFO
                RtkStatus.FLOAT          -> GnssStatusLevel.WARNING
                RtkStatus.FIX            -> GnssStatusLevel.SUCCESS
                RtkStatus.DEAD_RECKONING -> GnssStatusLevel.WARNING
                RtkStatus.INVALID        -> GnssStatusLevel.ERROR
            }
        }

        // --- SATS ---
        val used = if (sky.totalUsed > 0) sky.totalUsed else (fix.satsUsed ?: 0).coerceIn(0, 100)
        val vis = if (sky.totalVisible > 0) sky.totalVisible else (fix.satsVisible ?: used).coerceIn(used, 100)
        val satelliteText = if (used > 0 || vis > 0) GnssStatusFormatter.formatSatellites(used, vis) else null

        // --- ACCURACY ---
        // Receiver-reported (GST / platform) when available; otherwise the same DOP × UERE estimate
        // capture uses, marked with "~" so it is never mistaken for a reported value. Many phones'
        // NMEA has no GST, so without this the internal source never showed an accuracy.
        val reportedH = fix.hAccM
        val hAcc = reportedH ?: AccuracyEstimator.estimate1SigmaMeters(fix, uereTable).horizontalMeters
        val estimated = reportedH == null
        // Vertical (1σ) alongside it for an external receiver that reports it, like the Home card;
        // never estimated, and never for internal GNSS (Android's vertical accuracy is unreliable).
        // An invalid vertical-accuracy marker (e.g. Emlid's 999 m when receiver-side height corrections are
        // interrupted) is not an accuracy: no vertical value is shown then.
        val vAcc = fix.vAccM?.takeIf { !isInternal && !estimated && it in 0.0..9999.0 &&
            !io.github.pennstatecs.geokit.gnss.accuracy.VerticalAccuracyValidity.isInvalid(fix) }
        val accuracyText: String?
        val accuracyCompactText: String?
        val accuracyLevel: GnssStatusLevel
        if (hAcc != null && hAcc in 0.0..9999.0) {
            accuracyCompactText = (if (estimated) "~" else "") + GnssStatusFormatter.formatAccuracyMeters(hAcc)
            accuracyText = if (vAcc != null) GnssStatusFormatter.formatHorizontalVerticalAccuracy(hAcc, vAcc) else accuracyCompactText
            accuracyLevel = accuracyLevel(hAcc, isInternal = selectedSource == LocationSourceType.INTERNAL)
        } else {
            accuracyText = null
            accuracyCompactText = null
            accuracyLevel = GnssStatusLevel.NONE
        }

        // --- COORDINATES ---
        // --- COORDINATES / HEIGHT: in the user's coordinate system when provided ---
        val latLonText: String
        val altitudeText: String
        var coordLabel = "LL"
        var altitudeLabel = "ALT"
        val gridConfigured = coords?.easting != null && coords.northing != null
        if (coords != null) {
            latLonText = if (gridConfigured && !preferLatLon) {
                coordLabel = "NE"
                app.surrealar.coords.CoordinateFormat.horizontal(coords)
            } else {
                // Lat/lon from the converted position (also when a grid exists and the user toggled to LL).
                String.format(Locale.US, "%.6f, %.6f", coords.latDeg, coords.lonDeg)
            }
            // Not upper-cased: "h" (ellipsoidal) and "H" (orthometric) are different heights.
            altitudeLabel = toolbarHeightLabel(coords)
            altitudeText = coords.height?.let {
                String.format(Locale.US, "%,.2f %s", it, coords.unit.suffix)
            } ?: if (coords.heightLoading) HEIGHT_LOADING else "--"
        } else {
            latLonText = "${String.format(Locale.US, "%.6f", fix.latDeg)}, " +
                String.format(Locale.US, "%.6f", fix.lonDeg)
            // --- ALTITUDE: prefer MSL, else ellipsoidal, validate range ---
            val altMsl = fix.altMslM
            val altEllip = fix.altEllipsoidalM
            altitudeText = when {
                altMsl != null && altMsl in -500.0..10000.0 -> String.format(Locale.US, "%.2f m", altMsl)
                altEllip != null && altEllip in -500.0..10000.0 -> String.format(Locale.US, "%.2f m", altEllip)
                else -> "--"
            }
        }

        val isReplay = fix.provider == Provider.REPLAY || replayActive

        // --- CORRECTION AGE (external receivers only) ---
        // TODO(colour update): grade the age — e.g. good < 2 s, warn < 5 s, bad at the capture gate
        // (AveragingPolicy.maxDiffAgeSec). Plain value colour for now.
        val correctionAgeText = fix.diffAgeS
            ?.takeIf { !isInternal && !isReplay && it.isFinite() && it >= 0.0 }
            ?.let { GnssStatusFormatter.formatAgeSeconds(it) }

        return ToolbarMapResult.Render(
            GnssToolbarState(
                sourceText = if (isReplay) REPLAY_LABEL else GnssStatusFormatter.formatSource(isInternal, externalLabel),
                fixText = fixText,
                fixLevel = fixLevel,
                satelliteText = satelliteText,
                accuracyText = accuracyText,
                accuracyLevel = accuracyLevel,
                accuracyCompactText = accuracyCompactText.takeIf { it != accuracyText },
                latLonText = latLonText,
                altitudeText = altitudeText,
                correctionAgeText = correctionAgeText,
                batteryVisible = !isInternal && !isReplay,   // no receiver battery for a log file
                isExternal = !isInternal,
                isWaiting = false,
                isStale = false,
                coordLabel = coordLabel,
                altitudeLabel = altitudeLabel,
                coordToggleAvailable = gridConfigured,
                isReplay = isReplay,
                sourceDescription = sourceDescription(isInternal, externalLabel, isReplay),
                fixDescription = fixDescription(fix.rtkStatus, isInternal),
                satsDescription = if (satelliteText != null) satsDescription(used, vis) else null,
                accuracyDescription = if (accuracyText != null && hAcc != null) accuracyDescription(hAcc, estimated, vAcc) else null,
                ageDescription = correctionAgeText?.let { ageDescription(fix.diffAgeS!!) },
                coordDescription = (if (coordLabel == "NE") "Grid position " else "Position ") + latLonText.replace(" · ", ", "),
                altitudeDescription = when {
                    altitudeText == "--" -> "Height not available"
                    altitudeText == HEIGHT_LOADING -> "Height ${coords?.let { toolbarHeightSpoken(it) } ?: altitudeLabel}: data is loading"
                    else -> "Height ${coords?.let { toolbarHeightSpoken(it) } ?: altitudeLabel} $altitudeText"
                },
            )
        )
    }
}
