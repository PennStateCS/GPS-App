package io.github.pennstatecs.geokit.gnss.model

/**
 * Display-oriented snapshot of a [Fix]: the subset a status card, fix badge or saved-point builder
 * needs, mapped from the live fix by the host.
 */
public data class FixSnapshot(
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
