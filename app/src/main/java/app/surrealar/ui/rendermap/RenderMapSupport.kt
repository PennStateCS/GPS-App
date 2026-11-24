package app.surrealar.ui.rendermap

import android.content.Context
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs
import kotlin.math.min

// Small helpers shared by RenderMapFragment and its map components (moved out of the fragment).

/**
 * Logs a failed map step with its exception. Many steps run per fix, per frame or per marker,
 * so each step writes at most one line a minute; a one-off failure is still logged in full.
 */
internal fun logFail(step: String, e: Throwable, level: Char = 'W') {
    DiagnosticsLogger.every(DiagTags.MAP, "rendermap.$step", 60_000L, level, e) { "Map step failed step=\"$step\"" }
}

// WGS84 geodesic (geokit-core) — a 6,371 km sphere was 0.13–0.26 % off at 41° N.
internal fun calculateDistance(pos1: LatLng, pos2: LatLng): Double =
    io.github.pennstatecs.geokit.ellipsoid.Geodesic.distanceM(pos1.latitude, pos1.longitude, pos2.latitude, pos2.longitude)

internal fun calculateHeadingChange(h1: Double, h2: Double): Double {
    val diff = abs(h2 - h1); return min(diff, 360.0 - diff)
}

internal fun calculateBearing(from: LatLng, to: LatLng): Double =
    io.github.pennstatecs.geokit.ellipsoid.Geodesic.initialBearingDeg(from.latitude, from.longitude, to.latitude, to.longitude)

internal fun Context.dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
