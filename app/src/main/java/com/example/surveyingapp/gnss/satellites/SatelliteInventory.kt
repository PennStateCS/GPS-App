package com.example.surveyingapp.gnss.satellites

import com.example.surveyingapp.gnss.bus.adapters.GsvMessage
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.model.SatInfo
import com.example.surveyingapp.gnss.model.SkySource
import com.example.surveyingapp.gnss.model.SkyGeometry
import com.example.surveyingapp.gnss.model.Constellation
import kotlin.math.max

/**
 * Maintains smoothed SNR per satellite and rolls up used/visible per constellation.
 * Evicts stale satellites not seen within [evictionSeconds].
 *
 * Thread-safety: confine to a single coroutine/thread, or guard calls with a Mutex.
 */
class SatelliteInventory(
    private val halfLifeSeconds: Double = 10.0,
    private val evictionSeconds: Double = 30.0,
    private val nowSeconds: () -> Double = { System.nanoTime() / 1e9 }
) {
    /** Namespaced satellite id to avoid SVID collisions across constellations. */
    private data class SatId(val c: Constellation, val svid: Int)

    /** smoothed SNR + lastSeen seconds */
    private val snrBySat = mutableMapOf<SatId, Pair<Double, Double>>()   // SatId -> (snr, lastSeen)
    /** latest geometry per satellite */
    private val geometryBySat = mutableMapOf<SatId, GeometryData>()      // SatId -> geometry

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

        // Update SNR smoothing and geometry for each entry in this message
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

        // Evict stale satellites from both maps
        val cutoff = t - evictionSeconds
        snrBySat.entries.removeIf { (_, v) -> v.second < cutoff }
        geometryBySat.entries.removeIf { (_, g) -> g.lastSeen < cutoff }

        // Rebuild tallies from current geometry (no double-counting)
        val visibleByConstellation = mutableMapOf<Constellation, Int>()
        val usedByConstellation = mutableMapOf<Constellation, Int>()

        geometryBySat.values.forEach { g ->
            visibleByConstellation[g.id.c] = (visibleByConstellation[g.id.c] ?: 0) + 1
            if (g.usedInFix) {
                usedByConstellation[g.id.c] = (usedByConstellation[g.id.c] ?: 0) + 1
            }
        }

        // Build SatInfo list for SkySnapshot
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

        // Create SkySnapshot with proper constructor parameters
        return SkySnapshot(
            satellites = satInfoList,
            epoch = java.time.Instant.ofEpochSecond(t.toLong(), ((t % 1.0) * 1e9).toLong()),
            source = SkySource.NMEA_GSV
        )
    }

    private fun mapConstellationName(name: String): Constellation =
        when (name.uppercase()) {
            "GPS"            -> Constellation.GPS
            "GLO", "GLONASS" -> Constellation.GLONASS
            "GAL", "GALILEO" -> Constellation.GALILEO
            "BDS", "BEIDOU"  -> Constellation.BEIDOU
            "QZSS"           -> Constellation.QZSS
            "SBAS"           -> Constellation.SBAS
            "IRNSS", "NAVIC" -> Constellation.IRNSS
            else             -> Constellation.UNKNOWN
        }
}
