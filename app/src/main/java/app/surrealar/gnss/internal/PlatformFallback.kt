package app.surrealar.gnss.internal

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvEntry
import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import java.time.Instant

/**
 * Plain snapshot of an `android.location.Location` (keeps the mapping unit-testable without Android).
 * Optional values are null when the platform did not report them.
 */
internal data class PlatformLocation(
    val timeMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    /** Android reports `Location.altitude` above the WGS84 ellipsoid. */
    val altEllipsoidalM: Double?,
    /** `Location.mslAltitudeMeters` (API 34+). */
    val altMslM: Double?,
    val hAccM: Double?,
    val vAccM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    /** Legacy `extras["satellites"]` some OEMs still populate. */
    val satellitesExtra: Int?,
)

/** One satellite from `android.location.GnssStatus` (one entry per signal on multi-band chips). */
internal data class PlatformSatellite(
    /** NMEA-style talker label for the constellation ("GP", "GL", "GA", "GB", "GQ", "GI", "SBAS"). */
    val constellation: String,
    val svid: Int,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
)

/**
 * Fallback for devices whose internal GNSS does not deliver usable NMEA (some OEM builds, emulators,
 * chipsets that emit only GSV/RMC without GGA). When no NMEA-derived fix / GSV epoch has arrived
 * for [NMEA_GRACE_MS], the platform's `Location` and `GnssStatus` are converted into the same
 * [Fix] / [GsvMessage] types so every page keeps working. As soon as NMEA flows again it takes over.
 */
internal object PlatformFallback {

    /** How long NMEA must be silent (since registration or since its last output) before falling back. */
    const val NMEA_GRACE_MS = 3_000L

    /**
     * True when the platform data should be published: NMEA has produced nothing for [NMEA_GRACE_MS],
     * measured from the later of listener registration and the last NMEA output (monotonic clock).
     */
    fun nmeaSilent(nowMs: Long, registeredAtMs: Long, lastNmeaAtMs: Long): Boolean =
        nowMs - maxOf(registeredAtMs, lastNmeaAtMs) > NMEA_GRACE_MS

    /** Max GNSS-time difference for a platform Location to count as the same epoch as an NMEA fix. */
    const val SAME_EPOCH_MS = 1_500L

    /**
     * Fills what a phone's NMEA left out using the same chip's platform Location for the same epoch:
     *  - **Accuracy**: many phones send no GST, so the fix had no receiver-reported accuracy (only a
     *    DOP × UERE guess downstream). The chip's own `Location.accuracy` / vertical accuracy is a
     *    better, receiver-reported estimate.
     *  - **Ellipsoidal height**: some GGA omits the geoid separation; Location.altitude is
     *    ellipsoidal, which also yields the geoid separation.
     * Values already present in the NMEA are never overwritten. Position is never touched.
     */
    fun enrich(fix: Fix, loc: PlatformLocation?): Fix {
        if (loc == null) return fix
        if (kotlin.math.abs(loc.timeMs - fix.timeUtc.toEpochMilli()) > SAME_EPOCH_MS) return fix
        var out = fix
        if (out.altEllipsoidalM == null && out.altMslM != null) {
            loc.altEllipsoidalM?.takeIf { it.isFinite() }?.let { ell ->
                out = out.copy(altEllipsoidalM = ell, geoidSeparationM = ell - out.altMslM!!)
            }
        }
        if (out.hAccM == null) loc.hAccM?.takeIf { it.isFinite() && it > 0.0 }?.let { out = out.copy(hAccM = it) }
        if (out.vAccM == null) loc.vAccM?.takeIf { it.isFinite() && it > 0.0 }?.let { out = out.copy(vAccM = it) }
        return out
    }

    fun toFix(loc: PlatformLocation, satsUsed: Int?, satsVisible: Int?): Fix {
        val ell = loc.altEllipsoidalM?.takeIf { it.isFinite() }
        val msl = loc.altMslM?.takeIf { it.isFinite() }
        return Fix(
            provider = Provider.INTERNAL,
            timeUtc = Instant.ofEpochMilli(loc.timeMs),
            timestampSource = TimestampSource.GNSS_PROVIDER,
            latDeg = loc.latDeg,
            lonDeg = loc.lonDeg,
            altEllipsoidalM = ell,
            altMslM = msl,
            geoidSeparationM = if (ell != null && msl != null) ell - msl else null,
            hDop = null,
            vDop = null,
            pDop = null,
            // Android's accuracy is a 68 % radius — slightly conservative as a 1-sigma value.
            hAccM = loc.hAccM?.takeIf { it.isFinite() && it > 0.0 },
            vAccM = loc.vAccM?.takeIf { it.isFinite() && it > 0.0 },
            stdDevEastM = null,
            stdDevNorthM = null,
            stdDevUpM = null,
            rtkStatus = RtkStatus.SINGLE,
            satsUsed = satsUsed ?: loc.satellitesExtra,   // null = unknown, never 0
            satsVisible = satsVisible?.takeIf { it >= (satsUsed ?: 0) },
            diffAgeS = null,
            speedMps = loc.speedMps,
            courseDeg = loc.bearingDeg,
        )
    }

    /**
     * Groups GnssStatus satellites into one [GsvMessage] per constellation. Multi-band chips list a
     * satellite once per signal; these are merged (strongest C/N0, used if any signal is used).
     */
    fun toGsvMessages(sats: List<PlatformSatellite>): List<GsvMessage> =
        sats.groupBy { it.constellation }.map { (constellation, list) ->
            val entries = list.groupBy { it.svid }.map { (svid, signals) ->
                val best = signals.maxByOrNull { it.cn0DbHz }!!
                GsvEntry(
                    svid = svid,
                    // Rounded exactly like GsvParser (kotlin.math.round), so a satellite reads the same
                    // whichever path produced it; toInt() truncated (12.7 → 12, −0.6 → 0).
                    elevationDeg = best.elevationDeg.takeIf { it.isFinite() }?.let { kotlin.math.round(it).toInt() },
                    azimuthDeg = best.azimuthDeg.takeIf { it.isFinite() }?.let { kotlin.math.round(it).toInt() },
                    snrDbHz = best.cn0DbHz.takeIf { it.isFinite() && it > 0f }?.toDouble(),
                    usedInFix = signals.any { it.usedInFix },
                )
            }
            GsvMessage(constellation, entries)
        }

    /** Distinct (constellation, svid) counts: used, visible. */
    fun counts(sats: List<PlatformSatellite>): Pair<Int, Int> {
        val distinct = sats.groupBy { it.constellation to it.svid }
        return distinct.count { (_, s) -> s.any { it.usedInFix } } to distinct.size
    }
}
