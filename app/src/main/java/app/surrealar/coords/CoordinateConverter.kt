package app.surrealar.coords

import io.github.pennstatecs.geokit.convert.Epsg
import io.github.pennstatecs.geokit.convert.GeodeticDatum
import io.github.pennstatecs.geokit.convert.GeoidLookup
import io.github.pennstatecs.geokit.convert.HeightUnavailableReason
import io.github.pennstatecs.geokit.convert.LengthUnit
import io.github.pennstatecs.geokit.convert.OutputSystem
import io.github.pennstatecs.geokit.convert.TargetSystem
import io.github.pennstatecs.geokit.geoid.GeoidModel
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GeoidResult
import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import java.util.Locale
import io.github.pennstatecs.geokit.convert.CoordinateConverter as GeoConverter
import io.github.pennstatecs.geokit.convert.SourcePosition as GeoSourcePosition

/** EPSG codes recorded in Coordinate.crsEpsg for the datum of stored lat/lon/height. */
object StoredDatum {
    const val WGS84_3D = Epsg.WGS84_3D
    const val WGS84_2D = Epsg.WGS84_2D
    const val NAD83_2011_3D = Epsg.NAD83_2011_3D
    const val NAD83_2011_2D = Epsg.NAD83_2011_2D

    const val ITRF2020_3D = Epsg.ITRF2020_3D
    const val ITRF2020_2D = Epsg.ITRF2020_2D

    fun isNad83(epsg: Int?) = Epsg.isNad83_2011(epsg)

    /** True for a point stored in ITRF2020 (EPSG 9989/9990). Captures never store it today (imports could). */
    fun isItrf2020(epsg: Int?) = epsg == ITRF2020_3D || epsg == ITRF2020_2D

    /**
     * Datum of stored lat/lon/height for conversions: 6318/6319 → NAD83(2011), 9989/9990 → ITRF2020,
     * everything else (4326/4979, null, unknown codes) → WGS84 (ITRF2014), the historical default.
     */
    fun geodeticDatum(epsg: Int?): GeodeticDatum = GeodeticDatum.fromEpsg(epsg) ?: GeodeticDatum.WGS84_ITRF2014

    /** Short label of the stored datum for display: "NAD83", "ITRF2020" or "WGS84". */
    fun label(epsg: Int?): String = when (geodeticDatum(epsg)) {
        GeodeticDatum.NAD83_2011 -> "NAD83"
        GeodeticDatum.ITRF2020 -> "ITRF2020"
        GeodeticDatum.WGS84_ITRF2014 -> "WGS84"
    }

    /**
     * A stored point as WGS84 (ITRF2014) for placement on Google Maps / ARCore Geospatial, which work
     * in WGS84. Points already in WGS84 come back unchanged (the same instance); NAD83(2011) points
     * (imports, or captures from before the feed normalised receiver output) are transformed at
     * their capture epoch. Use for plotting only, not for re-saving over the stored record.
     *
     * ITRF2020 points (EPSG 9989/9990) are plotted as they are, like WGS84: ITRF2020 and ITRF2014 differ by
     * a few millimetres (EPSG Helmert: ~1.4 mm translations, 0.42 ppb), far below Maps/ARCore accuracy.
     * Conversions ([SourcePosition.datum]) apply the exact ITRF2020 → ITRF2014 step instead.
     */
    fun toWgs84(c: app.surrealar.domain.model.Coordinate): app.surrealar.domain.model.Coordinate {
        if (!isNad83(c.crsEpsg)) return c
        val w = Itrf2014ToNad83.inverse(GeodeticPosition(c.latitude, c.longitude, c.altitude),
            Itrf2014ToNad83.decimalYear(c.timestamp))
        return c.copy(latitude = w.latDeg, longitude = w.lonDeg, altitude = w.heightM,
            crsEpsg = if (c.crsEpsg == NAD83_2011_2D) WGS84_2D else WGS84_3D)
    }

