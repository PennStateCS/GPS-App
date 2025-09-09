package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.data.location.nmea.parser.NmeaParser
import com.example.surveyingapp.domain.model.RtkStatus as DomainRtk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * ExistingNmeaPipeline implementation that reuses the app's current manager:
 *   - fix snapshots: SurveyingApp.locationManager.fixFlow
 *   - sky events:    SurveyingApp.locationManager.skyFlow  (sat list + usedPrns)
 *
 * This avoids wiring the raw TCP/BT connector and parser here. Once the new bus
 * is fully adopted, you can replace this with a lower-level binding if desired.
 */
class ExistingNmeaFromLocationManager(
    private val scope: CoroutineScope
) : ExistingNmeaPipeline {

    private val _fixes = MutableSharedFlow<FixSnapshot>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _gsv = MutableSharedFlow<GsvEvent>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var jobFix: Job? = null
    private var jobSky: Job? = null

    override fun start() {
        if (jobFix != null || jobSky != null) return

        // Forward your existing fixFlow into bridge-level FixSnapshot
        jobFix = scope.launch(Dispatchers.Default) {
            SurveyingApp.locationManager.fixFlow.collect { f ->
                _fixes.emit(
                    FixSnapshot(
                        timestampMillis = null,                 // Not exposed in fragment; we can leave null
                        timestampSource = null,                 // Same; external parser knows but manager may not expose
                        lat = f.lat,
                        lon = f.lon,
                        altMsl = f.altMslM,
                        geoidSeparation = f.geoidSeparationM,
                        altEllipsoidal = f.altEllipsoidalM,
                        speedMps = f.speedMps,
                        courseDeg = null,
                        hdop = f.hdop,
                        vdop = null,
                        pdop = f.pdop,
                        hAcc = f.hAccM,
                        vAcc = f.vAccM,
                        fixType = when (f.rtkStatus) {
                            DomainRtk.FIX   -> FixType.RTK_FIXED
                            DomainRtk.FLOAT -> FixType.RTK_FLOAT
                            DomainRtk.DGPS  -> FixType.DGPS
                            else            -> FixType.NONE
                        },
                        satsUsed = f.satsUsed,
                        satsVisible = f.satsVisible,
                        diffAgeSec = f.diffAge?.let { d: Duration ->
                            d.toDouble(DurationUnit.MILLISECONDS) / 1000.0
                        }
                    )
                )
            }
        }

        // Build per-constellation "GSV-like" events from skyFlow for the satellite inventory
        jobSky = scope.launch(Dispatchers.Default) {
            SurveyingApp.locationManager.skyFlow.collect { sky ->
                val used = sky.usedPrns ?: emptySet<Int>()
                // Group satellites by constellation so inventory can tally used/visible per band
                sky.satellites.groupBy { it.constellation }.forEach { (const, sats) ->
                    _gsv.emit(
                        GsvEvent(
                            constellation = const.toBridgeConstellation(),
                            sats = sats.map { s ->
                                GsvSat(
                                    svid = s.prn,
                                    elevationDeg = s.elevationDeg,
                                    azimuthDeg = s.azimuthDeg,
                                    snrDbHz = (s.snrDb ?: 0).toDouble(),
                                    usedInFix = used.contains(s.prn)
                                )
                            }
                        )
                    )
                }
            }
        }
    }

    override fun stop() {
        jobFix?.cancel(); jobFix = null
        jobSky?.cancel(); jobSky = null
    }

    override fun fixSnapshots(): SharedFlow<FixSnapshot> = _fixes.asSharedFlow()
    override fun gsvEvents(): SharedFlow<GsvEvent> = _gsv.asSharedFlow()

    // Map your parser's constellation enum to a short tag the inventory understands
    private fun NmeaParser.Constellation.toBridgeConstellation(): String = when (this) {
        NmeaParser.Constellation.GPS     -> "GPS"
        NmeaParser.Constellation.GLONASS -> "GLO"
        NmeaParser.Constellation.GALILEO -> "GAL"
        NmeaParser.Constellation.BEIDOU  -> "BDS"
        NmeaParser.Constellation.QZSS    -> "QZSS"
        NmeaParser.Constellation.SBAS    -> "SBAS"
        NmeaParser.Constellation.IRNSS   -> "IRNSS"
        else                             -> "UNK"
    }
}
