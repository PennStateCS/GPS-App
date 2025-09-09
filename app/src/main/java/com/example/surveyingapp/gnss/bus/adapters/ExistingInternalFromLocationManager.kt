package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.gnss.bus.SourceAdapter
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Internal (phone GNSS) adapter that forwards the app's existing fixFlow into the bus.
 * We start this adapter only when LocationSourceType.INTERNAL is active, so fixFlow
 * will represent the phone GNSS path at that time.
 *
 * Notes:
 * - timeUtc uses the current clock because the legacy fix model doesn’t expose UTC.
 * - RTK is set to NONE for phone GNSS.
 * - Fields not present on the legacy model are left null.
 */
class ExistingInternalFromLocationManager(
    private val scope: CoroutineScope
) : SourceAdapter {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val fixes: SharedFlow<Fix> = _fixes

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            // Forward internal fixes. Switchboard will only bind this when INTERNAL is selected.
            SurveyingApp.locationManager.fixFlow.collect { f ->
                _fixes.emit(
                    Fix(
                        provider = Provider.INTERNAL,
                        timeUtc = Instant.now(),          // no UTC provenance on legacy internal fix
                        timeSource = TimeSource.SYSTEM,
                        latDeg = f.lat ?: 0.0,
                        lonDeg = f.lon ?: 0.0,
                        altEllipsoidalM = f.altEllipsoidalM,
                        altMslM = f.altMslM,
                        geoidSeparationM = f.geoidSeparationM,
                        hDop = f.hdop,
                        vDop = null,
                        pDop = f.pdop,
                        hAccM = f.hAccM,
                        vAccM = f.vAccM,
                        rtkStatus = RtkStatus.NONE,       // phone GNSS isn’t RTK
                        satsUsed = f.satsUsed ?: 0,
                        satsVisible = f.satsVisible,
                        diffAgeS = null,
                        speedMps = f.speedMps,
                        courseDeg = null
                    )
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
