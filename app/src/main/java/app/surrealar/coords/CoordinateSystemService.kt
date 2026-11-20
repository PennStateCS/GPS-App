package app.surrealar.coords

import android.content.Context
import app.surrealar.domain.model.Coordinate
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.ReceiverDatum
import app.surrealar.util.DiagnosticsLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-side entry point for coordinate systems: loads the bundled GEOID18 grid once and converts
 * live fixes and stored coordinates into the configured system. UI code should go through here so
 * every screen agrees.
 */
@Singleton
class CoordinateSystemService @Inject constructor(@ApplicationContext context: Context) {

    private val geoid: Geoid18Grid? = runCatching {
        context.assets.open(Geoid18Grid.ASSET_PATH).use { Geoid18Grid(it.readBytes()) }
    }.onFailure { DiagnosticsLogger.e("Coords", "GEOID18 grid failed to load — NAVD88 heights unavailable", it) }
        .getOrNull()

    private val converter = CoordinateConverter { lat, lon -> geoid?.undulation(lat, lon) }

    /** Datum the receiver output is in: internal GNSS is always WGS84/ITRF; external per setting. */
    fun fixIsNad83(fix: Fix, s: CoordinateSystemSettings): Boolean =
        fix.provider != Provider.INTERNAL && s.externalReceiverDatum == ReceiverDatum.NAD83_2011

    /** EPSG code to store in Coordinate.crsEpsg for a capture from [provider]. */
    fun storedDatumEpsg(provider: Provider, s: CoordinateSystemSettings): Int =
        if (provider != Provider.INTERNAL && s.externalReceiverDatum == ReceiverDatum.NAD83_2011)
            StoredDatum.NAD83_2011_3D else StoredDatum.WGS84_3D

    fun convertFix(fix: Fix, s: CoordinateSystemSettings): ConvertedPosition =
        converter.convert(
            SourcePosition(
                latDeg = fix.latDeg, lonDeg = fix.lonDeg,
                ellipsoidalHeightM = fix.altEllipsoidalM, receiverMslM = fix.altMslM,
                isNad83 = fixIsNad83(fix, s),
                epochYear = Itrf2014ToNad83.decimalYear(fix.timeUtc.toEpochMilli()),
            ), s,
        )

    /**
     * Stored coordinate → configured system. `altitude` is ellipsoidal (antenna height already
     * removed) and `altitudeMsl` the receiver MSL height; `crsEpsg` says which datum they are in
     * (null/4326/4979 = WGS84). The capture time is the transformation epoch.
     */
    fun convertCoordinate(c: Coordinate, s: CoordinateSystemSettings): ConvertedPosition =
        converter.convert(
            SourcePosition(
                latDeg = c.latitude, lonDeg = c.longitude,
                ellipsoidalHeightM = c.altitude, receiverMslM = c.altitudeMsl,
                isNad83 = StoredDatum.isNad83(c.crsEpsg),
                epochYear = Itrf2014ToNad83.decimalYear(c.timestamp),
            ), s,
        )
}
