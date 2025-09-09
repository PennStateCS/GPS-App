// app/src/main/java/com/example/surveyingapp/gnss/bus/adapters/NmeaSourceBridge.kt
package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Glue between the new bus and the existing RS2 NMEA pipeline.
 * Kicks the upstream, maps its snapshots/events to the new models, and exposes clean flows.
 */
class NmeaSourceBridge(
    private val scope: CoroutineScope,
    private val upstream: ExistingNmeaPipeline
) : NmeaSource {

    private val _fixes = MutableSharedFlow<Fix>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _gsv = MutableSharedFlow<GsvMessage>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var jobFix: kotlinx.coroutines.Job? = null
    private var jobGsv: kotlinx.coroutines.Job? = null

    override fun start() {
        if (jobFix != null || jobGsv != null) return
        upstream.start()

        jobFix = scope.launch(Dispatchers.Default) {
            upstream.fixSnapshots().collect { s -> _fixes.emit(s.toFix()) }
        }
        jobGsv = scope.launch(Dispatchers.Default) {
            upstream.gsvEvents().collect { e ->
                _gsv.emit(
                    GsvMessage(
                        constellation = e.constellation,
                        entries = e.sats.map { GsvEntry(it.svid, it.elevationDeg, it.azimuthDeg, it.snrDbHz, it.usedInFix) }
                    )
                )
            }
        }
    }

    override fun stop() {
        jobFix?.cancel(); jobFix = null
        jobGsv?.cancel(); jobGsv = null
        upstream.stop()
    }

    override fun parsedFixes(): SharedFlow<Fix> = _fixes.asSharedFlow()
    override fun gsvStream(): SharedFlow<GsvMessage> = _gsv.asSharedFlow()
}

/** Upstream facade implemented by your existing RS2 code (next section). */
interface ExistingNmeaPipeline {
    fun start()
    fun stop()
    fun fixSnapshots(): SharedFlow<FixSnapshot>
    fun gsvEvents(): SharedFlow<GsvEvent>
}

/** Bridge-level DTOs mirroring what your accumulator already exposes. */
data class FixSnapshot(
    val timestampMillis: Long?,
    val timestampSource: String?,   // "RMC" | "ZDA" | "GGA_DATE" | null
    val lat: Double?, val lon: Double?,
    val altMsl: Double?, val geoidSeparation: Double?, val altEllipsoidal: Double?,
    val speedMps: Double?, val courseDeg: Double?,
    val hdop: Double?, val vdop: Double?, val pdop: Double?,
    val hAcc: Double?, val vAcc: Double?,
    val fixType: FixType,           // NONE | DGPS | RTK_FLOAT | RTK_FIXED
    val satsUsed: Int?, val satsVisible: Int?,
    val diffAgeSec: Double?
)
enum class FixType { NONE, DGPS, RTK_FLOAT, RTK_FIXED }

data class GsvEvent(
    val constellation: String,      // "GPS" | "GAL" | "GLO" | "BDS" | "QZSS" | "SBAS"
    val sats: List<GsvSat>
)
data class GsvSat(
    val svid: Int,
    val elevationDeg: Int?,
    val azimuthDeg: Int?,
    val snrDbHz: Double,
    val usedInFix: Boolean
)

/** Map the bridge snapshot into the normalized Fix used by the new bus/UI. */
private fun FixSnapshot.toFix(): Fix {
    return Fix(
        provider = Provider.RS2_EXTERNAL,
        timeUtc = Instant.ofEpochMilli(timestampMillis ?: System.currentTimeMillis()),
        timeSource = when (timestampSource) {
            "RMC" -> TimeSource.RMC
            "ZDA" -> TimeSource.ZDA
            "GGA_DATE" -> TimeSource.GGA_DATE
            else -> TimeSource.SYSTEM
        },
        latDeg = lat ?: 0.0,
        lonDeg = lon ?: 0.0,
        altEllipsoidalM = altEllipsoidal,
        altMslM = altMsl,
        geoidSeparationM = geoidSeparation,
        hDop = hdop,
        vDop = vdop,
        pDop = pdop,
        hAccM = hAcc,
        vAccM = vAcc,
        rtkStatus = when (fixType) {
            FixType.RTK_FIXED -> RtkStatus.FIX
            FixType.RTK_FLOAT -> RtkStatus.FLOAT
            FixType.DGPS -> RtkStatus.DGPS
            FixType.NONE -> RtkStatus.NONE
        },
        satsUsed = satsUsed ?: 0,
        satsVisible = satsVisible,
        diffAgeS = diffAgeSec,
        speedMps = speedMps,
        courseDeg = courseDeg
    )
}
