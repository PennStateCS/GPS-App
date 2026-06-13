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
 * Wires Android's internal GPS NMEA listener into the [InternalAdapter].
 *
 * NMEA callbacks arrive on the main thread (via [Handler(Looper.getMainLooper())]).
 * Sentence fusion is delegated to [NmeaFuser]; the shared [NmeaRegistry] instance
 * from DI is reused so no extra parser allocations occur.
 */
class InternalNmeaSource(
    context: Context,
    private val scope: CoroutineScope,
    registry: NmeaRegistry,
    private val diagnostics: DiagnosticsService? = null
) : NmeaSource {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _gsv = MutableSharedFlow<GsvMessage>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun parsedFixes(): SharedFlow<Fix> = _fixes
    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv

    private val fuser = NmeaFuser(
        provider = Provider.INTERNAL,
        registry = registry,
        onFix    = { fix -> scope.launch { _fixes.emit(fix) } },
        onGsv    = { gsv -> scope.launch { _gsv.emit(gsv) } }
    )

    private var started = false
    private var nmeaListener: OnNmeaMessageListener? = null

    override fun start() {
        if (started) return
        android.util.Log.d("InternalNmeaSource", "Starting internal GPS NMEA listener")
        val listener = OnNmeaMessageListener { message, _ ->
            diagnostics?.recordLine(message)
            fuser.accept(message)
        }
        nmeaListener = listener
        try {
            lm.addNmeaListener(listener, Handler(Looper.getMainLooper()))
            started = true  // Only set after successful registration
            android.util.Log.d("InternalNmeaSource", "NMEA listener added successfully")
        } catch (e: SecurityException) {
            android.util.Log.e("InternalNmeaSource", "Missing location permission", e)
            nmeaListener = null  // Clear listener reference on failure
            // started remains false so retry is possible
        }
    }

    override fun stop() {
        nmeaListener?.let { runCatching { lm.removeNmeaListener(it) } }
        nmeaListener = null
        started = false
        fuser.reset()
        android.util.Log.d("InternalNmeaSource", "NMEA listener removed")
    }
}
