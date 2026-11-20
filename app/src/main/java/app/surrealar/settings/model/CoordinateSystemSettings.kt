package app.surrealar.settings.model

/**
 * How coordinates are EXPRESSED (display and export). Stored coordinates are never changed: points
 * keep the receiver's latitude/longitude/ellipsoidal height plus the datum they were measured in
 * (Coordinate.crsEpsg), and are converted on the fly. Defaults reproduce the app's behaviour before
 * coordinate systems existed (WGS84 lat/lon, metres, receiver MSL height).
 *
 * All prefKeys are persisted — never rename.
 */
data class CoordinateSystemSettings(
    val horizontal: HorizontalSystem = HorizontalSystem.GEOGRAPHIC,
    /** Datum for Geographic/UTM output. State Plane is always NAD83(2011). */
    val datum: OutputDatum = OutputDatum.WGS84,
    val unit: LinearUnit = LinearUnit.METERS,
    val vertical: VerticalReference = VerticalReference.RECEIVER_MSL,
    /**
     * Datum the EXTERNAL receiver's output is in. A Reach on an RTK network that broadcasts NAD83(2011)
     * corrections (e.g. a state RTN) outputs NAD83(2011); a local base or PPP outputs WGS84/ITRF.
     * Internal device GNSS is always WGS84/ITRF.
     */
    val externalReceiverDatum: ReceiverDatum = ReceiverDatum.ITRF2014,
) {
    /** Datum the horizontal output is expressed in (State Plane forces NAD83(2011)). */
    val effectiveDatum: OutputDatum
        get() = if (horizontal.isStatePlane) OutputDatum.NAD83_2011 else datum
}

enum class HorizontalSystem(val prefKey: String, val label: String, val isStatePlane: Boolean = false) {
    GEOGRAPHIC("geographic", "Latitude / Longitude"),
    UTM("utm", "UTM (auto zone)"),
    SPCS_PA_NORTH("spcs83_pa_north", "State Plane PA North (3701)", isStatePlane = true),
    SPCS_PA_SOUTH("spcs83_pa_south", "State Plane PA South (3702)", isStatePlane = true);

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: GEOGRAPHIC }
}

enum class OutputDatum(val prefKey: String, val label: String) {
    WGS84("wgs84", "WGS84 (ITRF2014)"),
    NAD83_2011("nad83_2011", "NAD83(2011)");

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: WGS84 }
}

enum class LinearUnit(val prefKey: String, val label: String, val metersPerUnit: Double, val suffix: String) {
    METERS("m", "Meters", 1.0, "m"),
    /** 1200/3937 m — the legal unit for Pennsylvania SPCS83 coordinates. */
    US_SURVEY_FEET("ftus", "US survey feet", 1200.0 / 3937.0, "ftUS"),
    INTERNATIONAL_FEET("ft", "International feet", 0.3048, "ft");

    fun fromMeters(m: Double) = m / metersPerUnit

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: METERS }
}

enum class VerticalReference(val prefKey: String, val label: String, val shortLabel: String) {
    ELLIPSOIDAL("ellipsoidal", "Ellipsoidal height", "Ellip"),
    NAVD88_GEOID18("navd88_geoid18", "NAVD88 (GEOID18)", "NAVD88"),
    RECEIVER_MSL("receiver_msl", "Receiver MSL (as reported)", "MSL");

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: RECEIVER_MSL }
}

enum class ReceiverDatum(val prefKey: String, val label: String) {
    ITRF2014("itrf2014", "WGS84 / ITRF (local base, PPP)"),
    NAD83_2011("nad83_2011", "NAD83(2011) (RTK network)");

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: ITRF2014 }
}
