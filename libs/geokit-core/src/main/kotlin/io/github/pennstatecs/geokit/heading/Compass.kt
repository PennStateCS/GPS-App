package io.github.pennstatecs.geokit.heading

/** 8-point compass labels for bearings. */
public object Compass {

    private val POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /**
     * 8-point compass label ("N", "NE", … "NW") for a bearing in degrees clockwise from north.
     * Any input is normalised first (negative, ≥ 360); non-finite input reads "N".
     */
    public fun point8(bearingDeg: Double): String {
        if (!bearingDeg.isFinite()) return "N"
        val norm = ((bearingDeg % 360.0) + 360.0) % 360.0
        return POINTS[(Math.floor((norm + 22.5) / 45.0).toInt()) % 8]
    }
}