    /** [toWgs84] for the Room entity (AR loads entities directly). */
    fun toWgs84(e: app.surrealar.data.local.entity.CoordinateEntity): app.surrealar.data.local.entity.CoordinateEntity {
        if (!isNad83(e.crsEpsg)) return e
        val w = Itrf2014ToNad83.inverse(GeodeticPosition(e.latitude, e.longitude, e.altitude),
            Itrf2014ToNad83.decimalYear(e.timestamp))
        return e.copy(latitude = w.latDeg, longitude = w.lonDeg, altitude = w.heightM,
            crsEpsg = if (e.crsEpsg == NAD83_2011_2D) WGS84_2D else WGS84_3D)
    }
}

/** A measured position as stored/received, plus what is needed to convert it correctly. */
data class SourcePosition(
    val latDeg: Double,
    val lonDeg: Double,
    /** Ellipsoidal height in the source datum, metres (null = unknown). */
    val ellipsoidalHeightM: Double?,
    /** Receiver-reported MSL height, metres (for VerticalReference.RECEIVER_MSL). */
    val receiverMslM: Double?,
    /** True if lat/lon/height are NAD83(2011); false = WGS84/ITRF2014 (or ITRF2020, see [datum]). */
    val isNad83: Boolean,
    /** Observation epoch as a decimal year (ITRF ⇄ NAD83 is time-dependent). */
    val epochYear: Double,
    /**
     * Datum of lat/lon/height. Defaults from [isNad83] (NAD83(2011) or WGS84 (ITRF2014)); stored points in
     * ITRF2020 pass [GeodeticDatum.ITRF2020] so they are transformed, never read as WGS84.
     */
    val datum: GeodeticDatum = if (isNad83) GeodeticDatum.NAD83_2011 else GeodeticDatum.WGS84_ITRF2014,
) {
    init {
        require(isNad83 == (datum == GeodeticDatum.NAD83_2011)) { "isNad83=$isNad83 contradicts datum $datum" }
    }
}

/** A position expressed in the user's chosen coordinate system. Lengths are in [unit]. */
data class ConvertedPosition(
    val horizontal: HorizontalSystem,
    val datum: OutputDatum,
    val unit: LinearUnit,
    /** Geographic output (degrees, in [datum]); also filled for grid systems for reference. */
    val latDeg: Double,
    val lonDeg: Double,
    /** Grid coordinates in [unit]; null for GEOGRAPHIC. */
    val easting: Double?,
    val northing: Double?,
    /** e.g. "SPCS 3701", "UTM 18N"; null for GEOGRAPHIC. */
    val zoneLabel: String?,
    val vertical: VerticalReference,
    /** Height in [unit] for [vertical]; null when unavailable (no height, outside GEOID18 grid…). */
    val height: Double?,
    /** Why [height] is null, when it is. */
    val heightUnavailableReason: String? = null,
    /** State Plane only: the position lies outside the selected zone's area of use (wrong zone?). */
    val outsideZone: Boolean = false,
    /**
     * Short height-datum label: "NAVD88" (or PRVD02/VIVD09 from GEOID18 in PR/VI), "EGM2008", "EGM96",
     * "h", "H rcvr".
     */
    val verticalLabel: String = vertical.shortLabel,
    /**
     * Coordinate epoch (decimal year) of an ITRF2020 output: the frame is time-dependent, so the
     * position is only meaningful with it. Null for the other datums (their labels are unchanged).
     */
    val epochYear: Double? = null,
    /**
     * Provenance of a beta model height, null otherwise (legacy outputs unchanged). GEOID2022:
     * "GEOID2022 (beta beta_v0a) epoch 2026.886" (model, NGS dataset version, epoch of N and of the ITRF2020
     * position); shown after [verticalLabel] in the system summary, details and the CSV System column.
     */
    val heightModelNote: String? = null,
    /**
     * True when [height] is null only because the height model's data is still loading (GEOID2022 / EGM packs
     * on the main thread, or the GEOID18 grid at start-up): screens show a loading state, never a stale or
     * substituted value, and refresh when [CoordinateSystemService.heightDataVersion] changes.
     */
    val heightLoading: Boolean = false,
)

