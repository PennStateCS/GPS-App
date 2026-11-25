package app.surrealar.settings.model

import io.github.pennstatecs.geokit.status.ProductStatus

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
    /**
     * SPCS83 zone used when [horizontal] is STATE_PLANE: EPSG code of the zone's NAD83(2011) metre CRS
     * (see io.github.pennstatecs.geokit.projection.SpcsZones). Default Pennsylvania North (3701).
     */
    val spcsZoneEpsg: Int = DEFAULT_SPCS_ZONE_EPSG,
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
    /**
     * "Modernized NSRS 2022 (beta preview)" (Phase 3), default OFF. When ON, the beta options appear
     * (today: [VerticalReference.GEOID2022]); when OFF the app behaves exactly as before. NGS beta
     * products are preliminary and never authoritative, so nothing beta is ever a default.
     */
    val nsrs2022Preview: Boolean = false,
) {
    /**
     * The height reference actually used for display and export. A beta choice saved while the preview
     * is OFF is not used (the stored value is kept, never rewritten): the existing default
     * ([VerticalReference.RECEIVER_MSL]) is shown instead, with a note ([betaVerticalSuspended]).
     */
    val effectiveVertical: VerticalReference
        get() = if (vertical.isBeta && !nsrs2022Preview) VerticalReference.RECEIVER_MSL else vertical

    /** True when the saved height reference is a beta option that is not used because the preview is OFF. */
    val betaVerticalSuspended: Boolean
        get() = vertical.isBeta && !nsrs2022Preview

    /** These settings as used for conversions: [vertical] replaced by [effectiveVertical]. */
    fun forConversion(): CoordinateSystemSettings =
        if (betaVerticalSuspended) copy(vertical = effectiveVertical) else this

    /** Height references offered in the dropdown: beta ones only with the preview ON. */
    val selectableVerticals: List<VerticalReference>
        get() = VerticalReference.entries.filter { nsrs2022Preview || !it.isBeta }

    /** Datum the horizontal output is expressed in (State Plane forces NAD83(2011)). */
    val effectiveDatum: OutputDatum
        get() = if (horizontal.isStatePlane) OutputDatum.NAD83_2011 else datum

    /** Persisted form of the horizontal system: "geographic", "utm", or "spcs83:<zone EPSG>". */
    val horizontalPrefValue: String
        get() = if (horizontal == HorizontalSystem.STATE_PLANE) "$SPCS_PREFIX$spcsZoneEpsg" else horizontal.prefKey

    companion object {
        const val DEFAULT_SPCS_ZONE_EPSG = 6562   // SPCS83 Pennsylvania North (3701)
        private const val SPCS_PREFIX = "spcs83:"

        /** Inverse of [horizontalPrefValue]; also reads the earlier PA-only values. */
        fun parseHorizontal(v: String?): Pair<HorizontalSystem, Int> = when {
            v == null -> HorizontalSystem.GEOGRAPHIC to DEFAULT_SPCS_ZONE_EPSG
            v.startsWith(SPCS_PREFIX) ->
                HorizontalSystem.STATE_PLANE to (v.removePrefix(SPCS_PREFIX).toIntOrNull() ?: DEFAULT_SPCS_ZONE_EPSG)
            v == "spcs83_pa_north" -> HorizontalSystem.STATE_PLANE to 6562
            v == "spcs83_pa_south" -> HorizontalSystem.STATE_PLANE to 6564
            else -> HorizontalSystem.fromPrefKey(v) to DEFAULT_SPCS_ZONE_EPSG
        }
    }
}

enum class HorizontalSystem(val prefKey: String, val label: String, val isStatePlane: Boolean = false) {
    GEOGRAPHIC("geographic", "Latitude / Longitude"),
    UTM("utm", "UTM (auto zone)"),
    STATE_PLANE("spcs83", "State Plane (SPCS83)", isStatePlane = true);

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: GEOGRAPHIC }
}

/**
 * Output frame for Geographic/UTM. [ITRF2020] (added in Phase 2) is the 2020 frame **at the
 * coordinate epoch** of each position (no time propagation): stored points use their recorded
 * coordinate epoch, or the capture time when none was recorded; live fixes use the fix time. It is
 * reached from the stored WGS84 (ITRF2014) coordinates by the verified EPSG ITRF2014 → ITRF2020
 * transformation (geokit FrameTransformationRegistry). New entries are only ever appended.
 */
