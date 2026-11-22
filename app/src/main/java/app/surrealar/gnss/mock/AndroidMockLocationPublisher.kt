package app.surrealar.gnss.mock

import android.annotation.SuppressLint
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
import io.github.pennstatecs.geokit.gnss.capture.FixFreshnessGate
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** Settings → AR Display → Antenna Height Above Tablet (m); subtracted from the injected altitude. */
    @Volatile private var antennaAboveCameraM: Double = 0.0
    /** Throttle for the per-injection export log — status is updated every injection, file log is not. */
    private var lastInjectionLogMs: Long = 0L

    private val _errorEvents = MutableSharedFlow<MockLocationError>(extraBufferCapacity = 8)

    /**
     * Emits when the mock location test provider cannot be set up, typically because
     * this app has not been selected as the mock location app in Developer Options.
     */
    val errorEvents: SharedFlow<MockLocationError> = _errorEvents.asSharedFlow()

    /**
     * True while the AR screen is open. ARCore Geospatial localizes from Android location, so with an
     * external receiver selected AR publishes the receiver's fixes as mock locations automatically,
     * even when the user has not turned on the Settings toggle. In-memory only (never persisted).
     */
    private val arSessionActive = MutableStateFlow(false)

    /** Called by the AR screen on resume (true) / pause (false). */
    fun setArSessionActive(active: Boolean) {
        if (arSessionActive.value != active) {
            DiagnosticsLogger.i("GNSS", "AR session ${if (active) "active — auto-enabling" else "inactive — releasing"} mock publishing")
        }
        arSessionActive.value = active
    }

    /** Start observing the fix stream and publishing mock locations while enabled. */
    fun start(scope: CoroutineScope) {
        if (publishJob?.isActive == true) return
        removeLeftoverTestProvider()
        publishJob = scope.launch {
            combine(
                settingsRepo.mockLocationEnabled,
                arSessionActive,
                settingsRepo.locationSource,
                // Receiver page antenna setup: phase centre above the tablet camera.
                settingsRepo.gnssReceiverSettings.combine(settingsRepo.externalReceiverProfile) { receiver, profile ->
                    app.surrealar.settings.model.AntennaSetup.resolve(receiver, profile).antennaAboveCameraM
                },
                fixSwitchboard.fixes
            ) { userEnabled, arActive, locationSource, aboveCameraM, fix ->
                antennaAboveCameraM = aboveCameraM
                Triple(userEnabled || arActive, locationSource, fix)
            }
            .collect { (mockEnabled, locationSource, fix) ->
                if (!mockEnabled || locationSource != LocationSourceType.EXTERNAL) {
                    if (providerAdded) {
                        DiagnosticsLogger.i(DIAG, "Mock publishing off: " +
                            if (locationSource != LocationSourceType.EXTERNAL) "source is $locationSource" else "turned off")
                        Log.i(TAG, "Stopping mock publishing — source=$locationSource mockEnabled=$mockEnabled")
                    }
                    teardownProvider()
                    return@collect
                }
                val drop = when {
                    !isExternalFix(fix) -> "internal fix"
                    isStale(fix)        -> "stale (late vs stream)"
                    !isValid(fix)       -> "invalid coords"
                    isDuplicate(fix)    -> null   // normal (same epoch re-delivered); not worth logging
                    else -> ""
                }
                debugCountMock(drop)
                if (drop != "") return@collect
                publishLocation(fix, fixToLocation(fix, antennaAboveCameraM))
            }
        }
    }

    // Debug-build accounting: what happened to the fixes reaching the publisher (see GnssDebugLog).
    private var debugPublished = 0
    private val debugDropped = mutableMapOf<String, Int>()

    /** [drop] = "" published, null duplicate (ignored), else the drop reason. */
    private fun debugCountMock(drop: String?) {
        if (!app.surrealar.gnss.debug.GnssDebugLog.enabled || drop == null) return
        if (drop.isEmpty()) debugPublished++ else debugDropped[drop] = (debugDropped[drop] ?: 0) + 1
        app.surrealar.gnss.debug.GnssDebugLog.every("mock.summary", "mock") {
            val line = "published=$debugPublished dropped=$debugDropped arActive=${arSessionActive.value} provider=$providerAdded"
            debugPublished = 0; debugDropped.clear()
            line
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

    /**
     * Skew-tolerant staleness (see [FixFreshnessGate]): a fix is stale when it arrives more than
     * [MAX_FIX_AGE_MS] later than the stream's normal delivery delay, or its GNSS time goes backwards.
     * The tablet clock vs GNSS time no longer matters — a skewed tablet clock previously made EVERY
     * fix "stale", so nothing was published (and AR silently ignored the external receiver).
     * After [STALE_RESET_COUNT] consecutive rejections the baseline is rebuilt, so a tablet clock
     * that jumps mid-session (network time sync) cannot block publishing permanently.
     */
    internal fun isStale(fix: Fix): Boolean {
        if (freshness.isFresh(fix)) {
            consecutiveStale = 0
            return false
        }
        if (++consecutiveStale >= STALE_RESET_COUNT) {
            DiagnosticsLogger.w("GNSS", "Fix timing baseline reset after $consecutiveStale stale fixes (clock change?)")
            resetFreshness()
        }
        return true
    }

    private var freshness = FixFreshnessGate(MAX_FIX_AGE_MS)
    private var consecutiveStale = 0

    private fun resetFreshness() {
        freshness = FixFreshnessGate(MAX_FIX_AGE_MS)
        consecutiveStale = 0
    }

    internal fun isValid(fix: Fix): Boolean =
        fix.latDeg in -90.0..90.0 && fix.lonDeg in -180.0..180.0

    internal fun isDuplicate(fix: Fix): Boolean {
        // A fix far BEHIND the last published one is a time jump, not a duplicate: e.g. the first fix
        // after connect was dated from a wrong tablet date (no RMC/ZDA yet) a day ahead. Without this,
        // every later fix would be dropped as a "duplicate" for the rest of the session.
        if (fix.timeUtc.isBefore(lastPublishedUtc.minusSeconds(TIME_JUMP_RESET_SEC))) {
            DiagnosticsLogger.w(DIAG, "Mock duplicate check reset: fix time jumped back from $lastPublishedUtc")
            lastPublishedUtc = fix.timeUtc
            return false
        }
        if (!fix.timeUtc.isAfter(lastPublishedUtc)) return true
        lastPublishedUtc = fix.timeUtc
        return false
    }

    // ── Location conversion ───────────────────────────────────────────────────

    /**
     * Converts a GNSS [Fix] to an Android [Location] stamped with [LocationManager.GPS_PROVIDER].
     * All optional Fix fields (altitude, accuracy, speed, course) are set when present.
     */
    internal fun fixToLocation(
        fix: Fix,
        /** Antenna phase centre above the tablet camera (m): the injected height is the CAMERA's. */
        antennaAboveCameraM: Double = 0.0,
        nowWallMs: Long = System.currentTimeMillis(),
        nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Location =
        Location(LocationManager.GPS_PROVIDER).apply {
            latitude  = fix.latDeg
            longitude = fix.lonDeg

            // Android Location.altitude is WGS84 ellipsoidal (what ARCore expects). The fix is the
            // antenna; ARCore treats the location as the device, so lower it to the camera.
            fix.altEllipsoidalM?.let { altitude = it - antennaAboveCameraM }
            fix.speedMps?.let       { speed = it.toFloat() }
            fix.courseDeg?.let      { bearing = it.toFloat() }

            // accuracy is mandatory — setTestProviderLocation throws if it is unset (0f). The receiver's
            // own 68 % radius when it sends one; else the app-wide DOP × UERE estimate for the fix's
            // accuracy class (same number the toolbar shows); the flat per-status value only when HDOP
            // is missing too.
            accuracy = fix.hAccM?.toFloat()?.takeIf { it > 0f }
                ?: io.github.pennstatecs.geokit.gnss.accuracy.AccuracyEstimator.estimate1SigmaMeters(fix, uere)
                    .horizontalMeters?.toFloat()?.takeIf { it > 0f }
                ?: fallbackHorizontalAccuracyM(fix.rtkStatus)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                fix.vAccM?.let { verticalAccuracyMeters = it.toFloat() }
            }

            // time = the fix's GNSS epoch. elapsedRealtimeNanos is the monotonic clock fusion engines
            // (FLP/ARCore) use to align samples: back-date it by the fix's age so a fix that took
            // ~0.1–0.3 s to arrive isn't treated as current (≈0.2–0.4 m lag while walking). Only
            // plausible ages are trusted (device-clock skew must never move it into the future or far
            // past); otherwise arrival time is used, as before.
            val epochMs = fix.timeUtc.toEpochMilli()
            time = if (epochMs > 0L) epochMs else nowWallMs
            val ageMs = plausibleFixAgeMs(epochMs, nowWallMs)
            elapsedRealtimeNanos = nowElapsedNanos - (ageMs ?: 0L) * 1_000_000L
        }

    private val uere = io.github.pennstatecs.geokit.gnss.accuracy.UereTable()

    /**
     * Placeholder horizontal accuracy (m, ~68%) by solution type when there's no receiver accuracy and
     * no HDOP to estimate one from.
     * Conservative for each class: RTK fixed is cm-level, float decimetres, DGPS ~1 m.
     */
    internal fun fallbackHorizontalAccuracyM(status: io.github.pennstatecs.geokit.gnss.model.RtkStatus): Float = when (status) {
        io.github.pennstatecs.geokit.gnss.model.RtkStatus.FIX   -> 0.05f
        io.github.pennstatecs.geokit.gnss.model.RtkStatus.FLOAT -> 0.5f
        io.github.pennstatecs.geokit.gnss.model.RtkStatus.DGPS  -> 1.5f
        else -> 5.0f
    }

    /** Fix age (ms) when it is plausible (0…[MAX_TRUSTED_FIX_AGE_MS]); null = don't back-date. */
    internal fun plausibleFixAgeMs(fixEpochMs: Long, nowWallMs: Long): Long? {
        if (fixEpochMs <= 0L) return null
        val age = nowWallMs - fixEpochMs
        return age.takeIf { it in 0L..MAX_TRUSTED_FIX_AGE_MS }
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
                val backdatedMs = plausibleFixAgeMs(fixTimeMs, appRecvMs)
                // No lat/lon here: this line reaches release logcat and the exportable diagnostics log.
                app.surrealar.gnss.debug.GnssDebugLog.event("mock") {
                    "injected lat=${"%.7f".format(location.latitude)} lon=${"%.7f".format(location.longitude)}"
                }
                DiagnosticsLogger.i(DIAG, "MOCK_LOCATION_INJECTED sourceProvider=${fix.provider} " +
                    "fixTimeMs=$fixTimeMs appRecvMs=$appRecvMs ageMs=${appRecvMs - fixTimeMs} " +
                    "backdatedMs=${backdatedMs ?: "none(implausible age)"} " +
                    // No heights either: they are coordinates.
                    "altitude=${if (location.hasAltitude()) "set" else "none"} " +
                    "antennaAboveCameraM=${"%.2f".format(antennaAboveCameraM)} " +
                    "hAccM=${fix.hAccM?.let { "%.2f".format(it) } ?: "unknown"}" +
                    (if (hAccWasFallback) "(placeholder ${location.accuracy}m injected)" else "") +
                    " vAccM=${fix.vAccM?.let { "%.2f".format(it) } ?: "unknown"} rtk=${fix.rtkStatus} " +
                    "injections=${MockInjectionStatus.injectionCount}")
            }
            injectFailureLog.reset()
        } catch (e: SecurityException) {
            logInjectFailure("not_permitted", e) {
                "MOCK_LOCATION injection blocked — app not selected as mock location app: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            }
            MockInjectionStatus.providerActive = false
            _errorEvents.tryEmit(MockLocationError.NOT_PERMITTED)
        } catch (e: IllegalArgumentException) {
            logInjectFailure("provider_error", e) {
                "MOCK_LOCATION test provider configuration error: ${e.javaClass.simpleName}: ${e.message}"
            }
            _errorEvents.tryEmit(MockLocationError.PROVIDER_ERROR)
        }
    }

    /*
     * These failures repeat on every fix while AR keeps publishing enabled. Log the first one with its
     * stack trace, then at most once per minute without it.
     */
    private val injectFailureLog = FailureLogThrottle(INJECT_FAILURE_LOG_INTERVAL_MS)

    private inline fun logInjectFailure(kind: String, e: Throwable, message: () -> String) {
        when (injectFailureLog.decide(kind)) {
            FailureLogThrottle.Mode.FULL -> DiagnosticsLogger.w(DIAG, message(), e)
            FailureLogThrottle.Mode.BRIEF ->
                DiagnosticsLogger.w(DIAG, message() + " (still failing; repeats suppressed 60 s)")
            FailureLogThrottle.Mode.NONE -> Unit
        }
    }

    @SuppressLint("WrongConstant")
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

    /**
     * [providerAdded] lives only in memory. If the previous process died while the GPS test provider
     * was registered (killed in the background, crash), Android can keep GPS_PROVIDER mocked with no
     * one injecting — internal GNSS would then sit on "waiting" until reboot. Remove any leftover
     * provider once at start; it is re-added on demand if publishing is needed. Failures are expected
     * (no leftover → IllegalArgumentException; app not the mock-location app → SecurityException).
     */
    private fun removeLeftoverTestProvider() {
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            DiagnosticsLogger.i(DIAG, "MOCK_LOCATION_CLEANUP — removed a leftover GPS test provider from a previous run")
        } catch (_: IllegalArgumentException) {
            // No leftover provider — the normal case.
        } catch (_: SecurityException) {
            // Not selected as the mock location app: this app could not have left one behind.
        } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "MOCK_LOCATION_CLEANUP failed: ${e.message}")
        }
        providerAdded = false
        MockInjectionStatus.providerActive = false
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
            resetFreshness()  // the next publishing session builds its own timing baseline
            lastPublishedUtc = Instant.EPOCH  // …and its own duplicate check
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
        private const val DIAG = "GNSS"
        /** Minimum spacing between per-injection export logs (status still updates every fix). */
        private const val INJECT_LOG_INTERVAL_MS = 3_000L
        /** Repeated injection failures are logged at most this often after the first. */
        private const val INJECT_FAILURE_LOG_INTERVAL_MS = 60_000L
        /** Fix ages beyond this (or negative = device clock ahead) are treated as clock skew, not latency. */
        internal const val MAX_TRUSTED_FIX_AGE_MS = 1_000L

        /** Drop fixes delivered more than 10 seconds later than the stream's normal delay. */
        internal const val MAX_FIX_AGE_MS = 10_000L

        /** Consecutive stale fixes after which the timing baseline is rebuilt (clock jump recovery). */
        internal const val STALE_RESET_COUNT = 5

        /** A fix this far (s) behind the last published one resets the duplicate check (time jump). */
        internal const val TIME_JUMP_RESET_SEC = 60L
    }
}
