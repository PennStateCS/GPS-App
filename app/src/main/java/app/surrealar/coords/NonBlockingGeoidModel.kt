package app.surrealar.coords

import io.github.pennstatecs.geokit.geoid.GeoidAvailability
import io.github.pennstatecs.geokit.geoid.GeoidModel
import io.github.pennstatecs.geokit.geoid.GeoidResult
import io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [GeoidModel] that never loads data on the main (UI) thread.
 *
 * Opening an EGM model reads and SHA-256-verifies the bundled packs (about 1.7 MB) under a lock, which
 * must not run on the UI thread (settings preview, point list rows at cold start). So, on the main
 * thread only:
 * - data loaded ([GeoidAvailability.AVAILABLE] / [GeoidAvailability.PARTIAL]): the lookup is answered as
 *   before (at most one small tile is decoded);
 * - not loaded yet: returns [GeoidUnavailableReason.DATA_LOADING] at once and starts a background load;
 * - load failed: returns [GeoidUnavailableReason.LOAD_FAILED] at once and starts a background attempt
 *   (the model retries after its back-off, otherwise this returns immediately).
 *
 * Off the main thread every lookup goes straight to [delegate], so a synchronous load stays allowed
 * (exports, tests). This mirrors the GEOID18 path, where the converter sees no undulation until the grid
 * has been loaded in the background. Once loaded, the numbers are exactly the delegate's.
 *
 * @param isMainThread true when called on the UI thread.
 * @param loadInBackground runs a load task off the main thread.
 * @param warmUpLat position used for the background load's lookup (inside the bundled data).
 * @param onLoadFinished called (on the loading thread) after each background load ended, loaded or not, so
 *   screens that showed "data is loading" can refresh at once ([AppGeoidModels.loadEvents]).
 */
internal class NonBlockingGeoidModel(
    private val delegate: GeoidModel,
    private val isMainThread: () -> Boolean,
    private val loadInBackground: (Runnable) -> Unit,
    private val warmUpLat: Double = 40.0,
    private val warmUpLon: Double = -77.0,
    private val onLoadFinished: () -> Unit = {},
) : GeoidModel by delegate {

    /** True while a background load is queued or running (at most one at a time). */
    private val loading = AtomicBoolean(false)

    override fun undulation(latDeg: Double, lonDeg: Double): GeoidResult = guarded { delegate.undulation(latDeg, lonDeg) }

    /**
     * Runs [lookup] (a lookup on [delegate], e.g. GEOID2022's time-dependent `undulationAt`) under the same
     * rule as [undulation]: straight through off the main thread or once the data is loaded; on the main
     * thread before that, DATA_LOADING / LOAD_FAILED at once plus a background load.
     */
    fun guarded(lookup: () -> GeoidResult): GeoidResult {
        if (!isMainThread()) return lookup()
        return when (delegate.availability) {
            GeoidAvailability.AVAILABLE, GeoidAvailability.PARTIAL -> lookup()
            GeoidAvailability.NOT_LOADED -> {
                requestLoad()
                GeoidResult.Unavailable(GeoidUnavailableReason.DATA_LOADING, info.id, "${info.label} data is loading")
            }
            GeoidAvailability.FAILED -> {
                requestLoad()
                GeoidResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, info.id, "${info.label} data could not be loaded")
            }
        }
    }

    /** Starts one background load unless one is already queued or running. */
    fun requestLoad() {
        if (!loading.compareAndSet(false, true)) return
        try {
            val before = delegate.availability
            loadInBackground(Runnable {
                try {
                    delegate.undulation(warmUpLat, warmUpLon)
                } finally {
                    loading.set(false)
                    // Notify only when the attempt changed something (e.g. NOT_LOADED → AVAILABLE, or → FAILED
                    // once). A retry that fails again must not re-render screens, which would look up on the
                    // main thread, request another load, and loop (review H1).
                    if (delegate.availability != before) onLoadFinished()
                }
            })
        } catch (e: RuntimeException) {
            loading.set(false)
            throw e
        }
    }
}
