package app.surrealar.gnss.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Publishes active external GNSS fixes as Android mock GPS locations so that other
 * Android location clients can receive the external receiver's position through the
 * standard Android location stack.
 *
 * ## Setup required by the user
 * 1. Enable Developer Options on the Android device.
 * 2. Select this app as the "Mock location app" in Developer Options → Select mock location app.
 * 3. Enable this feature in Settings → GNSS Receiver → Advanced.
 *
 * ## ARCore note
 * Publishing mock locations via the Android location stack may allow ARCore to receive
 * the external receiver's position during Geospatial API initialization. ARCore still
 * performs its own camera/sensor/VPS fusion; it does not use Android location directly
 * for pose tracking. Enabling this feature does not override ARCore's camera pose and
 * does not guarantee centimeter-level accuracy in AR rendering.
 *
 * ## What is and is not published
 * - Only external GNSS fixes (Provider != INTERNAL) are published.
 * - Internal device GPS fixes are never re-published as mock locations.
 * - Stale fixes (older than [MAX_FIX_AGE_MS]) are dropped.
 * - Invalid coordinate values are dropped.
 * - Duplicate timestamps are dropped to avoid flooding.
 *
 * ## Lifecycle
 * Call [start] once with a long-lived [CoroutineScope] (e.g. application scope).
 * The publisher observes both the enabled setting and the fix stream; it tears down
 * the test provider automatically when the setting is disabled or [stop] is called.
 */