enum class OutputDatum(val prefKey: String, val label: String) {
    WGS84("wgs84", "WGS84 (ITRF2014)"),
    NAD83_2011("nad83_2011", "NAD83(2011)"),
    ITRF2020("itrf2020", "ITRF2020");

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: WGS84 }
}

enum class LinearUnit(val prefKey: String, val label: String, val metersPerUnit: Double, val suffix: String) {
    METERS("m", "Meters", 1.0, "m"),
    /** 1200/3937 m — the legal SPCS83 unit in Pennsylvania and many other states. */
    US_SURVEY_FEET("ftus", "US survey feet", 1200.0 / 3937.0, "ftUS"),
    INTERNATIONAL_FEET("ft", "International feet", 0.3048, "ft");

    fun fromMeters(m: Double) = m / metersPerUnit

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: METERS }
}

/**
 * Which height to show. Terminology follows survey practice: h = ellipsoidal height, H = orthometric
 * height, N = geoid undulation, H = h − N, and an orthometric height names its geoid model. The
 * receiver reports H with its own geoid model, which NMEA does not identify, so [RECEIVER_MSL] is
 * labelled "model unknown" (the constant name and prefKey keep the historical "MSL" wording because
 * they are persisted). Live heights are at the antenna phase centre; saved points are at the mark.
 */
enum class VerticalReference(
    val prefKey: String,
    val label: String,
    val shortLabel: String,
    /**
     * How far the option can be trusted (geokit [ProductStatus]): [ProductStatus.VERIFIED_BETA] options are
     * offered only with the NSRS 2022 beta preview ON and are always labelled beta. Nothing
     * [ProductStatus.UNAVAILABLE] is ever an entry here.
     */
    val status: ProductStatus = ProductStatus.VERIFIED_LEGACY,
) {
    ELLIPSOIDAL("ellipsoidal", "Ellipsoidal height (h)", "h"),
    NAVD88_GEOID18("navd88_geoid18", "Orthometric height H (NAVD88, GEOID18)", "H GEOID18"),
    RECEIVER_MSL("receiver_msl", "Orthometric height H (receiver geoid, model unknown)", "H rcvr"),

    /**
     * H = h − N(EGM2008), h the WGS84 (ITRF2014) ellipsoidal height. The app bundles EGM2008 for US
     * regions only; elsewhere the height is unavailable (never replaced by EGM96).
     */
    EGM2008("egm2008", "Orthometric height H (EGM2008)", "H EGM2008"),

    /** H = h − N(EGM96): the coarse global model, offered only as an explicitly labelled fallback. */
    EGM96("egm96", "Orthometric height H (EGM96, fallback)", "H EGM96"),

    /**
     * BETA (Phase 3, NSRS 2022 preview only): H = h(ITRF2020) − N(GEOID2022), NAPGD2022. The position and
     * ellipsoidal height are first transformed to ITRF2020 at the coordinate epoch through geokit's verified
     * frame registry; N is NGS's time-dependent GEOID2022 (SGEOID2022 + DGEOID2022·Δt) at that same epoch.
     * NGS dataset beta_v0a: preliminary, never a default, not for authoritative survey results. Outside
     * the bundled GEOID2022 data the height is unavailable (never another model).
     */
    GEOID2022("geoid2022_beta", "Orthometric height H (NAPGD2022, GEOID2022 beta)", "H GEOID2022 beta",
        ProductStatus.VERIFIED_BETA);

    /** True for an NGS beta option (shown only with the beta preview ON). */
    val isBeta: Boolean get() = status == ProductStatus.VERIFIED_BETA

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: RECEIVER_MSL }
}

enum class ReceiverDatum(val prefKey: String, val label: String) {
    ITRF2014("itrf2014", "WGS84 / ITRF (local base, PPP)"),
    NAD83_2011("nad83_2011", "NAD83(2011) (RTK network)");

    companion object { fun fromPrefKey(k: String?) = entries.firstOrNull { it.prefKey == k } ?: ITRF2014 }
}
