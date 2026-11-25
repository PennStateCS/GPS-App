package app.surrealar.coords

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import kotlinx.coroutines.launch

import android.content.Context
import app.surrealar.domain.model.Coordinate
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.ReceiverDatum
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Android-side entry point for coordinate systems: loads the bundled GEOID18 grid once and converts
 * live fixes and stored coordinates into the configured system. UI code should go through here so
 * every screen agrees.
 */
@Singleton
class CoordinateSystemService @Inject constructor(
    // Unused since GEOID18 moved from assets to geokit-core's classpath; kept so the injection graph is unchanged.
    @Suppress("UNUSED_PARAMETER") @ApplicationContext context: Context,
    settingsRepository: app.surrealar.domain.repository.CoordinateDisplaySettingsRepository,
    appScope: kotlinx.coroutines.CoroutineScope,
) {
    /**
     * Current Settings → Coordinates choice, kept hot so any screen can format synchronously
     * (dialogs, sheets, adapters). Screens that must re-render on change collect [settingsFlow].
     */
    val settingsFlow: kotlinx.coroutines.flow.StateFlow<CoordinateSystemSettings> =
        settingsRepository.coordinateSystemSettings.stateIn(
            appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, CoordinateSystemSettings())
    val settings: CoordinateSystemSettings get() = settingsFlow.value
    val unit: app.surrealar.settings.model.LinearUnit get() = settings.unit

    /**
     * The GEOID18 grid (5.2 MB), read on Dispatchers.IO in the background so cold start never blocks the
     * main thread on it. Null until loaded (or if loading failed): heights that need the geoid read as
     * unavailable for that moment, exactly as when the grid can't be loaded. Never waited on.
     */
    @Volatile private var geoid: Geoid18Grid? = null

    /** True once the GEOID18 grid is available. */
    val geoidLoaded: Boolean get() = geoid != null

    /** True while the GEOID18 grid load has not finished (loaded or failed). */
    @Volatile private var geoid18Loading = true

    /**
     * True once the beta CRS registry's SPCS2022 catalog (953 zones) is parsed (Phase 4 WP X). Before that, a
     * main-thread SPCS2022 conversion reads "SPCS2022 zone data is loading" (labelled default position) instead of
     * parsing on the UI thread; [spcs2022Events] then refreshes the screens through [heightDataVersion].
     */
    @Volatile private var spcs2022Ready = false
    private val spcs2022Events = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Incremented when the GEOID18 grid load ends (loaded or failed). */
    private val geoid18Events = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Height-data load-completion signal: changes whenever a height model's data finished loading (the
     * GEOID18 grid here, EGM2008 / EGM96 / GEOID2022 packs in [AppGeoidModels.loadEvents]). Screens that may
     * have shown a "… data is loading" height (Settings preview, point Details) re-render when it changes.
     * The toolbar needs no signal: it re-converts on its one-second tick.
     */
    val heightDataVersion: kotlinx.coroutines.flow.StateFlow<Long> =
        kotlinx.coroutines.flow.combine(geoid18Events, AppGeoidModels.loadEvents, spcs2022Events) { a, b, c -> a + b + c }
            .stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0L)

    init {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val t0 = System.nanoTime()
            DiagnosticsLogger.d(DiagTags.DATA) { "GEOID18 grid load started" }
            geoid = runCatching {
                Geoid18Grid.fromClasspath()   // bundled in geokit-core's jar, packaged into the APK
            }.onSuccess { DiagnosticsLogger.i(DiagTags.DATA, "GEOID18 grid loaded durationMs=${(System.nanoTime() - t0) / 1_000_000}") }.onFailure {
                DiagnosticsLogger.e(DiagTags.DATA, "GEOID18 grid load failed: NAVD88 heights unavailable", it)
                recordGridFailure("GEOID18 grid load failed (${it.javaClass.simpleName})")
            }
                .getOrNull()
            geoid18Loading = false
            geoid18Events.value = geoid18Events.value + 1
        }
    }

    /** Global geoid models opened successfully (each at most once per process; failures are retried). */
    private val warmUps = GeoidWarmUpTracker(AppGeoidModels::warmUp)

    init {
        // EGM2008 / EGM96 heights: open the chosen model's data off the main thread as soon as it is
        // selected (at start-up if already saved). Main-thread lookups before this finishes read "data is
        // loading" (they never load on the UI thread); background lookups load synchronously. A model is
        // marked opened only when its data loaded, so a failed open is tried again on the next selection.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // GEOID2022 (beta) only when the preview is ON (effectiveVertical): nothing beta loads otherwise.
            settingsFlow.map { it.effectiveVertical.appGeoidModel() }.distinctUntilChanged().collect { id ->
                if (id == null) return@collect
                val t0 = System.nanoTime()
                val r = warmUps.warmUp(id) ?: return@collect
                r.onSuccess {
                    if (warmUps.isWarm(id)) DiagnosticsLogger.i(DiagTags.DATA, "${id.token} geoid opened availability=$it durationMs=${(System.nanoTime() - t0) / 1_000_000}")
                    else {
                        DiagnosticsLogger.w(DiagTags.DATA, "${id.token} geoid open failed availability=$it: ${id.token} heights unavailable (will retry)")
                        recordGridFailure("${id.token} geoid open failed (availability=$it)")
                    }
                }.onFailure {
                    DiagnosticsLogger.e(DiagTags.DATA, "${id.token} geoid open failed: ${id.token} heights unavailable", it)
                    recordGridFailure("${id.token} geoid open failed (${it.javaClass.simpleName})")
                }
            }
        }
    }

    init {
        // SPCS2022 (beta): parse the zone catalog off the main thread once the system is in use (preview ON).
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            settingsFlow.map { it.effectiveHorizontal == app.surrealar.settings.model.HorizontalSystem.SPCS2022 }
                .distinctUntilChanged().collect { needed -> if (needed) warmUpSpcs2022() }
        }
    }

    private fun warmUpSpcs2022() {
        if (spcs2022Ready) return
        val t0 = System.nanoTime()
        runCatching { Nsrs2022Registries.betaCrs.catalog(io.github.pennstatecs.geokit.crs.CrsCatalogId.SPCS2022).operational.size }
            .onSuccess {
                spcs2022Ready = true
                spcs2022Events.value = spcs2022Events.value + 1
                DiagnosticsLogger.i(DiagTags.DATA, "SPCS2022 beta catalog loaded zones=$it durationMs=${(System.nanoTime() - t0) / 1_000_000}")
            }
            .onFailure {
                DiagnosticsLogger.e(DiagTags.DATA, "SPCS2022 beta catalog load failed: SPCS2022 output unavailable", it)
                recordGridFailure("SPCS2022 beta catalog load failed (${it.javaClass.simpleName})")
            }
    }

    // ── diagnostics (Phase 4 WP C: survey context in the diagnostic report) ──
    private val gridFailures = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun recordGridFailure(what: String) {
        synchronized(gridFailures) { if (gridFailures.size < 20) gridFailures.add(what) }
    }

    /** Grid / model loading failures this process (model and exception type only; no paths, no positions). */
    fun gridLoadFailures(): List<String> = synchronized(gridFailures) { gridFailures.toList() }

    /**
     * Load state of the height model [s] uses, for diagnostics: "loaded", "loading", "failed", or why none is
     * needed. Never loads anything.
     */
    fun heightModelState(s: CoordinateSystemSettings = settings): String {
        val v = s.effectiveVertical
        val id = v.appGeoidModel()
        return when {
            v == app.surrealar.settings.model.VerticalReference.NAVD88_GEOID18 ->
                if (geoid != null) "loaded" else if (geoid18Loading) "loading" else "failed"
            id != null -> if (warmUps.isWarm(id)) "loaded" else "not loaded yet (opens in the background when selected)"
            else -> "no app geoid model needed for ${v.shortLabel}"
        }
    }

    /** The saved SPCS2022 zone: never parses the catalog on the main thread (loads synchronously elsewhere). */
    private fun lookupSpcs2022Zone(id: String): Spcs2022ZoneLookup {
        if (!spcs2022Ready && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return Spcs2022ZoneLookup.Loading
        // Look up (this parses the catalog the first time) BEFORE marking ready, and announce it, so a main-thread
        // call during the parse still gets Loading and screens showing "loading" refresh (review M1).
        val result = Nsrs2022Registries.spcs2022Zone(id)?.let { Spcs2022ZoneLookup.Found(it) } ?: Spcs2022ZoneLookup.Unknown
        if (!spcs2022Ready) {
            spcs2022Ready = true
            spcs2022Events.value = spcs2022Events.value + 1
        }
        return result
    }

    // Preview OFF: geokit's default frame registry exactly as before; preview ON: the NSRS 2022 beta registry.
    private val converter = CoordinateConverter(
        { lat, lon -> geoid?.undulation(lat, lon) }, AppGeoidModels::model, { AppGeoidModels.geoid2022 },
        io.github.pennstatecs.geokit.datum.FrameTransformationRegistry.DEFAULT,
        io.github.pennstatecs.geokit.datum.FrameTransformationRegistry.NSRS2022_BETA,
        ::lookupSpcs2022Zone,
    )

    /**
     * EPSG code to store in Coordinate.crsEpsg for a capture. Fixes on the GNSS feed are always
     * WGS84: NAD83(2011) receiver output is normalised as it enters FixSwitchboard (geokit-gnss FixDatumNormalizer),
     * so captures are stored in WGS84 and converted back to NAD83 on display.
     */
    @Suppress("UNUSED_PARAMETER")
    fun storedDatumEpsg(provider: Provider, s: CoordinateSystemSettings): Int = StoredDatum.WGS84_3D

    /** Live fix (always WGS84 on the feed — see [storedDatumEpsg]) → configured system. */
    fun convertFix(fix: Fix, s: CoordinateSystemSettings): ConvertedPosition =
        track(
            SourcePosition(
                latDeg = fix.latDeg, lonDeg = fix.lonDeg,
                ellipsoidalHeightM = fix.altEllipsoidalM, receiverMslM = fix.altMslM,
                isNad83 = false,
                epochYear = Itrf2014ToNad83.decimalYear(fix.timeUtc.toEpochMilli()),
            ), s, live = true,
        )

    /**
     * Stored coordinate → configured system. `altitude` is ellipsoidal (antenna height already
     * removed) and `altitudeMsl` the receiver MSL height; `crsEpsg` says which datum they are in
     * (null/4326/4979 = WGS84, 6318/6319 = NAD83(2011), 9989/9990 = ITRF2020; see [storedSourcePosition]).
     * The transformation epoch is the capture time ([storedEpochYear]).
     */
    fun convertCoordinate(c: Coordinate, s: CoordinateSystemSettings): ConvertedPosition =
        track(storedSourcePosition(c, s), s)

    // ── Formatting in the current settings (one place, so every screen agrees) ──────────────────

    /**
     * Converts and reports failures / zone changes to the COORD log (throttled, no positions). Only the
     * [live] fix feeds the zone/height state; stored-point conversions report failures only.
     */
    private fun track(src: SourcePosition, s: CoordinateSystemSettings, live: Boolean = false): ConvertedPosition =
        try {
            // geoidLoaded concerns the background GEOID18 grid only; EGM models load on demand.
            markGeoid18Loading(converter.convert(src, s), s, geoid18Loading && geoid == null)
                .also { CoordDiagnostics.onResult(it, live, geoidLoaded || s.effectiveVertical.appGeoidModel() != null) }
        } catch (e: Exception) {
            CoordDiagnostics.onFailure(s, e); throw e
        }

    /** Height parts (h, H, N, model, reference, epoch, beta) of a stored point, for the Details screen. */
    fun elevationOfCoordinate(c: Coordinate, s: CoordinateSystemSettings): ElevationDetail {
        val src = storedSourcePosition(c, s)
        return converter.elevation(src, s, track(src, s))
    }

    /** Height parts of a live fix, for the Settings preview. */
    fun elevationOfFix(fix: Fix, s: CoordinateSystemSettings): ElevationDetail {
        val src = SourcePosition(
            latDeg = fix.latDeg, lonDeg = fix.lonDeg,
            ellipsoidalHeightM = fix.altEllipsoidalM, receiverMslM = fix.altMslM,
            isNad83 = false, epochYear = Itrf2014ToNad83.decimalYear(fix.timeUtc.toEpochMilli()),
        )
        return converter.elevation(src, s, track(src, s, live = true))
    }

    /** Stored point → current system. */
    fun convert(c: Coordinate): ConvertedPosition = convertCoordinate(c, settings)

    /** A WGS84 position (lat/lon/ellipsoidal height, observed at [epochMillis]) → current system. */
    fun convertWgs84(latDeg: Double, lonDeg: Double, ellipsoidalHeightM: Double?, epochMillis: Long,
                     receiverMslM: Double? = null): ConvertedPosition =
        track(
            SourcePosition(latDeg, lonDeg, ellipsoidalHeightM, receiverMslM, isNad83 = false,
                epochYear = Itrf2014ToNad83.decimalYear(epochMillis)), settings,
        )

    /** Stored point as one line: "N 435,037.47 · E 2,442,754.19 ftUS" / "41.34769028, -76.02259178". */
    fun positionText(c: Coordinate, latLonDecimals: Int = 8): String =
        runCatching { CoordinateFormat.horizontal(convert(c), latLonDecimals) }
            .getOrElse { String.format(java.util.Locale.US, "%.${latLonDecimals}f, %.${latLonDecimals}f", c.latitude, c.longitude) }

    /** Stored point's height in the chosen reference/units: "1,286.74 ftUS NAVD88" (or "—"). */
    fun heightText(c: Coordinate): String =
        runCatching { CoordinateFormat.height(convert(c)) }.getOrElse { "—" }

    /** Copyable position in the chosen system. */
    fun copyText(c: Coordinate): String =
        runCatching { CoordinateFormat.copyText(convert(c)) }
            .getOrElse { String.format(java.util.Locale.US, "%.8f, %.8f", c.latitude, c.longitude) }

    fun distanceText(meters: Double): String = CoordinateFormat.distance(meters, unit)
    fun lengthText(meters: Double, decimals: Int = CoordinateFormat.linearDecimals(unit)): String =
        CoordinateFormat.length(meters, unit, decimals)
    fun deltaText(meters: Double, decimals: Int = CoordinateFormat.linearDecimals(unit)): String =
        CoordinateFormat.delta(meters, unit, decimals)

    /** Hilt entry point for classes that can't be injected (hand-constructed dialogs, adapters). */
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface Access { fun coordinateSystemService(): CoordinateSystemService }

    companion object {
        fun from(context: Context): CoordinateSystemService =
            dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, Access::class.java)
                .coordinateSystemService()
    }
}

