package app.surrealar.gnss.satellites

import app.surrealar.gnss.bus.adapters.GsvMessage
import app.surrealar.gnss.model.SkySnapshot
import app.surrealar.gnss.model.SatInfo
import app.surrealar.gnss.model.SkySource
import app.surrealar.gnss.model.SkyGeometry
import app.surrealar.gnss.model.Constellation
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
    private val nowSeconds: () -> Double = { System.nanoTime() / 1e9 },
    /** Label for debug-build tracing ("internal" / "external"). */
    private val debugName: String = "sky"
) {
    private companion object { val LN2 = kotlin.math.ln(2.0) }

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

        // Apply EMA smoothing to SNR and record the latest geometry for each satellite
        gsv.entries.forEach { e ->
            val id = SatId(const, e.svid)

            // SNR EMA with a true half-life: after halfLifeSeconds the old value's weight is 0.5
            // (exp(−ln2·dt/T)). Using T as the time constant gave a 6.9 s half-life for T = 10 s.
            val snrSample = e.snrDbHz
            if (snrSample != null) {
                val prev = snrBySat[id]
                val dt = if (prev == null) 0.0 else max(1e-3, t - prev.second)
                val alpha = 1.0 - kotlin.math.exp(-LN2 * dt / halfLifeSeconds)
                val smoothed = prev?.first?.let { it + alpha * (snrSample - it) } ?: snrSample
                snrBySat[id] = smoothed to t
            } else {
                // Still listed but no SNR now (lost lock on the signal): don't keep the old value.
                snrBySat.remove(id)
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

        // A GSV epoch lists every satellite currently in view for its constellation (the fuser merges
        // per-signal sequences, so an L5-only satellite is still listed). A satellite of this
        // constellation missing from it is no longer in view: drop it now. It used to linger until the
        // 60 s eviction, inflating the toolbar's visible/used counts (e.g. 67 shown vs 51 reported).
        // Only this constellation is pruned, so a sparsely-reported one (GLONASS) is unaffected by
        // other constellations' updates. An empty message (no entries) prunes nothing.
        if (gsv.entries.isNotEmpty()) {
            val present = gsv.entries.mapTo(HashSet()) { it.svid }
            val gone = geometryBySat.keys.filter { it.c == const && it.svid !in present }
            gone.forEach { geometryBySat.remove(it); snrBySat.remove(it) }
        }

        // Evict satellites that have not been seen recently
        val cutoff = t - evictionSeconds
        val beforeEvict = geometryBySat.size
        val evictedSnr = snrBySat.entries.removeIf { (_, v) -> v.second < cutoff }
        val evictedGeom = geometryBySat.entries.removeIf { (_, g) -> g.lastSeen < cutoff }
        if (evictedSnr || evictedGeom) {
            android.util.Log.d("SatelliteInventory", "Evicted stale satellites (older than ${evictionSeconds}s)")
            app.surrealar.gnss.debug.GnssDebugLog.every("sky.evict.$debugName", "sky") {
                "$debugName evicted ${beforeEvict - geometryBySat.size} satellite(s) not seen for ${evictionSeconds}s"
            }
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

        app.surrealar.gnss.debug.GnssDebugLog.every("sky.summary.$debugName", "sky") {
            val staleSec = geometryBySat.values.count { t - it.lastSeen > 5.0 }
            // withGeom/withSnr: how many can be drawn (skyplot/charts need az+el and SNR).
            val withGeom = geometryBySat.values.count { it.azDeg != null && it.elDeg != null }
            val withSnr = geometryBySat.keys.count { snrBySat[it] != null }
            "$debugName visible=${geometryBySat.size} used=${geometryBySat.values.count { it.usedInFix }} " +
                "withGeom=$withGeom withSnr=$withSnr " +
                "visibleBy=$visibleByConstellation usedBy=$usedByConstellation " +
                "(last msg ${gsv.constellation}: ${gsv.entries.size} sats; $staleSec held from >5 s ago)"
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
