package com.example.surveyingapp.gnss.satellites

import com.example.surveyingapp.gnss.bus.adapters.GsvMessage
import com.example.surveyingapp.gnss.model.SkySnapshot
import com.example.surveyingapp.gnss.model.SkyGeometry
import com.example.surveyingapp.gnss.model.Constellation
import kotlin.math.max
import kotlin.math.min

/**
 * Maintains smoothed SNR per-SVID and rolls up used/visible counts per constellation.
 * Evicts stale entries that haven't been seen within a short horizon.
 */
class SatelliteInventory(
    private val halfLifeSeconds: Double = 10.0,
    private val evictionSeconds: Double = 30.0
) {
    private val nowSeconds: () -> Double = { System.nanoTime() / 1e9 }
    private val snr = mutableMapOf<Int, Pair<Double, Double>>() // svid -> (snr, lastSeen)
    private val used = mutableMapOf<String, Int>()
    private val visible = mutableMapOf<String, Int>()

    // Store geometry data for building SkyGeometry objects
    private val geometry = mutableMapOf<Int, GeometryData>() // svid -> geometry data

    private data class GeometryData(
        val svid: Int,
        val constellation: Constellation,
        val azDeg: Double?,
        val elDeg: Double?,
        val usedInFix: Boolean,
        val lastSeen: Double
    )

    fun consume(gsv: GsvMessage): SkySnapshot {
        val t = nowSeconds()
        val constellation = mapConstellationName(gsv.constellation)

        // Update smoothed SNR and geometry data
        gsv.entries.forEach { e ->
            val prev = snr[e.svid]
            val dt = if (prev == null) 0.0 else max(1e-3, t - prev.second)
            val alpha = 1.0 - Math.exp(-dt / halfLifeSeconds)
            val smoothed = if (prev == null) e.snrDbHz else prev.first + alpha * (e.snrDbHz - prev.first)
            snr[e.svid] = smoothed to t

            // Store geometry data
            geometry[e.svid] = GeometryData(
                svid = e.svid,
                constellation = constellation,
                azDeg = e.azimuthDeg?.toDouble(),
                elDeg = e.elevationDeg?.toDouble(),
                usedInFix = e.usedInFix,
                lastSeen = t
            )
        }

        // Rebuild constellation tallies from this message.
        val v = (visible[gsv.constellation] ?: 0) + gsv.entries.size
        val u = (used[gsv.constellation] ?: 0) + gsv.entries.count { it.usedInFix }
        visible[gsv.constellation] = v
        used[gsv.constellation] = u

        // Evict stale SVIDs
        val cutoff = t - evictionSeconds
        snr.entries.removeIf { it.value.second < cutoff }
        geometry.entries.removeIf { it.value.lastSeen < cutoff }

        // Build List<SkyGeometry> for all satellites
        val satList = geometry.values.map { geoData ->
            SkyGeometry(
                svid = geoData.svid,
                constellation = geoData.constellation,
                azDeg = geoData.azDeg,
                elDeg = geoData.elDeg,
                snrDbHz = snr[geoData.svid]?.first,
                usedInFix = geoData.usedInFix
            )
        }

        return SkySnapshot(
            visibleByConstellation = visible.toMap(),
            usedByConstellation = used.toMap(),
            snrBySvid = snr.mapValues { it.value.first },
            geometry = satList
        )
    }

    private fun mapConstellationName(name: String): Constellation {
        return when (name.uppercase()) {
            "GPS" -> Constellation.GPS
            "GLO", "GLONASS" -> Constellation.GLONASS
            "GAL", "GALILEO" -> Constellation.GALILEO
            "BDS", "BEIDOU" -> Constellation.BEIDOU
            "QZSS" -> Constellation.QZSS
            "SBAS" -> Constellation.SBAS
            "IRNSS" -> Constellation.IRNSS
            else -> Constellation.UNKNOWN
        }
    }
}
