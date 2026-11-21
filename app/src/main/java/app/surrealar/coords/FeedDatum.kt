package app.surrealar.coords

import app.surrealar.geo.datum.GeodeticPosition
import app.surrealar.geo.datum.Itrf2014ToNad83

import app.surrealar.gnss.model.Fix

/**
 * Normalises receiver output to WGS84 (ITRF2014) as it enters the GNSS feed.
 *
 * An external receiver on RTK-network corrections outputs NAD83(2011), which sits ~1–1.5 m from
 * WGS84 across the US. Everything downstream — Google Maps, ARCore Geospatial, mock location,
 * stored captures — works in WGS84, so the feed converts once here and the whole app agrees. The
 * display layer converts back to NAD83(2011) when the user asks for it; that round trip
 * (inverse then forward Helmert at the same epoch) is lossless to < 0.1 mm, so NAD83 values shown
 * to the user still equal what the receiver output.
 *
 * Heights: the ellipsoidal height is transformed with the position. The receiver's MSL height is
 * an orthometric value from its own geoid model and is left as reported; the geoid separation is
 * recomputed so `altEllipsoidal = altMsl + N` still holds.
 */
object FeedDatum {

    fun nad83ToWgs84(fix: Fix): Fix {
        val epoch = Itrf2014ToNad83.decimalYear(fix.timeUtc.toEpochMilli())
        // Height barely affects the horizontal result (< 0.1 mm per 400 m), so 0 is fine when absent.
        val src = GeodeticPosition(fix.latDeg, fix.lonDeg, fix.altEllipsoidalM ?: 0.0)
        val w = Itrf2014ToNad83.inverse(src, epoch)
        val ellH = fix.altEllipsoidalM?.let { w.heightM }
        val sep = if (ellH != null && fix.altMslM != null) ellH - fix.altMslM else fix.geoidSeparationM
        return fix.copy(latDeg = w.latDeg, lonDeg = w.lonDeg, altEllipsoidalM = ellH, geoidSeparationM = sep)
    }
}
