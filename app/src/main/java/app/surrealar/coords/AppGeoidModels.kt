package app.surrealar.coords

import io.github.pennstatecs.geokit.geoid.GeoidAvailability
import io.github.pennstatecs.geokit.geoid.GeoidModel
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason
import io.github.pennstatecs.geokit.geoid.GlobalGeoidModels

/**
 * The global geoid models used for "H (EGM2008)" and "H (EGM96, fallback)" — one instance of each per
 * process (each has its own tile cache), created lazily so nothing is read unless an EGM height is chosen.
 *
 * GEOID18 is not here: [CoordinateSystemService] keeps loading the GEOID18 grid itself, unchanged.
 *
 * Data loads on the first lookup (blocking I/O: the bundled packs are read and SHA-256-verified). The
 * service calls [warmUp] on a background dispatcher when an EGM height is selected (at app start or
 * when the option is chosen), so screens normally never pay for it; a first synchronous lookup before
 * the warm-up finishes still works, it just blocks for that one load.
 */
object AppGeoidModels {

    /** EGM2008 2.5′: the bundled US-region tiles only (see [GlobalGeoidModels.EGM2008_BUNDLED_REGIONS]). */
    val egm2008: GeoidModel by lazy { GlobalGeoidModels.egm2008() }

    /** EGM96 15′ worldwide: the explicitly labelled fallback. */
    val egm96: GeoidModel by lazy { GlobalGeoidModels.egm96() }

    /** The shared instance of a global model; null for GEOID18 (handled by the conversion engine). */
    fun model(id: GeoidModelId): GeoidModel? = when (id) {
        GeoidModelId.EGM2008 -> egm2008
        GeoidModelId.EGM96 -> egm96
        GeoidModelId.GEOID18 -> null
    }

    /** A position inside the bundled EGM2008 CONUS tiles (and EGM96's global grid) used to open the data. */
    private const val WARM_UP_LAT = 40.0
    private const val WARM_UP_LON = -77.0

    /**
     * Opens [id]'s data with one lookup (blocking: call from a background dispatcher) and returns the
     * resulting availability; null for a model this object does not hold.
     */
    fun warmUp(id: GeoidModelId): GeoidAvailability? {
        val m = model(id) ?: return null
        m.undulation(WARM_UP_LAT, WARM_UP_LON)
        return m.availability
    }

    /**
     * Why an orthometric height from [id] is unavailable, as shown in place of the height (detail
     * screen, preview, logs). EGM2008 is bundled for US regions only, so a missing tile says so.
     */
    fun unavailableText(id: GeoidModelId, reason: GeoidUnavailableReason): String = when (reason) {
        GeoidUnavailableReason.TILE_NOT_AVAILABLE ->
            if (id == GeoidModelId.EGM2008) "outside EGM2008 coverage bundled in this app (US regions)"
            else "outside ${id.token} coverage bundled in this app"
        GeoidUnavailableReason.OUTSIDE_COVERAGE -> "outside ${id.token} coverage"
        GeoidUnavailableReason.LOAD_FAILED -> "${id.token} data could not be loaded"
        GeoidUnavailableReason.INVALID_POSITION -> "invalid position for ${id.token}"
    }
}
