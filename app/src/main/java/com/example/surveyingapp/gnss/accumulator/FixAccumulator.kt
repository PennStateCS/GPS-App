package com.example.surveyingapp.gnss.accumulator

import com.example.surveyingapp.gnss.model.TimestampSource
import com.example.surveyingapp.gnss.nmea.sentence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of accumulated GNSS fix data from multiple NMEA sentences.
 * Parsing remains pure; all fusion/derivation happens here.
 */
data class FixSnapshot(
    val timestampMillis: Long,
    val timestampSource: TimestampSource,
    val lat: Double?,
    val lon: Double?,
    val altMsl: Double?,
    val geoidSeparation: Double?,
    val altEllipsoidal: Double?,     // Derived as altMsl + geoid when both finite
    val speedMps: Double?,
    val courseDeg: Double?,
    val satsUsed: Int?,
    val hdop: Double?,
    val vDop: Double?,              // NEW: Vertical dilution of precision
    val pDop: Double?,              // NEW: Position dilution of precision
    val satellitesInView: Int?,
    val horizontalAccuracyM: Double?,  // NEW: Horizontal accuracy in meters
    val verticalAccuracyM: Double?,    // NEW: Vertical accuracy in meters
    val correctionAgeS: Double?,       // NEW: Age of differential correction
    val correctionStationId: String?, // NEW: Correction station ID
    val multipathIndex: Double?,       // NEW: Multipath index
    val rtkStatus: String?,            // NEW: RTK status
    val stdLatM: Double?,              // NEW: Standard deviation of latitude (meters)
    val stdLonM: Double?,              // NEW: Standard deviation of longitude (meters)
    val stdAltM: Double?               // NEW: Standard deviation of altitude (meters)
)

class FixAccumulator {

    private val _state = MutableStateFlow(
        FixSnapshot(
            timestampMillis = System.currentTimeMillis(),
            timestampSource = TimestampSource.DEVICE,
            lat = null,
            lon = null,
            altMsl = null,
            geoidSeparation = null,
            altEllipsoidal = null,
            speedMps = null,
            courseDeg = null,
            satsUsed = null,
            hdop = null,
            vDop = null,
            pDop = null,
            satellitesInView = null,
            horizontalAccuracyM = null,
            verticalAccuracyM = null,
            correctionAgeS = null,
            correctionStationId = null,
            multipathIndex = null,
            rtkStatus = null,
            stdLatM = null,
            stdLonM = null,
            stdAltM = null
        )
    )
    val state: StateFlow<FixSnapshot> = _state.asStateFlow()

