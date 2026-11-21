package app.surrealar.ui.viewpoints

/**
 * Picks the altitude for a point saved at a model's embedded location.
 *
 * A model file may carry lat/lon without an altitude. Storing 0.0 then put the point at the
 * ellipsoid (~360 m too low in PA). Instead: the model's own altitude when present; otherwise the
 * current GNSS fix's ellipsoidal height, flagged so the UI can say so; otherwise null — the caller
 * must not save the model location.
 */
object ModelAltitudeResolver {

    enum class Source { MODEL, CURRENT_FIX }

    data class Resolved(val altitudeM: Double, val source: Source)

    fun resolve(modelAltitudeM: Double?, liveEllipsoidalM: Double?): Resolved? = when {
        modelAltitudeM != null && modelAltitudeM.isFinite() -> Resolved(modelAltitudeM, Source.MODEL)
        liveEllipsoidalM != null && liveEllipsoidalM.isFinite() -> Resolved(liveEllipsoidalM, Source.CURRENT_FIX)
        else -> null
    }
}
