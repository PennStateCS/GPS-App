package app.surrealar.gnss.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.surrealar.gnss.bus.PermissionRetryable
import app.surrealar.gnss.bus.adapters.GsvMessage
import app.surrealar.gnss.bus.adapters.NmeaFuser
import app.surrealar.gnss.bus.adapters.NmeaSource
import app.surrealar.gnss.diagnostics.DiagnosticsService
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.nmea.parse.NmeaRegistry
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Reads NMEA sentences from Android's internal GNSS provider.
 *
 * Android delivers raw NMEA lines through LocationManager. This source forwards
 * those lines into NmeaFuser, then publishes the normalized fixes and GSV updates
 * consumed by InternalAdapter.
 */
class InternalNmeaSource(
    context: Context,
    private val scope: CoroutineScope,
    private val registry: NmeaRegistry,
    private val diagnostics: DiagnosticsService? = null
) : NmeaSource, PermissionRetryable {

    private companion object {
        const val TAG = "InternalNmeaSource"
    }

    // Application context retained for fresh permission checks on every (re)start attempt.
    private val appContext: Context = context.applicationContext
    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _gsv = MutableSharedFlow<GsvMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Internal source does not expose raw NMEA to external status observers.
    private val _emptyRawNmea = MutableSharedFlow<Unit>()

    override fun parsedFixes(): SharedFlow<Fix> = _fixes

    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv

    override fun rawNmeaEvents(): SharedFlow<Unit> = _emptyRawNmea
    override fun nmeaTimingStats() = fuser.timingStats
    override fun nmeaCustomStats() = fuser.nmeaCustomStats

    private val fuser = NmeaFuser(
        provider = Provider.INTERNAL,
        registry = registry,
        onFix = { fix ->
            if (!firstFixLogged) {
                firstFixLogged = true
                DiagnosticsLogger.i("SOURCE", "internal GPS: first fix received " +
                    "(provider=INTERNAL rtk=${fix.rtkStatus} sats=${fix.satsUsed ?: "?"})")
            }
            lastNmeaFixAtMs = SystemClock.elapsedRealtime()
            val fix = fillEllipsoidalHeight(fix)
            // tryEmit keeps receive order (a coroutine per fix on the multi-threaded app scope could
            // reorder them); the DROP_OLDEST buffer means it never fails.
            _fixes.tryEmit(fix)
        },
        onGsv = { gsv ->
            lastNmeaGsvAtMs = SystemClock.elapsedRealtime()
            _gsv.tryEmit(gsv)
        }
    )

    // ── Platform fallback (devices without usable NMEA) — see PlatformFallback ──
    @Volatile private var registeredAtMs = 0L
    @Volatile private var lastNmeaFixAtMs = 0L
    @Volatile private var lastNmeaGsvAtMs = 0L
    @Volatile private var fallbackFixLogged = false
    @Volatile private var fallbackSkyLogged = false
    /** Latest distinct used/visible counts from GnssStatus (null until the first callback). */
    @Volatile private var platformSatsUsed: Int? = null
    @Volatile private var platformSatsVisible: Int? = null

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) = onPlatformStatus(status)
    }
    private var gnssStatusRegistered = false

    /** Latest non-mock platform Location from the same GNSS chip (for same-epoch enrichment). */
    @Volatile private var lastPlatformLocation: PlatformLocation? = null

    /** Fills fields the NMEA lacked from the same epoch's platform Location (see [PlatformFallback.enrich]). */
    private fun fillEllipsoidalHeight(fix: Fix): Fix {
        val out = PlatformFallback.enrich(fix, lastPlatformLocation)
        if (out !== fix) app.surrealar.gnss.debug.GnssDebugLog.every("internal.enrich", "internal") {
            "same-epoch fill from platform Location: " +
                (if (fix.hAccM == null && out.hAccM != null) "hAcc=${out.hAccM} " else "") +
                (if (fix.vAccM == null && out.vAccM != null) "vAcc=${out.vAccM} " else "") +
                (if (fix.altEllipsoidalM == null && out.altEllipsoidalM != null)
                    "ellH=${out.altEllipsoidalM} (msl=${fix.altMslM} → N=${out.geoidSeparationM})" else "")
        }
        return out
    }

    private fun onPlatformLocation(loc: Location) {
        // Never feed our own mock publisher's output (external fixes injected into GPS_PROVIDER)
        // back in as an internal fix.
        val isMock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider
        if (isMock) return
        val snapshot = PlatformLocation(
            timeMs = loc.time,
            latDeg = loc.latitude,
            lonDeg = loc.longitude,
            altEllipsoidalM = if (loc.hasAltitude()) loc.altitude else null,
            altMslM = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters else null,
            hAccM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            vAccM = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            satellitesExtra = loc.extras?.getInt("satellites", -1)?.takeIf { it >= 0 },
        )
        lastPlatformLocation = snapshot
        if (!PlatformFallback.nmeaSilent(SystemClock.elapsedRealtime(), registeredAtMs, lastNmeaFixAtMs)) return
        if (!fallbackFixLogged) {
            fallbackFixLogged = true
            DiagnosticsLogger.w("SOURCE", "internal GPS: no NMEA fixes from this device — using platform Location fallback")
        }
        val fix = PlatformFallback.toFix(snapshot, platformSatsUsed, platformSatsVisible)
        app.surrealar.gnss.debug.GnssDebugLog.every("internal.fallbackFix", "internal") {
            "platform-Location fallback fix (NMEA silent): lat=${fix.latDeg} lon=${fix.lonDeg} " +
                "ellH=${fix.altEllipsoidalM} msl=${fix.altMslM} hAcc=${fix.hAccM} sats=${fix.satsUsed}/${fix.satsVisible}"
        }
        if (!firstFixLogged) {
            firstFixLogged = true
            DiagnosticsLogger.i("SOURCE", "internal GPS: first fix received (platform fallback, sats=${fix.satsUsed})")
        }
        _fixes.tryEmit(fix)
    }

    private fun onPlatformStatus(status: GnssStatus) {
        val sats = (0 until status.satelliteCount).map { i ->
            PlatformSatellite(
                constellation = when (status.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> "GP"
                    GnssStatus.CONSTELLATION_GLONASS -> "GL"
                    GnssStatus.CONSTELLATION_GALILEO -> "GA"
                    GnssStatus.CONSTELLATION_BEIDOU -> "GB"
                    GnssStatus.CONSTELLATION_QZSS -> "GQ"
                    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                    GnssStatus.CONSTELLATION_IRNSS -> "GI"
                    else -> "UNKNOWN"
                },
                svid = status.getSvid(i),
                elevationDeg = status.getElevationDegrees(i),
                azimuthDeg = status.getAzimuthDegrees(i),
                cn0DbHz = status.getCn0DbHz(i),
                usedInFix = status.usedInFix(i),
            )
        }
        val (used, visible) = PlatformFallback.counts(sats)
        platformSatsUsed = used
        platformSatsVisible = visible
        if (!PlatformFallback.nmeaSilent(SystemClock.elapsedRealtime(), registeredAtMs, lastNmeaGsvAtMs)) return
        if (!fallbackSkyLogged) {
            fallbackSkyLogged = true
            DiagnosticsLogger.w("SOURCE", "internal GPS: no NMEA GSV from this device — using platform GnssStatus for satellites")
        }
        PlatformFallback.toGsvMessages(sats).forEach { _gsv.tryEmit(it) }
    }

    private var started = false
    private var nmeaListener: OnNmeaMessageListener? = null
    /**
     * True when a start attempt was blocked because location permission was missing/not-ready.
     * While this is set, [retryIfAwaitingPermission] will re-attempt registration once permission
     * is available (on app resume, on permission grant, etc.). Cleared once active or stopped.
     */
    @Volatile private var awaitingPermission = false
    /** Logs only the first usable fix per registration so the export shows the provider produced data. */
    @Volatile private var firstFixLogged = false

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /*
     * Dummy LocationListener whose sole purpose is to keep the GPS hardware active.
     * Android may not deliver NMEA sentences unless there is at least one active
     * location request on the GPS provider; addNmeaListener() alone is insufficient
     * on some devices and OS versions.
     */
    private val gpsActivationListener = object : LocationListener {
        // Position normally arrives via the NMEA listener; this is only used when NMEA is silent.
        override fun onLocationChanged(location: Location) = onPlatformLocation(location)

        // Must be overridden explicitly: on API 24–29 these are ABSTRACT in the framework (defaults
        // only arrived in API 30), so a lambda listener crashes with AbstractMethodError the first
        // time Android calls one (e.g. the user turns Location off).
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun start() {
        if (started) return

        android.util.Log.d(TAG, "Starting internal GNSS NMEA listener")
        DiagnosticsLogger.i("SOURCE", "internal GPS: start requested " +
            "(locationPermission=${if (hasLocationPermission()) "granted" else "MISSING"})")

        // Fresh permission check FIRST. Previously start() called requestLocationUpdates/
        // addNmeaListener and caught the SecurityException, but left the provider stuck with no
        // retry. Now a missing permission records a WAITING state that retryIfAwaitingPermission()
        // recovers from — no provider restart / source switch required.
        if (!hasLocationPermission()) {
            awaitingPermission = true
            DiagnosticsLogger.w("SOURCE",
                "internal GPS: WAITING — location permission not granted; will retry on resume/grant")
            return
        }

        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            DiagnosticsLogger.w("SOURCE", "internal GPS: GPS_PROVIDER is disabled in system settings")
        }
        registerListeners()
    }

    /**
     * Registers the GPS-activation request and the NMEA listener, each guarded by a fresh permission
     * check. Idempotent w.r.t. duplicate listeners: any previously-registered listener/updates are
     * removed first, so a retry can never leave two listeners attached. On a permission failure it
     * records the WAITING state instead of getting stuck.
     */
    private fun registerListeners() {
        firstFixLogged = false
        registeredAtMs = SystemClock.elapsedRealtime()
        lastNmeaFixAtMs = 0L; lastNmeaGsvAtMs = 0L
        fallbackFixLogged = false; fallbackSkyLogged = false
        platformSatsUsed = null; platformSatsVisible = null

        // Defensive cleanup so retries can never accumulate duplicate listeners.
        nmeaListener?.let { runCatching { lm.removeNmeaListener(it) } }
        runCatching { lm.removeUpdates(gpsActivationListener) }
        unregisterGnssStatus()
        nmeaListener = null

        /*
         * Request GPS location updates to keep the hardware active. The actual position data is
         * consumed through the NMEA listener below; this listener is a no-op that exists only to
         * keep the provider running.
         */
        try {
            if (!hasLocationPermission()) { markWaiting("permission lost before requestLocationUpdates"); return }
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                gpsActivationListener,
                Looper.getMainLooper()
            )
            DiagnosticsLogger.i("SOURCE", "internal GPS: requestLocationUpdates registered")
        } catch (e: SecurityException) {
            markWaiting("requestLocationUpdates blocked by permission", e)
            return
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not request GPS updates: ${e.message}")
            DiagnosticsLogger.w("SOURCE", "internal GPS: requestLocationUpdates failed " +
                "(non-permission): ${e.javaClass.simpleName} ${e.message}")
            // Continue — on some devices/OS versions the NMEA listener alone still delivers data.
        }

        val listener = OnNmeaMessageListener { message, _ ->
            // Diagnostics records the raw receiver stream before fusion.
            diagnostics?.recordLine(message)
            fuser.accept(message)
            debugSampler.accept(message) { "GnssStatus used=$platformSatsUsed visible=$platformSatsVisible" }
        }

        try {
            if (!hasLocationPermission()) {
                runCatching { lm.removeUpdates(gpsActivationListener) }
                markWaiting("permission lost before addNmeaListener"); return
            }
            // Register on the main looper because Android's NMEA listener API expects a
            // Handler-backed callback thread.
            lm.addNmeaListener(listener, Handler(Looper.getMainLooper()))
            nmeaListener = listener
            started = true
            awaitingPermission = false
            android.util.Log.d(TAG, "Internal NMEA listener registered")
            DiagnosticsLogger.i("SOURCE", "internal GPS: ACTIVE — NMEA listener registered, awaiting first fix")
        } catch (e: SecurityException) {
            runCatching { lm.removeUpdates(gpsActivationListener) }
            nmeaListener = null
            markWaiting("addNmeaListener blocked by permission", e)
            return
        } catch (e: Exception) {
            // Some OEM builds reject/omit NMEA support. The Location + GnssStatus fallback still works.
            nmeaListener = null
            started = true
            awaitingPermission = false
            DiagnosticsLogger.w("SOURCE", "internal GPS: addNmeaListener failed " +
                "(${e.javaClass.simpleName} ${e.message}); running on platform Location fallback")
        }

        // Satellite status for the fallback sky view and the fallback fix's satellite counts.
        try {
            if (hasLocationPermission()) {
                gnssStatusRegistered = lm.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            DiagnosticsLogger.w("SOURCE", "internal GPS: GnssStatus callback unavailable (${e.javaClass.simpleName})")
        }

        requestFullGnssTracking()
    }

    /**
     * Highest internal accuracy: by default many chipsets DUTY-CYCLE GNSS tracking between fixes to
     * save power, which degrades accuracy (and on dual-frequency phones can drop L5). Android 12+
     * lets an app request FULL tracking (no duty cycling, all constellations/frequencies) by
     * registering a raw-measurement listener with `setFullTracking(true)` — the same effect as the
     * "Force full GNSS measurements" developer option. Only active while the internal source runs.
     * Older Android has no API for this; the chip's default mode is used.
     */
    private fun requestFullGnssTracking() {
        if (Build.VERSION.SDK_INT < 31 || !hasLocationPermission()) return
        try {
            val request = android.location.GnssMeasurementRequest.Builder().setFullTracking(true).build()
            fullTrackingRegistered = lm.registerGnssMeasurementsCallback(
                request, ContextCompat.getMainExecutor(appContext), fullTrackingCallback
            )
            DiagnosticsLogger.i("SOURCE", "internal GPS: full GNSS tracking requested (registered=$fullTrackingRegistered)")
        } catch (e: Exception) {
            DiagnosticsLogger.w("SOURCE", "internal GPS: full GNSS tracking unavailable (${e.javaClass.simpleName} ${e.message})")
        }
    }

    /** Measurements themselves are unused; the registration is what keeps tracking at full duty. */
    private val fullTrackingCallback = object : android.location.GnssMeasurementsEvent.Callback() {}
    private var fullTrackingRegistered = false

    /** Debug builds: periodic raw-sentence samples with Android's own satellite counts (logcat only). */
    private val debugSampler = app.surrealar.gnss.debug.NmeaDebugSampler("nmea-int")

    private fun unregisterGnssStatus() {
        if (gnssStatusRegistered) runCatching { lm.unregisterGnssStatusCallback(gnssStatusCallback) }
        gnssStatusRegistered = false
        if (fullTrackingRegistered) runCatching { lm.unregisterGnssMeasurementsCallback(fullTrackingCallback) }
        fullTrackingRegistered = false
    }

    /** Records the blocked-on-permission WAITING state so a later retry can recover. */
    private fun markWaiting(why: String, e: Throwable? = null) {
        started = false
        awaitingPermission = true
        android.util.Log.w(TAG, "internal GPS waiting: $why", e)
        DiagnosticsLogger.w("SOURCE", "internal GPS: WAITING — $why; will retry on resume/grant", e)
    }

    /**
     * Re-attempts a start that was blocked waiting for location permission. No-op when already
     * active (prevents duplicate listeners) or when not waiting. Called on app resume and when
     * permission is granted.
     */
    override fun retryIfAwaitingPermission(reason: String) {
        if (started) return
        if (!awaitingPermission) return
        if (!hasLocationPermission()) {
            DiagnosticsLogger.d("SOURCE", "internal GPS: retry skipped — still no permission (reason=$reason)")
            return
        }
        DiagnosticsLogger.i("SOURCE", "internal GPS: RETRYING start (reason=$reason)")
        registerListeners()
    }

    override fun stop() {
        nmeaListener?.let { listener ->
            runCatching {
                lm.removeNmeaListener(listener)
            }
        }

        runCatching { lm.removeUpdates(gpsActivationListener) }
        unregisterGnssStatus()

        nmeaListener = null
        started = false
        awaitingPermission = false

        /*
         * The fuser carries sentence state across messages. Reset it so the next start
         * does not combine new receiver output with stale fields from a previous run.
         */
        fuser.reset()

        /*
         * Drop the replay=1 buffered fix. Otherwise, when this source is restarted
         * (e.g. after switching provider away and back), the adapter's fresh collector
         * immediately receives the OLD fix from before the switch and republishes it as
         * if it were live. Clearing here keeps a restart from resurrecting stale data.
         */
        _fixes.resetReplayCache()

        android.util.Log.d(TAG, "Internal NMEA listener removed")
        DiagnosticsLogger.i("SOURCE", "internal GPS: NMEA listener removed")
        // Emit a final NMEA parse summary so a poor/unparseable stream is visible in the export.
        registry.logParseSummary("internal stream stopped")
    }
}