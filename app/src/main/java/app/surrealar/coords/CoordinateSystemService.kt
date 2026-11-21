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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.stateIn

/**
 * Android-side entry point for coordinate systems: loads the bundled GEOID18 grid once and converts
 * live fixes and stored coordinates into the configured system. UI code should go through here so
 * every screen agrees.
 */
@Singleton
class CoordinateSystemService @Inject constructor(
    @ApplicationContext context: Context,
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
            DiagnosticsLogger.i("DATA", "GEOID18 grid load start")
            geoid = runCatching {
                context.assets.open(Geoid18Grid.ASSET_PATH).use { Geoid18Grid(it.readBytes()) }
            }.onSuccess { DiagnosticsLogger.i("DATA", "GEOID18 grid loaded in ${(System.nanoTime() - t0) / 1_000_000}ms") }.onFailure { DiagnosticsLogger.e("DATA", "GEOID18 grid failed to load — NAVD88 heights unavailable", it) }
                .getOrNull()
        }
    }

    private val converter = CoordinateConverter { lat, lon -> geoid?.undulation(lat, lon) }

    /**
     * EPSG code to store in Coordinate.crsEpsg for a capture. Fixes on the GNSS feed are always
     * WGS84: NAD83(2011) receiver output is normalised as it enters FixSwitchboard (FeedDatum),
     * so captures are stored in WGS84 and converted back to NAD83 on display.
     */
    @Suppress("UNUSED_PARAMETER")
    fun storedDatumEpsg(provider: Provider, s: CoordinateSystemSettings): Int = StoredDatum.WGS84_3D

    /** Live fix (always WGS84 on the feed — see [storedDatumEpsg]) → configured system. */
    fun convertFix(fix: Fix, s: CoordinateSystemSettings): ConvertedPosition =
        converter.convert(
            SourcePosition(
                latDeg = fix.latDeg, lonDeg = fix.lonDeg,
                ellipsoidalHeightM = fix.altEllipsoidalM, receiverMslM = fix.altMslM,
                isNad83 = false,
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

    // ── Formatting in the current settings (one place, so every screen agrees) ──────────────────

    /** Stored point → current system. */
    fun convert(c: Coordinate): ConvertedPosition = convertCoordinate(c, settings)

    /** A WGS84 position (lat/lon/ellipsoidal height, observed at [epochMillis]) → current system. */
    fun convertWgs84(latDeg: Double, lonDeg: Double, ellipsoidalHeightM: Double?, epochMillis: Long,
                     receiverMslM: Double? = null): ConvertedPosition =
        converter.convert(
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