/**
 * Converts stored/received positions into the configured coordinate system. A thin adapter over
 * geokit-core's [io.github.pennstatecs.geokit.convert.CoordinateConverter]: it maps the app's
 * settings onto the library's neutral types (CoordinateSettingsMapping.kt) and the library result
 * back onto [ConvertedPosition]. Pure (no Android): the GEOID18 lookup is injected so it can be
 * unit-tested and loaded in the background on device. Output for the pre-Phase-2 settings is pinned by
 * CoordinateConverterGoldenTest.
 *
 * Heights from the global geoid models (H EGM2008 / H EGM96) are computed here, never by the engine's
 * GEOID18 path: H = h - N(model), where h and the lookup position are the source expressed in
 * WGS84 (ITRF2014), i.e. the app's stored WGS84-as-ITRF2014 coordinates as they are (NAD83(2011)
 * sources are first transformed to it at their epoch). EGM2008/EGM96 are defined on WGS84; the
 * ITRF2014/WGS84 realisation difference is centimetres, which changes N by far less than a millimetre.
 * The height is that one subtraction, whatever the output frame (one geoid correction per height). A
 * model without data at the point gives no height with a reason, never a value from another model.
 *
 * GEOID2022 heights (BETA, only with the NSRS 2022 preview ON: [VerticalReference.GEOID2022]) are computed
 * here as well, never by the engine: the source position and ellipsoidal height are transformed to ITRF2020
 * at the source's coordinate epoch through the verified [frames] registry (identity for an ITRF2020 source),
 * N is GEOID2022's time-dependent undulation at that ITRF2020 position and epoch (NGS: SGEOID2022 at 2020.0
 * + DGEOID2022 * dt), and H = h(ITRF2020) - N: one subtraction. No GEOID2022 data at the point gives no
 * height with a reason, never EGM or GEOID18. A beta height saved while the preview is OFF is not used
 * ([CoordinateSystemSettings.forConversion]: the default receiver height is shown instead).
 *
 * @param geoidN GEOID18 undulation at a NAD83(2011) position (unchanged path).
 * @param globalGeoid the shared EGM2008/EGM96 instances (default [AppGeoidModels]); tests inject models.
 * @param geoid2022 the GEOID2022 (beta) lookup, resolved on first use (default [AppGeoidModels.geoid2022]).
 * @param frames verified frame transformations for the ITRF2020 step of GEOID2022 heights.
 */
