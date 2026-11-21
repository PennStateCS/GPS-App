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
            batteryText = null,
            statusLevel = GnssStatusLevel.NONE,
            isExternal = !isInternal,
            isWaiting = true,
            isStale = false
        )
    }

    /**
     * Shown while the toolbar pipeline is recovering from an error (it resubscribes with backoff).
     * Uses the existing ERROR level; live fields are cleared rather than left stale.
     */
    fun error(source: LocationSourceType, externalLabel: String = GnssStatusFormatter.EXTERNAL_FALLBACK_LABEL): GnssToolbarState =
        waiting(source, externalLabel).copy(fixText = "Error", fixLevel = GnssStatusLevel.ERROR, statusLevel = GnssStatusLevel.ERROR)

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
        if (status == LinkStatus.LOCATION_OFF) return base.copy(fixText = "Location off")

        val usable = last?.takeIf { fixMatchesSource(source, it.fix) && nowMs - it.atMs in 0..STALE_KEEP_MS }
        if (usable != null && status != LinkStatus.STREAMING) {
            val shown = (map(source, usable.fix, SkySnapshot(), externalLabel, usable.coords) as? ToolbarMapResult.Render)?.state
            if (shown != null) {
                return shown.copy(
                    fixText = "${shown.fixText} (lost ${seconds(nowMs - usable.atMs)}s)",
                    fixLevel = GnssStatusLevel.NONE,
                    statusLevel = GnssStatusLevel.NONE,
                    satelliteText = null,
                    accuracyText = null,
                    accuracyLevel = GnssStatusLevel.NONE,
                    isWaiting = true,
                    isStale = true,
                )
            }
        }
        val text = when (status) {
            LinkStatus.CONNECTING -> "Connecting…"
            LinkStatus.CONNECTED_NO_DATA -> link.lastRawNmeaAtMs?.let { "No data ${seconds(nowMs - it)}s" } ?: "No data"
            else -> "No fix"
        }
        return base.copy(fixText = text)
    }

    private fun seconds(ms: Long): Long = ms.coerceAtLeast(0L) / 1_000L

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
    ): ToolbarMapResult {
        if (fix == null) return ToolbarMapResult.Render(noFix(selectedSource, externalLabel, link, last, nowMs))
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
        val accuracyText: String?
        val accuracyLevel: GnssStatusLevel
        if (hAcc != null && hAcc in 0.0..9999.0) {
            accuracyText = (if (estimated) "~" else "") + GnssStatusFormatter.formatAccuracyMeters(hAcc)
            accuracyLevel = accuracyLevel(hAcc, isInternal = selectedSource == LocationSourceType.INTERNAL)
        } else {
            accuracyText = null
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
            altitudeLabel = coords.verticalLabel.uppercase(Locale.US)
            altitudeText = coords.height?.let {
                String.format(Locale.US, "%,.2f %s", it, coords.unit.suffix)
            } ?: "--"
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

        val isReplay = fix.provider == Provider.REPLAY

        // --- CORRECTION AGE (external receivers only) ---
        // TODO(colour update): grade the age — e.g. good < 2 s, warn < 5 s, bad at the capture gate
        // (AveragingPolicy.maxDiffAgeSec). Plain value colour for now.
        val correctionAgeText = fix.diffAgeS
            ?.takeIf { !isInternal && !isReplay && it.isFinite() && it >= 0.0 }
            ?.let { GnssStatusFormatter.formatAgeSeconds(it) }

        return ToolbarMapResult.Render(
            GnssToolbarState(
                sourceText = if (isReplay) "Replay" else GnssStatusFormatter.formatSource(isInternal, externalLabel),
                fixText = fixText,
                fixLevel = fixLevel,
                satelliteText = satelliteText,
                accuracyText = accuracyText,
                accuracyLevel = accuracyLevel,
                latLonText = latLonText,
                altitudeText = altitudeText,
                correctionAgeText = correctionAgeText,
                batteryVisible = !isInternal && !isReplay,   // no receiver battery for a log file
                batteryText = null,
                statusLevel = fixLevel,
                isExternal = !isInternal,
                isWaiting = false,
                isStale = false,
                coordLabel = coordLabel,
                altitudeLabel = altitudeLabel,
                coordToggleAvailable = gridConfigured,
            )
        )
    }
}
