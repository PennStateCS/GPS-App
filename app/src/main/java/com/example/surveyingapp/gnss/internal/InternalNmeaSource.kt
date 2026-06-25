package com.example.surveyingapp.gnss.internal

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.Looper
import com.example.surveyingapp.gnss.bus.adapters.GsvMessage
import com.example.surveyingapp.gnss.bus.adapters.NmeaFuser
import com.example.surveyingapp.gnss.bus.adapters.NmeaSource
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
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
    registry: NmeaRegistry,
    private val diagnostics: DiagnosticsService? = null
) : NmeaSource {

    private companion object {
        const val TAG = "InternalNmeaSource"
    }

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

    private val fuser = NmeaFuser(
        provider = Provider.INTERNAL,
        registry = registry,
        onFix = { fix ->
            scope.launch {
                _fixes.emit(fix)
            }
        },
        onGsv = { gsv ->
            scope.launch {
                _gsv.emit(gsv)
            }
        }
    )

    private var started = false
    private var nmeaListener: OnNmeaMessageListener? = null

    /*
     * Dummy LocationListener whose sole purpose is to keep the GPS hardware active.
     * Android may not deliver NMEA sentences unless there is at least one active
     * location request on the GPS provider; addNmeaListener() alone is insufficient
     * on some devices and OS versions.
     */
    private val gpsActivationListener = LocationListener { /* position arrives via NMEA listener */ }

    override fun start() {
        if (started) return

        android.util.Log.d(TAG, "Starting internal GNSS NMEA listener")

        /*
         * Request GPS location updates to ensure the hardware stays active. The actual
         * position data is consumed through the NMEA listener below; this listener is
         * a no-op that exists only to keep the provider running.
         */
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                gpsActivationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Missing location permission for GPS activation", e)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not request GPS updates: ${e.message}")
        }

        val listener = OnNmeaMessageListener { message, _ ->
            /*
             * Diagnostics records the raw receiver stream before fusion. This makes
             * parser and receiver issues easier to inspect without changing runtime
             * behavior for the normal fix pipeline.
             */
            diagnostics?.recordLine(message)
            fuser.accept(message)
        }

        nmeaListener = listener

        try {
            /*
             * Register on the main looper because Android's NMEA listener API expects
             * a Handler-backed callback thread. Mark the source started only after
             * registration succeeds so permission failures can be retried later.
             */
            lm.addNmeaListener(listener, Handler(Looper.getMainLooper()))
            started = true

            android.util.Log.d(TAG, "Internal NMEA listener registered")
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Missing location permission for internal NMEA listener", e)

            /*
             * Roll back the GPS activation request since we can't receive NMEA anyway.
             */
            runCatching { lm.removeUpdates(gpsActivationListener) }

            /*
             * Leave started=false and clear the listener reference. The adapter can call
             * start() again after location permission is granted.
             */
            nmeaListener = null
        }
    }

    override fun stop() {
        nmeaListener?.let { listener ->
            runCatching {
                lm.removeNmeaListener(listener)
            }
        }

        runCatching { lm.removeUpdates(gpsActivationListener) }

        nmeaListener = null
        started = false

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
    }
}