class CoordinateConverter(
    geoidN: (latDeg: Double, lonDeg: Double) -> Double?,
    private val globalGeoid: (GeoidModelId) -> GeoidModel?,
    geoid2022: () -> TimeDependentGeoid?,
    private val frames: FrameTransformationRegistry,
) {
    private val geoid2022Lookup: TimeDependentGeoid? by lazy(geoid2022)

    /**
     * GEOID18 lookup, injected EGM models, the shared GEOID2022 lookup and the default frame registry
     * (the Phase 2 signature, so `CoordinateConverter(geoidN) { id -> … }` keeps compiling).
     */
    constructor(geoidN: (latDeg: Double, lonDeg: Double) -> Double?, globalGeoid: (GeoidModelId) -> GeoidModel?) :
        this(geoidN, globalGeoid, { AppGeoidModels.geoid2022 }, FrameTransformationRegistry.DEFAULT)


    /**
     * GEOID18 lookup plus the shared [AppGeoidModels]. Kept as its own constructor so the existing
     * `CoordinateConverter { lat, lon -> … }` call sites keep compiling unchanged.
     */
    constructor(geoidN: (latDeg: Double, lonDeg: Double) -> Double?) : this(geoidN, AppGeoidModels::model)

    private val engine = GeoConverter(GeoidLookup { lat, lon -> geoidN(lat, lon) })

    fun convert(src: SourcePosition, settings: CoordinateSystemSettings): ConvertedPosition {
        // A beta height saved while the preview is OFF is not used (identity for every other setting).
        val s = settings.forConversion()
        val geoSrc = src.toGeoSource()
        val r = engine.convert(geoSrc, s.toOutputSystem())
        val unit = s.unit
        val base = ConvertedPosition(
            horizontal = s.horizontal, datum = s.effectiveDatum, unit = unit,
            latDeg = r.latDeg, lonDeg = r.lonDeg,
            easting = r.eastingM?.let { unit.fromMeters(it) }, northing = r.northingM?.let { unit.fromMeters(it) },
            zoneLabel = r.zoneLabel,
            vertical = s.vertical, height = r.heightM?.let { unit.fromMeters(it) },
            heightUnavailableReason = r.heightUnavailableReason?.description,
            outsideZone = r.outsideZone,
            verticalLabel = r.verticalDatumName ?: s.vertical.shortLabel,
        )
        val epoch = if (s.effectiveDatum == OutputDatum.ITRF2020) src.epochYear else null
        if (s.vertical == VerticalReference.GEOID2022) {
            val g = geoid2022Height(src)
            return base.copy(
                height = g.heightM?.let { unit.fromMeters(it) },
                heightUnavailableReason = g.reason,
                verticalLabel = Geoid2022Labels.VERTICAL_LABEL,
                epochYear = epoch,
                heightModelNote = Geoid2022Labels.note(src.epochYear),
                heightLoading = g.loading,
            )
        }
        val model = s.vertical.globalGeoidModel()
            ?: return if (epoch == null) base else base.copy(epochYear = epoch)
        val g = globalHeight(geoSrc, model)
        return base.copy(
            height = g.heightM?.let { unit.fromMeters(it) },
            heightUnavailableReason = g.reason,
            verticalLabel = g.label,
            epochYear = epoch,
            heightLoading = g.loading,
        )
    }

    /**
     * A model height and its parts: H ([heightM]), the ellipsoidal height h it was computed from
     * ([ellipsoidalM], in the model's frame) and N ([undulationM]); [loading] = unavailable only because
     * the model's data is still loading.
     */
    private class GlobalHeight(
        val heightM: Double?, val reason: String?, val label: String,
        val ellipsoidalM: Double? = null, val undulationM: Double? = null, val loading: Boolean = false,
    )

    private fun unavailable(id: GeoidModelId, r: GeoidResult.Unavailable, label: String, ellipsoidalM: Double?) =
        GlobalHeight(null, AppGeoidModels.unavailableText(id, r.reason), label, ellipsoidalM,
            loading = r.reason == io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason.DATA_LOADING)

    /** H = h - N([id]) at the WGS84 (ITRF2014) position, or the reason it is unavailable. */
    private fun globalHeight(geoSrc: GeoSourcePosition, id: GeoidModelId): GlobalHeight {
        val label = id.token   // "EGM2008" / "EGM96": the vertical datum of the height, also when unavailable
        if (geoSrc.ellipsoidalHeightM == null) {
            return GlobalHeight(null, HeightUnavailableReason.NO_ELLIPSOIDAL_HEIGHT.description, label)
        }
        val model = globalGeoid(id) ?: return GlobalHeight(null, "${id.token} not available in this app", label)
        val w = engine.convert(geoSrc, WGS84_ELLIPSOIDAL)
        val h = checkNotNull(w.heightM) { "ellipsoidal height lost in the WGS84 step" }
        return when (val n = model.undulation(w.latDeg, w.lonDeg)) {
            is GeoidResult.Value -> {
                check(n.model == id) { "asked ${id.token}, got ${n.model.token}" }   // no silent substitution
                GlobalHeight(n.orthometricHeightM(h), null, n.verticalDatum, h, n.undulationM)
            }
            is GeoidResult.Unavailable -> unavailable(id, n, label, h)
        }
    }

    /**
     * GEOID2022 (beta): H = h(ITRF2020) - N(GEOID2022) with the position and N both at the source's
     * coordinate epoch, or the reason it is unavailable.
     */
    private fun geoid2022Height(src: SourcePosition): GlobalHeight {
        val label = Geoid2022Labels.VERTICAL_LABEL
        val h = src.ellipsoidalHeightM
            ?: return GlobalHeight(null, HeightUnavailableReason.NO_ELLIPSOIDAL_HEIGHT.description, label)
        val model = geoid2022Lookup ?: return GlobalHeight(null, "GEOID2022 (beta) not available in this app", label)
        val epoch = CoordinateEpoch(src.epochYear)
        val itrf2020 = when (val t = frames.find(src.datum.frame, ReferenceFrame.ITRF2020, epoch)) {
            is FrameTransformationLookup.Available -> t.apply(GeodeticPosition(src.latDeg, src.lonDeg, h))
            is FrameTransformationLookup.Unavailable ->
                return GlobalHeight(null, "no verified transformation to ITRF2020 (${t.explanation})", label)
        }
        return when (val n = model.undulationAt(itrf2020.latDeg, itrf2020.lonDeg, src.epochYear)) {
            is GeoidResult.Value -> {
                check(n.model == GeoidModelId.GEOID2022) { "asked GEOID2022, got ${n.model.token}" }   // no substitution
                GlobalHeight(itrf2020.heightM - n.undulationM, null, label, itrf2020.heightM, n.undulationM)
            }
            is GeoidResult.Unavailable -> unavailable(GeoidModelId.GEOID2022, n, label, itrf2020.heightM)
        }
    }

    /**
     * The parts of the height in [p] (= [convert] of [src] in [settings]) for the detail screens: h (in the
     * frame the height model uses), H, N (when the model gives one), the geoid model and data version, the
     * vertical reference, the coordinate epoch and the beta status. Display only: [convert] (and so every
     * stored, exported or golden-tested output) is unchanged. H is [p]'s height itself; h and N are computed
     * on the same path as H (GEOID18: h in NAD83(2011), N = h − H; EGM: h in WGS84 (ITRF2014) and N from the
     * model; GEOID2022 beta: h in ITRF2020 at the epoch and N(epoch)). Receiver heights have no N here: the
     * receiver's model is unknown.
     */
    fun elevation(src: SourcePosition, settings: CoordinateSystemSettings, p: ConvertedPosition = convert(src, settings)): ElevationDetail {
        val s = settings.forConversion()
        val unit = s.unit
        fun u(m: Double?) = m?.let { unit.fromMeters(it) }
        val geoSrc = src.toGeoSource()
        val common = ElevationDetail(
            unit = unit, vertical = s.vertical, ellipsoidalHeight = null, ellipsoidalFrame = null,
            orthometricHeight = p.height, undulation = null, geoidModel = null, geoidVersion = null,
            verticalReference = p.verticalLabel, epochYear = p.epochYear, beta = s.vertical.isBeta,
            loading = p.heightLoading, unavailableReason = p.heightUnavailableReason,
        )
        return when (s.vertical) {
            VerticalReference.ELLIPSOIDAL -> common.copy(
                ellipsoidalHeight = p.height, ellipsoidalFrame = CoordinateFormat.datumLabel(p), orthometricHeight = null,
                verticalReference = ElevationDetail.ELLIPSOID,
            )
            VerticalReference.RECEIVER_MSL -> common.copy(
                ellipsoidalHeight = u(src.ellipsoidalHeightM), ellipsoidalFrame = src.datum.frame.displayName,
                geoidModel = ElevationDetail.RECEIVER_MODEL, verticalReference = ElevationDetail.RECEIVER_MODEL,
            )
            VerticalReference.NAVD88_GEOID18 -> {
                val info = io.github.pennstatecs.geokit.geoid.Geoid18Model.INFO
                val hM = src.ellipsoidalHeightM?.let { engine.convert(geoSrc, NAD83_ELLIPSOIDAL).heightM }
                val bigH = p.height?.let { it * unit.metersPerUnit }
                common.copy(
                    ellipsoidalHeight = u(hM), ellipsoidalFrame = info.horizontalFrame,
                    undulation = if (hM != null && bigH != null) u(hM - bigH) else null,
                    geoidModel = info.label, geoidVersion = ElevationDetail.shortVersion(info),
                )
            }
            VerticalReference.EGM2008, VerticalReference.EGM96 -> {
                val id = checkNotNull(s.vertical.globalGeoidModel())
                val g = if (geoSrc.ellipsoidalHeightM == null) null else globalHeight(geoSrc, id)
                val info = if (id == GeoidModelId.EGM2008) io.github.pennstatecs.geokit.geoid.GlobalGeoidModels.EGM2008_INFO
                    else io.github.pennstatecs.geokit.geoid.GlobalGeoidModels.EGM96_INFO
                common.copy(
                    ellipsoidalHeight = u(g?.ellipsoidalM), ellipsoidalFrame = ReferenceFrame.WGS84_AS_ITRF2014.displayName,
                    undulation = u(g?.undulationM), geoidModel = info.label, geoidVersion = ElevationDetail.shortVersion(info),
                )
            }
            VerticalReference.GEOID2022 -> {
                val g = geoid2022Height(src)
                val info = io.github.pennstatecs.geokit.geoid.Geoid2022Model.INFO
                common.copy(
                    ellipsoidalHeight = u(g.ellipsoidalM),
                    ellipsoidalFrame = String.format(Locale.US, "ITRF2020 epoch %.3f", src.epochYear),
                    undulation = u(g.undulationM), geoidModel = info.label, geoidVersion = ElevationDetail.shortVersion(info),
                    verticalReference = io.github.pennstatecs.geokit.geoid.Geoid2022Model.VERTICAL_DATUM,
                    epochYear = src.epochYear,
                )
            }
        }
    }

    private companion object {
        /** The source as WGS84 (ITRF2014) lat/lon/ellipsoidal height, metres (identity for WGS84 sources). */
        val WGS84_ELLIPSOIDAL = OutputSystem(
            horizontal = TargetSystem.Geographic, datum = GeodeticDatum.WGS84_ITRF2014,
            unit = LengthUnit.METRE, vertical = io.github.pennstatecs.geokit.convert.VerticalReference.ELLIPSOIDAL,
        )

        /** The source as NAD83(2011) lat/lon/ellipsoidal height, metres (the GEOID18 frame). */
        val NAD83_ELLIPSOIDAL = WGS84_ELLIPSOIDAL.copy(datum = GeodeticDatum.NAD83_2011)
    }
}