/**
 * Coordinate epoch used to convert a stored point. ITRF2020 output (new in Phase 2), and a point stored in
 * ITRF2020 (EPSG 9989/9990), use the recorded `coordinateEpoch` when known, else the capture time. Every
 * other conversion keeps the capture time exactly as before, so existing outputs are unchanged (for
 * points captured since v14 the two are the same number anyway). A recorded epoch that is not a finite
 * decimal year in 1980..2100 ([validCoordinateEpochOrNull]) counts as unknown: the capture time is used.
 * GEOID2022 (beta) heights use the coordinate epoch the same way (ITRF2020 position and N at that epoch).
 */
/**
 * While the GEOID18 grid is still loading ([gridLoading]) a NAVD88 height reads as loading, not as
 * "outside GEOID18 coverage" (the engine's wording for a missing undulation). Identity in every other case,
 * so a loaded or failed grid gives exactly the converter's output.
 */
internal fun markGeoid18Loading(p: ConvertedPosition, s: CoordinateSystemSettings, gridLoading: Boolean): ConvertedPosition =
    if (gridLoading && p.height == null && s.effectiveVertical == app.surrealar.settings.model.VerticalReference.NAVD88_GEOID18 &&
        p.heightUnavailableReason == io.github.pennstatecs.geokit.convert.HeightUnavailableReason.OUTSIDE_GEOID18_COVERAGE.description)
        p.copy(heightUnavailableReason = GEOID18_LOADING, heightLoading = true)
    else p

