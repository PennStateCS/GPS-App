package com.example.surveyingapp.gnss.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
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
                publishLocation(fixToLocation(fix))
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

    private fun publishLocation(location: Location) {
        try {
            ensureProviderAdded()
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
            Log.d(TAG, "Published mock location lat=${location.latitude} lon=${location.longitude}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Mock location blocked — app not selected as mock location app", e)
            _errorEvents.tryEmit(MockLocationError.NOT_PERMITTED)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Test provider configuration error", e)
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
            Log.i(TAG, "Mock publishing started — GPS test provider added and enabled")
        } catch (e: SecurityException) {
            // Propagated to caller, which handles it uniformly
            throw e
        } catch (e: IllegalArgumentException) {
            // Provider already registered by this process from a previous start; treat as added.
            providerAdded = true
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            Log.d(TAG, "GPS test provider already existed; re-enabled")
        }
    }

    private fun teardownProvider() {
        if (!providerAdded) return
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            Log.d(TAG, "GPS test provider removed")
        } catch (e: Exception) {
            Log.w(TAG, "Teardown failed (provider already gone?): ${e.message}")
        } finally {
            providerAdded = false
        }
    }

    // ── Error enum ────────────────────────────────────────────────────────────

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

        /** Drop fixes older than 10 seconds. */
        internal const val MAX_FIX_AGE_MS = 10_000L

        /** Allow up to 5 seconds of clock skew between fix timestamp and device clock. */
        internal const val MAX_CLOCK_SKEW_MS = 5_000L
    }
}