/** The app's source position as geokit-core's. */
fun SourcePosition.toGeoSource(): GeoSourcePosition = GeoSourcePosition(
    latDeg = latDeg, lonDeg = lonDeg,
    ellipsoidalHeightM = ellipsoidalHeightM, receiverMslHeightM = receiverMslM,
    datum = datum,
    epochYear = epochYear,
)

/** Display strings for [ConvertedPosition] (shared by toolbar, detail screen, labels, export). */
object CoordinateFormat {
    private fun grouped(v: Double, decimals: Int) = String.format(Locale.US, "%,.${decimals}f", v)

    /** Decimals for grid values / heights: mm-level in metres, 0.01 in feet. */
    fun linearDecimals(unit: LinearUnit) = if (unit == LinearUnit.METERS) 3 else 2

    /** One line: "N 435,037.47 · E 2,442,754.19 ftUS" or "41.34769028, -76.02259178". */
    fun horizontal(p: ConvertedPosition, latLonDecimals: Int = 8): String =
        if (p.easting == null || p.northing == null)
            String.format(Locale.US, "%.${latLonDecimals}f, %.${latLonDecimals}f", p.latDeg, p.lonDeg)
        else {
            val d = linearDecimals(p.unit)
            "N ${grouped(p.northing, d)} · E ${grouped(p.easting, d)} ${p.unit.suffix}"
        }