class AndroidMockLocationPublisher(
    private val context: Context,
    private val fixSwitchboard: FixSwitchboard,
    private val settingsRepo: SettingsRepository
) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var publishJob: Job? = null
    private var providerAdded = false
    private var lastPublishedUtc: Instant = Instant.EPOCH
    /** Throttle for the per-injection export log — status is updated every injection, file log is not. */
    private var lastInjectionLogMs: Long = 0L

    private val _errorEvents = MutableSharedFlow<MockLocationError>(extraBufferCapacity = 8)

    /**
     * Emits when the mock location test provider cannot be set up, typically because
     * this app has not been selected as the mock location app in Developer Options.
     */
    val errorEvents: SharedFlow<MockLocationError> = _errorEvents.asSharedFlow()

    /** Start observing the fix stream and publishing mock locations while enabled. */
    fun start(scope: CoroutineScope) {
        if (publishJob?.isActive == true) return
        publishJob = scope.launch {
            combine(
                settingsRepo.mockLocationEnabled,
                settingsRepo.locationSource,
                fixSwitchboard.fixes
            ) { mockEnabled, locationSource, fix -> Triple(mockEnabled, locationSource, fix) }
            .collect { (mockEnabled, locationSource, fix) ->
                if (!mockEnabled || locationSource != LocationSourceType.EXTERNAL) {
                    if (providerAdded) {
                        Log.i(TAG, "Stopping mock publishing — source=$locationSource mockEnabled=$mockEnabled")
                    }
                    teardownProvider()
                    return@collect
                }
                if (!isExternalFix(fix))  return@collect
                if (isStale(fix))         return@collect
                if (!isValid(fix))        return@collect
                if (isDuplicate(fix))     return@collect
                publishLocation(fix, fixToLocation(fix))
            }
        }
    }

    /** Stop publishing and clean up the Android test provider. */
    fun stop() {
        publishJob?.cancel()
        publishJob = null
        teardownProvider()
    }

    // ── Predicates ────────────────────────────────────────────────────────────

    internal fun isExternalFix(fix: Fix): Boolean = fix.provider != Provider.INTERNAL

    internal fun isStale(fix: Fix): Boolean {
        val ageMs = ChronoUnit.MILLIS.between(fix.timeUtc, Instant.now())
        return ageMs > MAX_FIX_AGE_MS || ageMs < -MAX_CLOCK_SKEW_MS
    }

    internal fun isValid(fix: Fix): Boolean =
        fix.latDeg in -90.0..90.0 && fix.lonDeg in -180.0..180.0

    internal fun isDuplicate(fix: Fix): Boolean {
        if (!fix.timeUtc.isAfter(lastPublishedUtc)) return true
        lastPublishedUtc = fix.timeUtc
        return false
    }

    // ── Location conversion ───────────────────────────────────────────────────

    /**
     * Converts a GNSS [Fix] to an Android [Location] stamped with [LocationManager.GPS_PROVIDER].
     * All optional Fix fields (altitude, accuracy, speed, course) are set when present.
     */
    internal fun fixToLocation(fix: Fix): Location =
        Location(LocationManager.GPS_PROVIDER).apply {
            latitude  = fix.latDeg
            longitude = fix.lonDeg

            fix.altEllipsoidalM?.let { altitude = it }
            fix.speedMps?.let       { speed = it.toFloat() }
            fix.courseDeg?.let      { bearing = it.toFloat() }

            // accuracy is mandatory — setTestProviderLocation throws IllegalArgumentException
            // if it is unset (0f). Fall back to a conservative 5 m estimate when GST-derived
            // hAccM is absent so the location object is always valid.
            accuracy = fix.hAccM?.toFloat()?.takeIf { it > 0f } ?: 5.0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fix.vAccM?.let { verticalAccuracyMeters = it.toFloat() }
            }

            // time must be a real wall-clock millisecond value; elapsedRealtimeNanos must
            // also be set. Both are required fields on modern Android.
            val epochMs = fix.timeUtc.toEpochMilli()
            time = if (epochMs > 0L) epochMs else System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    // ── Publishing ────────────────────────────────────────────────────────────

    private fun publishLocation(fix: Fix, location: Location) {
        val appRecvMs = System.currentTimeMillis()
        try {
            ensureProviderAdded()
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)

            // Accuracy is a REQUIRED, non-zero field on the Android test provider, so when the source
            // fix carries none we inject a placeholder. Record the REAL source accuracy (null if
            // missing) plus this flag so a missing accuracy is never mistaken for a genuine 0 m fix.
            val hAccWasFallback = fix.hAccM?.let { it > 0.0 } != true
            val fixTimeMs = fix.timeUtc.toEpochMilli()
            MockInjectionStatus.apply {
                providerActive = true
                injectionCount++
                lastInjectionAtMs = appRecvMs
                lastInjectedProvider = LocationManager.GPS_PROVIDER
                lastInjectedLat = location.latitude
                lastInjectedLon = location.longitude
                lastInjectedAltM = fix.altEllipsoidalM
                lastInjectedHAccM = fix.hAccM
                lastInjectedVAccM = fix.vAccM
                lastInjectedHAccWasFallback = hAccWasFallback
                lastSourceFixProvider = fix.provider.name
                lastSourceFixTimeMs = fixTimeMs
            }

            // Throttle the export log to ~INJECT_LOG_INTERVAL_MS (fixes arrive at up to 10 Hz — logging
            // every one would flood the rolling log). The in-memory status above updates every fix.
            if (appRecvMs - lastInjectionLogMs >= INJECT_LOG_INTERVAL_MS) {
                lastInjectionLogMs = appRecvMs
                DiagnosticsLogger.i(DIAG, "MOCK_LOCATION_INJECTED sourceProvider=${fix.provider} " +
                    "fixTimeMs=$fixTimeMs appRecvMs=$appRecvMs ageMs=${appRecvMs - fixTimeMs} " +
                    "lat=${"%.7f".format(location.latitude)} lon=${"%.7f".format(location.longitude)} " +
                    "altM=${fix.altEllipsoidalM?.let { "%.2f".format(it) } ?: "unknown"} " +
                    "hAccM=${fix.hAccM?.let { "%.2f".format(it) } ?: "unknown"}" +
                    (if (hAccWasFallback) "(placeholder ${location.accuracy}m injected)" else "") +
                    " vAccM=${fix.vAccM?.let { "%.2f".format(it) } ?: "unknown"} rtk=${fix.rtkStatus} " +
                    "injections=${MockInjectionStatus.injectionCount}")
            }
        } catch (e: SecurityException) {
            DiagnosticsLogger.w(DIAG, "MOCK_LOCATION injection blocked — app not selected as mock " +
                "location app: ${e.javaClass.simpleName}: ${e.message}", e)
            MockInjectionStatus.providerActive = false
            _errorEvents.tryEmit(MockLocationError.NOT_PERMITTED)
        } catch (e: IllegalArgumentException) {
            DiagnosticsLogger.w(DIAG, "MOCK_LOCATION test provider configuration error: " +
                "${e.javaClass.simpleName}: ${e.message}", e)
            _errorEvents.tryEmit(MockLocationError.PROVIDER_ERROR)
        }
    }

    private fun ensureProviderAdded() {
        if (providerAdded) return
        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                /* requiresNetwork */   false,
                /* requiresSatellite */ false,
                /* requiresCell */      false,
                /* hasMonetaryCost */   false,
                /* supportsAltitude */  true,
                /* supportsSpeed */     true,
                /* supportsBearing */   true,
                /* powerRequirement */  Criteria.POWER_LOW,
                /* accuracy */          Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            providerAdded = true
            MockInjectionStatus.providerActive = true
            DiagnosticsLogger.i(DIAG, "MOCK_LOCATION_START — GPS test provider added and enabled")
        } catch (e: SecurityException) {
            // Propagated to caller, which handles it uniformly
            throw e
        } catch (e: IllegalArgumentException) {
            // Provider already registered by this process from a previous start; treat as added.
            providerAdded = true
            MockInjectionStatus.providerActive = true
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            DiagnosticsLogger.i(DIAG, "MOCK_LOCATION_START — GPS test provider already existed; re-enabled")
        }
    }

    private fun teardownProvider() {
        if (!providerAdded) return
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            DiagnosticsLogger.i(DIAG, "MOCK_LOCATION_STOP — GPS test provider removed " +
                "(injections=${MockInjectionStatus.injectionCount})")
        } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "MOCK_LOCATION_STOP teardown failed (provider already gone?): ${e.message}")
        } finally {
            providerAdded = false
            MockInjectionStatus.providerActive = false
        }
    }

    // ── Error enum ────────────────────────────────────────────────────────────

    /** Why mock-location publishing failed; surfaced so the UI can guide the user to a fix. */
    enum class MockLocationError {
        /**
         * The app has not been selected as the mock location app in Developer Options.
         * Guide the user to: Settings → Developer Options → Select mock location app.
         */
        NOT_PERMITTED,

        /** The test provider could not be configured; see Logcat for details. */
        PROVIDER_ERROR
    }

    companion object {
        private const val TAG = "MockLocPublisher"
        /** Diagnostic export tag for all mock-location lifecycle/injection events. */
        private const val DIAG = "MOCK"
        /** Minimum spacing between per-injection export logs (status still updates every fix). */
        private const val INJECT_LOG_INTERVAL_MS = 3_000L

        /** Drop fixes older than 10 seconds. */
        internal const val MAX_FIX_AGE_MS = 10_000L

        /** Allow up to 5 seconds of clock skew between fix timestamp and device clock. */
        internal const val MAX_CLOCK_SKEW_MS = 5_000L
    }
}