    /**
     * Accept an NMEA sentence and update the fused fix state.
     * This function must never throw; tolerate malformed or partial data.
     */
    fun accept(sentence: NmeaSentence) {
        val current = _state.value

        when (sentence) {
            is GGA -> {
                // Prefer HDOP from GGA
                val newHdop = sentence.hdop ?: current.hdop
                val newAltMsl = sentence.altMsl ?: current.altMsl
                val newGeoid = sentence.geoidSeparation ?: current.geoidSeparation
                val derivedEllipsoidal = sumIfFinite(newAltMsl, newGeoid)

                _state.value = current.copy(
                    lat = sentence.lat ?: current.lat,
                    lon = sentence.lon ?: current.lon,
                    satsUsed = sentence.satsUsed ?: current.satsUsed,
                    hdop = newHdop,
                    altMsl = newAltMsl,
                    geoidSeparation = newGeoid,
                    altEllipsoidal = derivedEllipsoidal,
                    correctionAgeS = sentence.diffAge ?: current.correctionAgeS,
                    correctionStationId = sentence.stationId ?: current.correctionStationId,
                    rtkStatus = mapFixQualityToRtkStatus(sentence.fixQuality) ?: current.rtkStatus
                )
            }

            is RMC -> {
                // Convert knots → m/s if present
                val speedMps = sentence.speedKnots?.let { it * 0.514444 }
                _state.value = current.copy(
                    speedMps = speedMps ?: current.speedMps,
                    courseDeg = sentence.courseDeg ?: current.courseDeg
                )
            }

            is GSA -> {
                // Extract all DOP values from GSA - this is the primary source for DOP data
                _state.value = current.copy(
                    hdop = sentence.hdop ?: current.hdop,
                    vDop = sentence.vdop ?: current.vDop,
                    pDop = sentence.pdop ?: current.pDop,
                    satsUsed = if (sentence.usedSvids.isNotEmpty()) sentence.usedSvids.size else current.satsUsed
                )
            }

            is GSV -> {
                // Track satellites-in-view from the aggregated GSV set
                _state.value = current.copy(
                    satellitesInView = sentence.totalSatellites ?: current.satellitesInView
                )
            }

            is ZDA -> {
                // Use receiver UTC time when available; otherwise a single device-time fallback
                val fallbackNow = System.currentTimeMillis()
                val (ts, source) = if (sentence.epochMillis != null) {
                    sentence.epochMillis to TimestampSource.NMEA_ZDA
                } else {
                    fallbackNow to TimestampSource.DEVICE
                }
                _state.value = current.copy(
                    timestampMillis = ts,
                    timestampSource = source
                )
            }

            is GST -> {
                // Extract accuracy information from GPS Error Statistics
                val horizontalAccuracy = calculateHorizontalAccuracy(sentence.stdDevMajor, sentence.stdDevMinor)
                _state.value = current.copy(
                    horizontalAccuracyM = horizontalAccuracy ?: current.horizontalAccuracyM,
                    verticalAccuracyM = sentence.stdDevAlt ?: current.verticalAccuracyM,
                    stdLatM = sentence.stdDevLat ?: current.stdLatM,
                    stdLonM = sentence.stdDevLon ?: current.stdLonM,
                    stdAltM = sentence.stdDevAlt ?: current.stdAltM
                )
            }

            // Add other sentence types here as you introduce them (e.g., proprietary $P...)
            else -> {
                // No-op for unsupported sentences; keep accumulator tolerant.
            }
        }
    }

    private fun sumIfFinite(a: Double?, b: Double?): Double? {
        return if (a.isFinite() && b.isFinite()) a!! + b!! else null
    }

    private fun Double?.isFinite(): Boolean = this != null && this.isFinite()

    /**
     * Map GGA fix quality values to RTK status strings.
     * GGA fix quality: 0=invalid, 1=GPS, 2=DGPS, 4=RTK fixed, 5=RTK float, etc.
     */
    private fun mapFixQualityToRtkStatus(fixQuality: Int?): String? {
        return when (fixQuality) {
            0 -> "INVALID"
            1 -> "SINGLE"
            2 -> "DGPS"
            4 -> "RTK_FIXED"
            5 -> "RTK_FLOAT"
            6 -> "ESTIMATED"
            else -> null
        }
    }

    /**
     * Calculate 1-sigma circular horizontal accuracy (DRMS) from GST error ellipse semi-axes.
     *
     * The NMEA GST sentence provides [stdDevMajor] (σ_max) and [stdDevMinor] (σ_min) as the
     * semi-axes of the 1-sigma position error ellipse.
     *
     * DRMS (Distance Root Mean Square) = sqrt((σ_major² + σ_minor²) / 2) is the standard
     * single-value horizontal accuracy metric and correctly reflects elongated ellipses.
     *
     * The previous formula sqrt(σ_major * σ_minor) (geometric mean) underestimates accuracy
     * when σ_major >> σ_minor, which is exactly when satellite geometry is poorest.
     */
    private fun calculateHorizontalAccuracy(stdDevMajor: Double?, stdDevMinor: Double?): Double? {
        if (stdDevMajor == null || stdDevMinor == null) return null
        return Math.sqrt((stdDevMajor * stdDevMajor + stdDevMinor * stdDevMinor) / 2.0)
    }
}