/** Shown in place of a NAVD88 height while the GEOID18 grid loads. */
internal const val GEOID18_LOADING = "GEOID18 data is loading"

internal fun storedEpochYear(c: Coordinate, s: CoordinateSystemSettings): Double =
    // usesCoordinateEpoch: ITRF2020, a 2022 beta frame / SPCS2022 (Phase 4 WP X) or a GEOID2022 height.
    (if (s.usesCoordinateEpoch || StoredDatum.isItrf2020(c.crsEpsg))
        app.surrealar.domain.model.validCoordinateEpochOrNull(c.coordinateEpoch) else null)
        ?: Itrf2014ToNad83.decimalYear(c.timestamp)

/**
 * A stored point as the converter's source: lat/lon/heights in the datum its `crsEpsg` names
 * ([StoredDatum.geodeticDatum]: WGS84 (ITRF2014), NAD83(2011) or ITRF2020; null/other codes → WGS84 as
 * before) at [storedEpochYear].
 */
internal fun storedSourcePosition(c: Coordinate, s: CoordinateSystemSettings): SourcePosition =
    SourcePosition(
        latDeg = c.latitude, lonDeg = c.longitude,
        ellipsoidalHeightM = c.altitude, receiverMslM = c.altitudeMsl,
        isNad83 = StoredDatum.isNad83(c.crsEpsg),
        epochYear = storedEpochYear(c, s),
        datum = StoredDatum.geodeticDatum(c.crsEpsg),
    )
