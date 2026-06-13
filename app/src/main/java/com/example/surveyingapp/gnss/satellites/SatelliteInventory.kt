package com.example.surveyingapp.gnss.satellites

import android.util.Log
import com.example.surveyingapp.gnss.bus.adapters.GsvMessage
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.model.SatInfo
import com.example.surveyingapp.gnss.model.SkySource
import com.example.surveyingapp.gnss.model.SkyGeometry
import com.example.surveyingapp.gnss.model.Constellation
import kotlin.math.max

/**
 * Maintains a smoothed SNR reading and geometry snapshot for each visible satellite.
 *
 * Every time a complete GSV epoch arrives, [consume] updates the signal strength
 * using an exponential moving average (half-life configurable), records the latest
 * azimuth/elevation/used-in-fix state, and evicts any satellite not seen within
 * [evictionSeconds]. The result is a [SkySnapshot] ready for the skyplot UI.
 *
 * The EMA smoothing prevents the chart from flickering when the receiver reports
 * slightly different SNR values between updates.
 *
 * Not thread-safe — confine all calls to a single coroutine or add a Mutex.
 */
class SatelliteInventory(
    private val halfLifeSeconds: Double = 10.0,
    private val evictionSeconds: Double = 30.0,
    private val nowSeconds: () -> Double = { System.nanoTime() / 1e9 }
) {
    /** Namespaced key to avoid SVID collisions across constellations (GPS and GLONASS can share numbers). */
    private data class SatId(val c: Constellation, val svid: Int)

    private val snrBySat = mutableMapOf<SatId, Pair<Double, Double>>()   // SatId → (smoothedSnr, lastSeenSec)
    private val geometryBySat = mutableMapOf<SatId, GeometryData>()

    private data class GeometryData(
        val id: SatId,
        val azDeg: Double?,
        val elDeg: Double?,
        val usedInFix: Boolean,
        val lastSeen: Double
    )

    fun consume(gsv: GsvMessage): SkySnapshot {
        val t = nowSeconds()
        val const = mapConstellationName(gsv.constellation)

        android.util.Log.d("SatelliteInventory", "Consuming GSV: constellation=${gsv.constellation} (mapped to $const), ${gsv.entries.size} satellites")

        // Apply EMA smoothing to SNR and record the latest geometry for each satellite
        gsv.entries.forEach { e ->
            val id = SatId(const, e.svid)

            // SNR EMA (only if present)
            e.snrDbHz?.let { snrSample ->
                val prev = snrBySat[id]
                val dt = if (prev == null) 0.0 else max(1e-3, t - prev.second)
                val alpha = 1.0 - kotlin.math.exp(-dt / halfLifeSeconds)
                val smoothed = prev?.first?.let { it + alpha * (snrSample - it) } ?: snrSample
                snrBySat[id] = smoothed to t
            }

            // Geometry
            geometryBySat[id] = GeometryData(
                id = id,
                azDeg = e.azimuthDeg?.toDouble(),
                elDeg = e.elevationDeg?.toDouble(),
                usedInFix = e.usedInFix,
                lastSeen = t
            )
        }

        // Evict satellites that have not been seen recently
        val cutoff = t - evictionSeconds
        val evictedSnr = snrBySat.entries.removeIf { (_, v) -> v.second < cutoff }
        val evictedGeom = geometryBySat.entries.removeIf { (_, g) -> g.lastSeen < cutoff }
        if (evictedSnr || evictedGeom) {
            android.util.Log.d("SatelliteInventory", "Evicted stale satellites (older than ${evictionSeconds}s)")
        }

        // Rebuild per-constellation tallies from the current geometry map
        val visibleByConstellation = mutableMapOf<Constellation, Int>()
        val usedByConstellation = mutableMapOf<Constellation, Int>()

        geometryBySat.values.forEach { g ->
            visibleByConstellation[g.id.c] = (visibleByConstellation[g.id.c] ?: 0) + 1
            if (g.usedInFix) {
                usedByConstellation[g.id.c] = (usedByConstellation[g.id.c] ?: 0) + 1
            }
        }

        // Build the SatInfo list for the SkySnapshot
        val satInfoList = geometryBySat.values.map { g ->
            SatInfo(
                constellation = g.id.c,
                svid = g.id.svid,
                azimuthDeg = g.azDeg,
                elevationDeg = g.elDeg,
                cn0DbHz = snrBySat[g.id]?.first,
                usedInFix = g.usedInFix
            )
        }

        val totalVisible = visibleByConstellation.values.sum()
        val totalUsed = usedByConstellation.values.sum()
        android.util.Log.d("SatelliteInventory", "SkySnapshot built: ${satInfoList.size} satellites, $totalUsed used, $totalVisible visible | By constellation: ${visibleByConstellation.entries.sortedByDescending { it.value }.joinToString { "${it.key.name}=${it.value}" }}")

        return SkySnapshot(
            satellites = satInfoList,
            epoch = java.time.Instant.ofEpochSecond(t.toLong(), ((t % 1.0) * 1e9).toLong()),
            source = SkySource.NMEA_GSV
        )
    }

    /**
     * Clears all accumulated satellite state. Call when switching GNSS providers
     * to avoid stale satellites from the previous source appearing in the sky view.
     */
    fun reset() {
        snrBySat.clear()
        geometryBySat.clear()
        android.util.Log.d("SatelliteInventory", "Reset: cleared all satellite state")
    }

    private fun mapConstellationName(name: String): Constellation =
        when (name.uppercase()) {
            // Full constellation names (used by higher-level code)
            "GPS"            -> Constellation.GPS
            "GLO", "GLONASS" -> Constellation.GLONASS
            "GAL", "GALILEO" -> Constellation.GALILEO
            "BDS", "BEIDOU"  -> Constellation.BEIDOU
            "QZSS"           -> Constellation.QZSS
            "SBAS"           -> Constellation.SBAS
            "IRNSS", "NAVIC" -> Constellation.IRNSS
            // 2-letter NMEA talker prefixes (used by NmeaFuser when labelling GSV epochs)
            "GP"             -> Constellation.GPS
            "GL"             -> Constellation.GLONASS
            "GA"             -> Constellation.GALILEO
            "GB", "BD"       -> Constellation.BEIDOU
            "GQ"             -> Constellation.QZSS
            "GI"             -> Constellation.IRNSS
            // "GN" means combined GNSS; we can't tell which constellation per satellite
            "GN"             -> Constellation.GPS
            else             -> Constellation.UNKNOWN
        }
}
