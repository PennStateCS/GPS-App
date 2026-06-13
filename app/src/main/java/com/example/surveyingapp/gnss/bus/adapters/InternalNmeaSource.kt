package com.example.surveyingapp.gnss.bus.adapters

import android.content.Context
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.Looper
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

    override fun parsedFixes(): SharedFlow<Fix> = _fixes

    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv

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

    override fun start() {
        if (started) return

        android.util.Log.d(TAG, "Starting internal GNSS NMEA listener")

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

        nmeaListener = null
        started = false

        /*
         * The fuser carries sentence state across messages. Reset it so the next start
         * does not combine new receiver output with stale fields from a previous run.
         */
        fuser.reset()

        android.util.Log.d(TAG, "Internal NMEA listener removed")
    }
}