package io.github.pennstatecs.geokit.gnss.datum

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.gnss.model.Fix

/**
 * Datum a receiver's position output is expressed in.
 *
 * A receiver on a local base or PPP outputs ITRF2014 (treated as WGS84); a receiver on an RTK network
 * in the US usually outputs NAD83(2011), which sits ~1–1.5 m from WGS84.
 */
public enum class FixDatum {
    /** ITRF2014 / WGS84: no change is needed for a WGS84 consumer. */
    ITRF2014,

    /** NAD83(2011): converted to ITRF2014 / WGS84 with the inverse 14-parameter Helmert. */
    NAD83_2011,
}

/**
 * Normalises receiver output to WGS84 (ITRF2014) as it enters a GNSS feed.
 *
 * Map SDKs, ARCore Geospatial, Android mock locations and stored captures all work in WGS84, so a
 * feed converts NAD83(2011) output once and every consumer agrees. Converting back to NAD83(2011) for
 * display ([Itrf2014ToNad83.forward] at the same epoch) round-trips to < 0.1 mm, so NAD83 values shown
 * to the user still equal what the receiver output.
 *
 * Epoch: the decimal year of the fix's own [Fix.timeUtc].
 *
 * Heights: the ellipsoidal height is transformed with the position (null stays null). The receiver's
 * MSL height is an orthometric value from its own geoid model and is left as reported; the geoid
 * separation is recomputed so `altEllipsoidal = altMsl + N` still holds.
 */
public object FixDatumNormalizer {

    /**
     * Returns [fix] expressed in WGS84 (ITRF2014): the same instance when [datum] is
     * [FixDatum.ITRF2014], otherwise [nad83ToWgs84].
     */
    public fun toWgs84(fix: Fix, datum: FixDatum): Fix = when (datum) {
        FixDatum.ITRF2014 -> fix
        FixDatum.NAD83_2011 -> nad83ToWgs84(fix)
    }

    /** Converts a NAD83(2011) [fix] to WGS84 (ITRF2014) at the fix's epoch. */
    public fun nad83ToWgs84(fix: Fix): Fix {
        val epoch = Itrf2014ToNad83.decimalYear(fix.timeUtc.toEpochMilli())
        // Height barely affects the horizontal result (< 0.1 mm per 400 m), so 0 is fine when absent.
        val src = GeodeticPosition(fix.latDeg, fix.lonDeg, fix.altEllipsoidalM ?: 0.0)
        val w = Itrf2014ToNad83.inverse(src, epoch)
        val ellH = fix.altEllipsoidalM?.let { w.heightM }
        val msl = fix.altMslM
        val sep = if (ellH != null && msl != null) ellH - msl else fix.geoidSeparationM
        return fix.copy(latDeg = w.latDeg, lonDeg = w.lonDeg, altEllipsoidalM = ellH, geoidSeparationM = sep)
    }
}