    /** "1,286.74 ftUS NAVD88", or "—" with no height. */
    fun height(p: ConvertedPosition): String =
        p.height?.let { "${grouped(it, linearDecimals(p.unit))} ${p.unit.suffix} ${p.verticalLabel}" } ?: "—"

    /**
     * [height], except that a height whose model data is still loading reads "GEOID2022 data is loading"
     * (the reason) instead of "—": a clear loading state, never a stale or substituted value.
     */
    fun heightOrLoading(p: ConvertedPosition): String =
        if (p.height == null && p.heightLoading) p.heightUnavailableReason ?: "height data is loading" else height(p)

    /**
     * The output frame for display: the datum label ("WGS84 (ITRF2014)", "NAD83(2011)"), or for
     * ITRF2020 the frame with its coordinate epoch, "ITRF2020 epoch 2026.886".
     */
    fun datumLabel(p: ConvertedPosition): String {
        val epoch = p.epochYear
        return if (p.datum == OutputDatum.ITRF2020 && epoch != null)
            String.format(Locale.US, "ITRF2020 epoch %.3f", epoch)
        else p.datum.label
    }

    /**
     * Short system description: "NAD83(2011) · SPCS 3701 · ftUS · NAVD88" (+ an outside-zone warning). A beta
     * height adds its provenance after the vertical label:
     * "WGS84 (ITRF2014) · m · NAPGD2022 beta · GEOID2022 (beta beta_v0a) epoch 2026.886".
     */
    fun systemSummary(p: ConvertedPosition): String =
        listOfNotNull(datumLabel(p), p.zoneLabel, p.unit.suffix, p.verticalLabel, p.heightModelNote,
            if (p.outsideZone) "outside zone" else null).joinToString(" · ")

