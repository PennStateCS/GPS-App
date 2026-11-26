package app.surrealar.ui.home

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.FixSnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource

/**
 * [Fix] → [FixSnapshot] as the Home screen maps it (moved unchanged out of HomeViewModel so the GNSS Status report
 * can build the same Home Field Status inputs without a ViewModel). Pure.
 */
object FixSnapshots {

    /** No live fix. */
    val EMPTY = FixSnapshot(
        timestampMillis = 0L,
        timestampSource = TimestampSource.DEVICE,
        lat = null, lon = null,
        altMsl = null, geoidSeparation = null, altEllipsoidal = null,
        speedMps = null, courseDeg = null,
        satsUsed = null, hdop = null,
        vDop = null, pDop = null,
        satellitesInView = null, horizontalAccuracyM = null,
        verticalAccuracyM = null, correctionAgeS = null,
        correctionStationId = null, multipathIndex = null,
        rtkStatus = null, stdLatM = null, stdLonM = null, stdAltM = null
    )

    fun of(fix: Fix?): FixSnapshot = fix?.let {
        FixSnapshot(
            timestampMillis      = it.timeUtc.toEpochMilli(),
            timestampSource      = it.timestampSource,
            lat                  = it.latDeg,
            lon                  = it.lonDeg,
            altMsl               = it.altMslM,
            geoidSeparation      = it.geoidSeparationM,
            altEllipsoidal       = it.altEllipsoidalM,
            speedMps             = it.speedMps,
            courseDeg            = it.courseDeg,
            satsUsed             = it.satsUsed,
            hdop                 = it.hDop,
            vDop                 = it.vDop,
            pDop                 = it.pDop,
            satellitesInView     = it.satsVisible,
            horizontalAccuracyM  = it.hAccM,
            verticalAccuracyM    = it.vAccM,
            correctionAgeS       = it.diffAgeS,
            correctionStationId  = it.correctionStationId,
            multipathIndex       = it.multipathIndex,
            rtkStatus            = it.rtkStatus.name,
            stdLatM              = null,
            stdLonM              = null,
            stdAltM              = null
        )
    } ?: EMPTY
}
