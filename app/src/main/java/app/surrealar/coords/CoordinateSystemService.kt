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

    init {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val t0 = System.nanoTime()
            DiagnosticsLogger.d(DiagTags.DATA) { "GEOID18 grid load started" }
            geoid = runCatching {
                Geoid18Grid.fromClasspath()   // bundled in geokit-core's jar, packaged into the APK
            }.onSuccess { DiagnosticsLogger.i(DiagTags.DATA, "GEOID18 grid loaded durationMs=${(System.nanoTime() - t0) / 1_000_000}") }.onFailure { DiagnosticsLogger.e(DiagTags.DATA, "GEOID18 grid load failed: NAVD88 heights unavailable", it) }
                .getOrNull()
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
            settingsFlow.map { it.vertical.globalGeoidModel() }.distinctUntilChanged().collect { id ->
                if (id == null) return@collect
                val t0 = System.nanoTime()
                val r = warmUps.warmUp(id) ?: return@collect
                r.onSuccess {
                    if (warmUps.isWarm(id)) DiagnosticsLogger.i(DiagTags.DATA, "${id.token} geoid opened availability=$it durationMs=${(System.nanoTime() - t0) / 1_000_000}")
                    else DiagnosticsLogger.w(DiagTags.DATA, "${id.token} geoid open failed availability=$it: ${id.token} heights unavailable (will retry)")
                }.onFailure { DiagnosticsLogger.e(DiagTags.DATA, "${id.token} geoid open failed: ${id.token} heights unavailable", it) }
            }
        }
    }

    private val converter = CoordinateConverter({ lat, lon -> geoid?.undulation(lat, lon) })

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
     * (null/4326/4979 = WGS84). The transformation epoch is the capture time ([storedEpochYear]).
     */
    fun convertCoordinate(c: Coordinate, s: CoordinateSystemSettings): ConvertedPosition =
        track(
            SourcePosition(
                latDeg = c.latitude, lonDeg = c.longitude,
                ellipsoidalHeightM = c.altitude, receiverMslM = c.altitudeMsl,
                isNad83 = StoredDatum.isNad83(c.crsEpsg),
                epochYear = storedEpochYear(c, s),
            ), s,
        )

    // ── Formatting in the current settings (one place, so every screen agrees) ──────────────────

    /**
     * Converts and reports failures / zone changes to the COORD log (throttled, no positions). Only the
     * [live] fix feeds the zone/height state; stored-point conversions report failures only.
     */
    private fun track(src: SourcePosition, s: CoordinateSystemSettings, live: Boolean = false): ConvertedPosition =
        try {
            // geoidLoaded concerns the background GEOID18 grid only; EGM models load on demand.
            converter.convert(src, s).also { CoordDiagnostics.onResult(it, live, geoidLoaded || s.vertical.globalGeoidModel() != null) }
        } catch (e: Exception) {
            CoordDiagnostics.onFailure(s, e); throw e
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
 * Coordinate epoch used to convert a stored point. ITRF2020 output (new in Phase 2) uses the recorded
 * `coordinateEpoch` when known, else the capture time. Every other output keeps the capture time exactly
 * as before, so existing outputs are unchanged (for points captured since v14 the two are the same
 * number anyway).
 */
internal fun storedEpochYear(c: Coordinate, s: CoordinateSystemSettings): Double =
    (if (s.effectiveDatum == app.surrealar.settings.model.OutputDatum.ITRF2020) c.coordinateEpoch else null)
        ?: Itrf2014ToNad83.decimalYear(c.timestamp)
