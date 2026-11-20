package app.surrealar.ui.openinar

import app.surrealar.gnss.model.RtkStatus

/**
 * How each point's AR anchor gets its height (Settings → AR Display → Default Altitude Mode, and the
 * AR toolbar button).
 *
 *  - [AUTO] (default): per point. A point whose stored height is trustworthy — vertical accuracy
 *    ≤ [MAX_VERTICAL_ACCURACY_M], or an RTK FIX/FLOAT capture without an accuracy figure — is anchored
 *    at its stored ellipsoidal height (cm-level with RTK). Anything else (internal-GPS captures with
 *    metres of vertical error, points with no height) is anchored on terrain and snapped to the
 *    ground ARCore detects, so it doesn't float or sink.
 *  - [STORED]: every point at its stored height (fallback: the user's current height).
 *  - [TERRAIN]: every point on terrain, ignoring stored heights.
 *
 * Pure (no ARCore types) → unit-tested.
 */
object AltitudePolicy {
    const val AUTO = "AUTO"
    const val STORED = "STORED"
    const val TERRAIN = "TERRAIN"

    /** Stored heights better than this (m, vertical 1σ) are used as-is in AUTO. */
    const val MAX_VERTICAL_ACCURACY_M = 0.5

    /** Unknown/legacy values fall back to the default, AUTO. */
    fun normalize(mode: String?): String = when (mode) { STORED, TERRAIN -> mode; else -> AUTO }

    /** Toolbar cycle: Auto → Stored → Terrain → Auto. */
    fun next(mode: String): String = when (normalize(mode)) { AUTO -> STORED; STORED -> TERRAIN; else -> AUTO }

    fun label(mode: String): String = when (normalize(mode)) { STORED -> "Stored"; TERRAIN -> "Terrain"; else -> "Auto" }

    /** Why a point got the height it did (logs). */
    enum class Choice(val onTerrain: Boolean) {
        STORED_ACCURATE(false),      // AUTO: vertical accuracy within limit
        STORED_RTK(false),           // AUTO: RTK FIX/FLOAT, no accuracy figure
        STORED_FORCED(false),        // STORED mode
        TERRAIN_INACCURATE(true),    // AUTO: vertical accuracy too poor / non-RTK without figure
        TERRAIN_NO_HEIGHT(true),     // AUTO: no stored height
        TERRAIN_FORCED(true),        // TERRAIN mode
    }

    fun choose(mode: String, storedAltM: Double?, verticalAccuracyM: Double?, rtkStatus: RtkStatus?): Choice =
        when (normalize(mode)) {
            STORED -> Choice.STORED_FORCED
            TERRAIN -> Choice.TERRAIN_FORCED
            else -> when {
                storedAltM == null || !storedAltM.isFinite() -> Choice.TERRAIN_NO_HEIGHT
                verticalAccuracyM != null && verticalAccuracyM.isFinite() ->
                    if (verticalAccuracyM <= MAX_VERTICAL_ACCURACY_M) Choice.STORED_ACCURATE else Choice.TERRAIN_INACCURATE
                rtkStatus == RtkStatus.FIX || rtkStatus == RtkStatus.FLOAT -> Choice.STORED_RTK
                else -> Choice.TERRAIN_INACCURATE
            }
        }
}
