package app.surrealar.gnss.accumulator

import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.nmea.sentence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Current fused GNSS state built from the NMEA sentences received so far.
 *
 * Individual sentence parsers should stay narrow and only decode their own fields.
 * This accumulator is responsible for carrying state across sentences and deriving
 * app-level values such as ellipsoidal height, combined satellite counts, and accuracy.
 */
data class FixSnapshot(
    val timestampMillis: Long,
    val timestampSource: TimestampSource,
    val lat: Double?,
    val lon: Double?,
    val altMsl: Double?,
    val geoidSeparation: Double?,
    val altEllipsoidal: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val satsUsed: Int?,
    val hdop: Double?,
    val vDop: Double?,
    val pDop: Double?,
    val satellitesInView: Int?,
    val horizontalAccuracyM: Double?,
    val verticalAccuracyM: Double?,
    val correctionAgeS: Double?,
    val correctionStationId: String?,
    val multipathIndex: Double?,
    val rtkStatus: String?,
    val stdLatM: Double?,
    val stdLonM: Double?,
    val stdAltM: Double?
)

/**
 * Merges parsed NMEA sentences into a single current [FixSnapshot] exposed as a [StateFlow].
 *
 * Position comes from GGA/RMC, accuracy from GST, and DOP/used-satellite counts from GSA; this type
 * carries those fields across sentences because no single sentence holds them all. The published
 * snapshot is the latest fused view, not a history — a field that stops being reported goes null
 * rather than retaining a stale value, and consumers must treat an old snapshot as stale by its
 * timestamp. A malformed or partial sentence updates only the fields that parsed.
 */
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

    /*
     * Some receivers emit one GSA sentence per constellation. Keep a short-lived
     * combined set so the displayed "satellites used" count reflects the full fix,
     * not just the most recent GSA sentence.
     */
    private val gsaUsedSvids: MutableSet<Int> = mutableSetOf()
    private var lastGsaUpdateMs: Long = 0L
    private val GSA_EPOCH_TIMEOUT_MS = 2000L

    /**
     * Accepts one parsed NMEA sentence and updates the fused fix state.
     *
     * NMEA data often arrives as partial, repeated, or out-of-order fragments. This
     * method preserves the last known value for fields that are missing in the
     * current sentence and should remain tolerant of incomplete receiver output.
     */
    fun accept(sentence: NmeaSentence) {
        val current = _state.value

        when (sentence) {
            is GGA -> {
                val newHdop = sentence.hdop ?: current.hdop
                val newAltMsl = sentence.altMsl ?: current.altMsl
                val newGeoid = sentence.geoidSeparation ?: current.geoidSeparation

                /*
                 * GGA reports MSL height and geoid separation separately. Ellipsoidal
                 * height is only valid when both inputs are present and finite.
                 */
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
                val speedMps = sentence.speedKnots?.let { it * KNOTS_TO_METERS_PER_SECOND }

                _state.value = current.copy(
                    speedMps = speedMps ?: current.speedMps,
                    courseDeg = sentence.courseDeg ?: current.courseDeg
                )
            }

            is GSA -> {
                val now = System.currentTimeMillis()

                /*
                 * Treat GSA messages that arrive close together as one receiver epoch.
                 * If the stream goes quiet, clear the set so old constellation data does
                 * not inflate the next satellite-used count.
                 */
                if (now - lastGsaUpdateMs > GSA_EPOCH_TIMEOUT_MS) {
                    gsaUsedSvids.clear()
                }
                lastGsaUpdateMs = now

                if (sentence.usedSvids.isNotEmpty()) {
                    gsaUsedSvids.addAll(sentence.usedSvids)
                }

                _state.value = current.copy(
                    hdop = sentence.hdop ?: current.hdop,
                    vDop = sentence.vdop ?: current.vDop,
                    pDop = sentence.pdop ?: current.pDop,
                    satsUsed = if (gsaUsedSvids.isNotEmpty()) gsaUsedSvids.size else current.satsUsed
                )
            }

            is GSV -> {
                /*
                 * GSV describes satellites currently visible to the receiver. This value
                 * is useful for diagnostics even when those satellites are not part of
                 * the active position solution.
                 */
                _state.value = current.copy(
                    satellitesInView = sentence.totalSatellites ?: current.satellitesInView
                )
            }

            is ZDA -> {
                /*
                 * Prefer receiver UTC time when ZDA supplies a complete epoch. Use device
                 * time only as a fallback so downstream consumers always have a timestamp.
                 */
                val fallbackNow = System.currentTimeMillis()
                val (timestampMillis, timestampSource) = if (sentence.epochMillis != null) {
                    sentence.epochMillis to TimestampSource.NMEA_ZDA
                } else {
                    fallbackNow to TimestampSource.DEVICE
                }

                _state.value = current.copy(
                    timestampMillis = timestampMillis,
                    timestampSource = timestampSource
                )
            }

            is GST -> {
                /*
                 * GST provides receiver-reported position error statistics. Keep the raw
                 * latitude, longitude, and altitude deviations, and also derive a single
                 * horizontal accuracy value for UI and capture checks.
                 */
                val horizontalAccuracy = calculateHorizontalAccuracy(
                    sentence.stdDevMajor,
                    sentence.stdDevMinor
                )

                _state.value = current.copy(
                    horizontalAccuracyM = horizontalAccuracy ?: current.horizontalAccuracyM,
                    verticalAccuracyM = sentence.stdDevAlt ?: current.verticalAccuracyM,
                    stdLatM = sentence.stdDevLat ?: current.stdLatM,
                    stdLonM = sentence.stdDevLon ?: current.stdLonM,
                    stdAltM = sentence.stdDevAlt ?: current.stdAltM
                )
            }

            else -> {
                // Unsupported sentence type. Leave the current fused state unchanged.
            }
        }
    }

    private fun sumIfFinite(a: Double?, b: Double?): Double? =
        // Note: the finite check uses the smart-cast non-null receiver so it resolves to the
        // stdlib Double.isFinite() — a former private `Double?.isFinite()` extension here shadowed
        // the stdlib one and recursed infinitely (StackOverflowError).
        if (a != null && b != null && a.isFinite() && b.isFinite()) a + b else null

    /**
     * Maps standard GGA fix-quality values to the app's RTK status labels.
     *
     * The numeric values come from the NMEA GGA fix quality field:
     * 0 = invalid, 1 = standalone GNSS, 2 = differential GNSS,
     * 4 = RTK fixed, 5 = RTK float, 6 = estimated/dead reckoning.
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
     * Derives a single horizontal accuracy value from the GST error ellipse.
     *
     * GST reports the major and minor 1-sigma axes of the horizontal error ellipse.
     * DRMS gives the UI one stable horizontal metric while still accounting for
     * elongated error ellipses caused by weak satellite geometry.
     */
    private fun calculateHorizontalAccuracy(stdDevMajor: Double?, stdDevMinor: Double?): Double? {
        if (stdDevMajor == null || stdDevMinor == null) return null

        return Math.sqrt((stdDevMajor * stdDevMajor + stdDevMinor * stdDevMinor) / 2.0)
    }

    private companion object {
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
    }
}