package app.surrealar.gnss.lastknown

import android.content.Context
import app.surrealar.gnss.bus.FixSwitchboard
import io.github.pennstatecs.geokit.gnss.model.Fix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sqrt

/** Last position reported by the GNSS feed, persisted on the device. */
data class LastKnownPosition(val latDeg: Double, val lonDeg: Double, val savedAtMs: Long)

/**
 * Remembers the last live GNSS position (from [FixSwitchboard], internal or external) in the app's
 * private SharedPreferences, so the Home map can open where the user last was instead of a fixed
 * spot while waiting for the first fix. Device-local only (never sent anywhere) and overwritten by
 * each new fix; writes are throttled ([shouldPersist]).
 */
@Singleton
class LastKnownPositionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var lastSaved: LastKnownPosition? = null

    fun get(): LastKnownPosition? {
        lastSaved?.let { return it }
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0L))
        val lon = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LON, 0L))
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return LastKnownPosition(lat, lon, prefs.getLong(KEY_TIME, 0L)).also { lastSaved = it }
    }

    /** Starts recording live fixes from the GNSS feed for the life of [scope] (call once at app start). */
    fun start(scope: CoroutineScope, switchboard: FixSwitchboard) {
        scope.launch {
            switchboard.currentFix.filterNotNull().collect { record(it, System.currentTimeMillis()) }
        }
    }

    internal fun record(fix: Fix, nowMs: Long) {
        if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) return
        val next = LastKnownPosition(fix.latDeg, fix.lonDeg, nowMs)
        if (!shouldPersist(lastSaved ?: get(), next)) return
        lastSaved = next
        prefs.edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(next.latDeg))
            .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(next.lonDeg))
            .putLong(KEY_TIME, nowMs)
            .apply()
    }

    companion object {
        private const val PREFS = "gnss_last_known_position"
        private const val KEY_LAT = "lat_bits"
        private const val KEY_LON = "lon_bits"
        private const val KEY_TIME = "saved_at_ms"

        /** Save when moved at least this far… */
        const val MIN_MOVE_M = 10.0
        /** …or at least this often while stationary (keeps the timestamp fresh without constant writes). */
        const val MAX_INTERVAL_MS = 60_000L

        fun shouldPersist(prev: LastKnownPosition?, next: LastKnownPosition): Boolean {
            if (prev == null) return true
            if (next.savedAtMs - prev.savedAtMs >= MAX_INTERVAL_MS) return true
            return approxDistanceM(prev.latDeg, prev.lonDeg, next.latDeg, next.lonDeg) >= MIN_MOVE_M
        }

        /** WGS84 geodesic distance (geokit-core; was an equirectangular 111,320 m/° approximation). */
        internal fun approxDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            io.github.pennstatecs.geokit.ellipsoid.Geodesic.distanceM(lat1, lon1, lat2, lon2)
    }
}
