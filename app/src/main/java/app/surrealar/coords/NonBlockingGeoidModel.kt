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
 */
internal class NonBlockingGeoidModel(
    private val delegate: GeoidModel,
    private val isMainThread: () -> Boolean,
    private val loadInBackground: (Runnable) -> Unit,
    private val warmUpLat: Double = 40.0,
    private val warmUpLon: Double = -77.0,
) : GeoidModel by delegate {

    /** True while a background load is queued or running (at most one at a time). */
    private val loading = AtomicBoolean(false)

    override fun undulation(latDeg: Double, lonDeg: Double): GeoidResult {
        if (!isMainThread()) return delegate.undulation(latDeg, lonDeg)
        return when (delegate.availability) {
            GeoidAvailability.AVAILABLE, GeoidAvailability.PARTIAL -> delegate.undulation(latDeg, lonDeg)
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
            loadInBackground(Runnable {
                try {
                    delegate.undulation(warmUpLat, warmUpLon)
                } finally {
                    loading.set(false)
                }
            })
        } catch (e: RuntimeException) {
            loading.set(false)
            throw e
        }
    }
}
