package io.github.pennstatecs.geokit.ar.ground

/** One detected horizontal surface, reduced to what floor selection needs: height (world Y, m) and area (m²). */
public data class GroundSurface(val y: Float, val area: Float)

/** Picks the main floor / ground height from the horizontal surfaces ARCore detected below the camera. */
public object MainFloor {
    /** Surfaces smaller than this are ignored when picking the main floor (tables, steps, noise). */
    public const val MIN_FLOOR_AREA_M2: Float = 0.5f

    /**
     * Main floor = the LOWEST reasonably large surface (a floor sits below tables/benches). Falls back
     * to the largest surface when none reach [MIN_FLOOR_AREA_M2]; null when there are none.
     */
    public fun choose(surfaces: List<GroundSurface>): Float? {
        if (surfaces.isEmpty()) return null
        val large = surfaces.filter { it.area >= MIN_FLOOR_AREA_M2 }
        return if (large.isNotEmpty()) large.minOf { it.y } else surfaces.maxByOrNull { it.area }!!.y
    }
}
