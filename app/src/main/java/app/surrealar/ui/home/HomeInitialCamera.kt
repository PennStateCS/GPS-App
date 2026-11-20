package app.surrealar.ui.home

import app.surrealar.gnss.lastknown.LastKnownPosition

/**
 * Where the Home map opens before (or without) a live GNSS fix. Pure, so it is unit-testable.
 *
 * Priority: live fix → last position from the GNSS feed → saved survey points → regional overview.
 * Only [Live] means "you are here"; the others just frame a sensible area while waiting.
 */
sealed interface HomeInitialCamera {
    /** Status chip text shown while there is no live fix (null = hide the chip). */
    val waitingText: String?

    data class Live(val lat: Double, val lon: Double) : HomeInitialCamera {
        override val waitingText: String? = null
    }
    data class LastKnown(val lat: Double, val lon: Double) : HomeInitialCamera {
        override val waitingText = "Last known position · waiting for GNSS"
    }
    /** Fit these saved points (a single point is centred instead). */
    data class SavedPoints(val points: List<Pair<Double, Double>>) : HomeInitialCamera {
        override val waitingText = "Waiting for GNSS…"
    }
    data object Overview : HomeInitialCamera {
        override val waitingText = "Waiting for GNSS…"
        /** Continental-US overview (the app's field area) — no specific site is implied. */
        const val LAT = 39.8
        const val LON = -98.6
        const val ZOOM = 4f
    }

    companion object {
        fun choose(
            live: Pair<Double, Double>?,
            lastKnown: LastKnownPosition?,
            savedPoints: List<Pair<Double, Double>>,
        ): HomeInitialCamera {
            if (live != null && valid(live.first, live.second)) return Live(live.first, live.second)
            if (lastKnown != null && valid(lastKnown.latDeg, lastKnown.lonDeg)) {
                return LastKnown(lastKnown.latDeg, lastKnown.lonDeg)
            }
            val points = savedPoints.filter { valid(it.first, it.second) }
            if (points.isNotEmpty()) return SavedPoints(points)
            return Overview
        }

        private fun valid(lat: Double, lon: Double) =
            lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0) && lat.isFinite() && lon.isFinite()
    }
}
