package app.surrealar.receiver.tilt

import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import java.time.Instant
import java.util.Locale

/** NMEA ETC lines (with real checksums) and matching fixes for the tilt tests. */
object TiltTestNmea {
    /** 2026-11-24T00:00:00Z. */
    private const val DAY0_MS = 1_795_478_400_000L

    fun withChecksum(body: String): String {
        var cs = 0
        for (ch in body) cs = cs xor ch.code
        return "$" + body + "*" + String.format(Locale.US, "%02X", cs)
    }

    /** "hhmmss.ss" for seconds of day. */
    fun hms(sod: Double): String {
        val cs = Math.round(sod * 100).toInt()
        val h = cs / 360_000; val m = (cs / 6_000) % 60; val s = (cs % 6_000) / 100.0
        return String.format(Locale.US, "%02d%02d%05.2f", h, m, s)
    }

    fun etcActive(sod: Double, notification: String = "00") =
        withChecksum("GNETC,${hms(sod)},30,$notification,268.660,116.146,17.651,6.280,6.280,6.991")

    fun etcOff(sod: Double) = withChecksum("GNETC,${hms(sod)},,,,,,,,")

    fun etcAligning(sod: Double) = withChecksum("GNETC,${hms(sod)},20,00,,,,,,")

    fun fix(
        sod: Double,
        provider: Provider = Provider.EXTERNAL_TCP,
        alt: Double = 100.0,
        station: String? = null,
        timestampSource: TimestampSource = TimestampSource.NMEA_ZDA,
    ) = Fix(
        provider = provider, timeUtc = Instant.ofEpochMilli(DAY0_MS + Math.round(sod * 1000)), timestampSource = timestampSource,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = alt, altMslM = null, geoidSeparationM = null,
        hDop = 0.8, vDop = 1.0, pDop = 1.2, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = 0.0,
        correctionStationId = station,
    )
}
