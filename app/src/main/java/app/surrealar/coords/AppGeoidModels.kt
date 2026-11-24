package app.surrealar.coords

import io.github.pennstatecs.geokit.geoid.GeoidAvailability
import io.github.pennstatecs.geokit.geoid.GeoidModel
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason
import io.github.pennstatecs.geokit.geoid.GlobalGeoidModels
import android.os.Looper
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The global geoid models used for "H (EGM2008)" and "H (EGM96, fallback)" — one instance of each per
 * process (each has its own tile cache), created lazily so nothing is read unless an EGM height is chosen.
 *
 * GEOID18 is not here: [CoordinateSystemService] keeps loading the GEOID18 grid itself, unchanged.
 *
 * Data loads on the first lookup (blocking I/O: the bundled packs are read and SHA-256-verified). The
 * service calls [warmUp] on a background dispatcher when an EGM height is selected (at app start or
 * when the option is chosen). Lookups through [model] never load on the main thread: before the data is
 * loaded they return "EGM2008 data is loading" there and start a background load
 * ([NonBlockingGeoidModel]); off the main thread they may load synchronously (exports, tests).
 */
object AppGeoidModels {

    /** EGM2008 2.5′: the bundled US-region tiles only (see [GlobalGeoidModels.EGM2008_BUNDLED_REGIONS]). */
    val egm2008: GeoidModel by lazy { GlobalGeoidModels.egm2008() }

    /** EGM96 15′ worldwide: the explicitly labelled fallback. */
    val egm96: GeoidModel by lazy { GlobalGeoidModels.egm96() }

    /** Background loads started from the main thread (one per model at a time). */
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun isMainThread(): Boolean =
        runCatching { Looper.getMainLooper()?.isCurrentThread == true }.getOrDefault(false)

    private fun nonBlocking(id: GeoidModelId, raw: GeoidModel) = NonBlockingGeoidModel(
        delegate = raw, isMainThread = ::isMainThread,
        loadInBackground = { task ->
            loadScope.launch {
                val t0 = System.nanoTime()
                runCatching { task.run() }
                DiagnosticsLogger.i(DiagTags.DATA, "${id.token} geoid background load availability=${raw.availability} " +
                    "durationMs=${(System.nanoTime() - t0) / 1_000_000}")
            }
        },
        warmUpLat = WARM_UP_LAT, warmUpLon = WARM_UP_LON,
    )

    private val egm2008NonBlocking: GeoidModel by lazy { nonBlocking(GeoidModelId.EGM2008, egm2008) }
    private val egm96NonBlocking: GeoidModel by lazy { nonBlocking(GeoidModelId.EGM96, egm96) }

    /**
     * The shared instance of a global model for lookups from any thread (never loads on the main thread,
     * see [NonBlockingGeoidModel]); null for GEOID18 (handled by the conversion engine).
     */
    fun model(id: GeoidModelId): GeoidModel? = when (id) {
        GeoidModelId.EGM2008 -> egm2008NonBlocking
        GeoidModelId.EGM96 -> egm96NonBlocking
        GeoidModelId.GEOID18 -> null
    }

    /** The model itself (loads synchronously on first use, any thread): for [warmUp]. */
    private fun rawModel(id: GeoidModelId): GeoidModel? = when (id) {
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
        val m = rawModel(id) ?: return null
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
        GeoidUnavailableReason.DATA_LOADING -> "${id.token} data is loading"
    }
}

/**
 * Opens each global geoid model once per process, marking a model done only when the data actually
 * loaded (AVAILABLE / PARTIAL). A failed or thrown warm-up is not remembered, so the next request (or a
 * main-thread lookup's background load, after the model's back-off) tries again.
 */
internal class GeoidWarmUpTracker(private val warm: (GeoidModelId) -> GeoidAvailability?) {
    private val done = java.util.Collections.synchronizedSet(HashSet<GeoidModelId>())

    /** True once [id] has been opened successfully. */
    fun isWarm(id: GeoidModelId): Boolean = id in done

    /**
     * Warms [id] up unless already done: null when skipped, else the warm-up's result (the availability
     * reached, or the exception it threw).
     */
    fun warmUp(id: GeoidModelId): Result<GeoidAvailability?>? {
        if (id in done) return null
        val r = runCatching { warm(id) }
        r.getOrNull()?.let {
            if (it == GeoidAvailability.AVAILABLE || it == GeoidAvailability.PARTIAL) done.add(id)
        }
        return r
    }
}