    /** Copyable text: "N 435037.47, E 2442754.19 ftUS (NAD83(2011) SPCS 3701)" or "lat, lon". No grouping commas. */
    fun copyText(p: ConvertedPosition): String =
        if (p.easting == null || p.northing == null)
            String.format(Locale.US, "%.8f, %.8f", p.latDeg, p.lonDeg)
        else {
            val d = linearDecimals(p.unit)
            String.format(Locale.US, "N %.${d}f, E %.${d}f %s (%s %s)", p.northing, p.easting, p.unit.suffix,
                datumLabel(p), p.zoneLabel.orEmpty()).trimEnd()
        }

    /** A length in [unit] with a fixed precision: "12.346 m", "40.50 ftUS". */
    fun length(meters: Double, unit: LinearUnit, decimals: Int = linearDecimals(unit)): String =
        "${grouped(unit.fromMeters(meters), decimals)} ${unit.suffix}"

    /** Signed length ("+0.42 ftUS", "−1.250 m") for stakeout deltas. */
    fun delta(meters: Double, unit: LinearUnit, decimals: Int = linearDecimals(unit)): String {
        val v = unit.fromMeters(meters)
        val s = String.format(Locale.US, "%.${decimals}f", kotlin.math.abs(v))
        return (if (v < 0 && s.any { it in '1'..'9' }) "−" else "+") + s + " " + unit.suffix
    }

    /**
     * A distance with precision that falls off with size (walking/stakeout readouts). Metres:
     * "0.42 m", "4.2 m", "42 m", "1.2 km". Feet: "1.37 ftUS", "13.7 ftUS", "137 ftUS", "1.20 mi"
     * (statute mile beyond 5,280 ft).
     */
    fun distance(meters: Double, unit: LinearUnit): String {
        if (unit == LinearUnit.METERS) return when {
            meters < 1.0 -> String.format(Locale.US, "%.2f m", meters)
            meters < 10.0 -> String.format(Locale.US, "%.1f m", meters)
            meters < 1000.0 -> String.format(Locale.US, "%.0f m", meters)
            else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
        val ft = unit.fromMeters(meters)
        return when {
            ft < 10.0 -> String.format(Locale.US, "%.2f %s", ft, unit.suffix)
            ft < 100.0 -> String.format(Locale.US, "%.1f %s", ft, unit.suffix)
            ft < 5280.0 -> String.format(Locale.US, "%,.0f %s", ft, unit.suffix)
            else -> String.format(Locale.US, "%.2f mi", meters / 1609.344)
        }
    }
}
