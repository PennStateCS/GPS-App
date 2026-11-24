package app.surrealar.ui.rendermap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * The live GNSS position on the survey map: collects the FixSwitchboard feed (only fixes from the
 * SELECTED source, sampled at 250 ms) on the view lifecycle, and draws the current-position marker
 * (RTK-status colour), the accuracy circle, the live trail and the heading arrow.
 *
 * The one owner of the latest live fix ([currentFix]) and its position ([lastFixLatLng]), which
 * recenter, stakeout, labels and the selected-point card read. The "My Location" toggle
 * ([showCurrentLocationOnMap]) only hides the overlays; the latest fix keeps being tracked.
 *
 * Each accepted fix ends with [onLivePosition] (stakeout readout + distance labels). Holds no
 * GoogleMap reference (taken from [map] per call); [onDestroyView] removes the overlays.
 */
internal class LiveTrackingController(
    private val map: () -> GoogleMap?,
    private val context: () -> Context?,
    private val fixes: Flow<Fix>,
    private val fixStale: Flow<Boolean>,
    private val activeProvider: Flow<SourceSettings.ProviderChoice>,
    private val locationSource: Flow<LocationSourceType>,
    /** Latest true compass heading, or null (no sensor / not registered). */
    private val compassHeading: () -> Double?,
    /** Called for every accepted live position, after the overlays. */
    private val onLivePosition: (LatLng) -> Unit,
) {
    internal companion object {
        const val MIN_DISTANCE_METERS = 0.5
        const val MIN_HEADING_CHANGE_DEGREES = 5.0
        const val MAX_TRAIL_POINTS = 1000

        /**
         * Live position only when it comes from the SELECTED source (same rule as the toolbar and
         * Home map): during the External connecting window the live provider can still be Internal,
         * and the map must not show that as the receiver's position.
         */
        fun matchesSelectedSource(fix: Fix, src: LocationSourceType): Boolean =
            if (src == LocationSourceType.INTERNAL) fix.provider == Provider.INTERNAL
            else fix.provider != Provider.INTERNAL

        /** A trail point is added after ≥ 0.5 m of movement or a ≥ 5° course change. */
        fun shouldAddTrailPoint(lastFix: Fix?, lastPos: LatLng?, pos: LatLng, fix: Fix): Boolean {
            lastFix ?: return true
            lastPos ?: return true
            if (calculateDistance(lastPos, pos) >= MIN_DISTANCE_METERS) return true
            val cc = fix.courseDeg; val lc = lastFix.courseDeg
            if (cc != null && lc != null && calculateHeadingChange(lc, cc) >= MIN_HEADING_CHANGE_DEGREES) return true
            return false
        }
    }

    // Map-display-only toggle for the live-location overlays. Does NOT affect fix collection,
    // recenter, or stakeout — those use the latest fix (lastFixLatLng / currentFix) directly.
    // Session state (MapUiState.showCurrentLocation), restored by the fragment.
    var showCurrentLocationOnMap = true
        private set
    var currentFix: Fix? = null
        private set
    var lastFixLatLng: LatLng? = null
        private set

    private var currentMarker: Marker? = null
    private var lastCurrentMarkerHue: Float? = null
    private var accuracyCircle: Circle? = null
    private var liveTrail: Polyline? = null
    private val trailPoints = mutableListOf<LatLng>()
    private var lastTrailFix: Fix? = null

    // Current-location heading indicator (a flat, rotating arrow shown under the My Location marker).
    private var headingMarker: Marker? = null
    private var headingDescriptor: BitmapDescriptor? = null
    private var lastAppliedHeadingDeg: Double? = null

    val headingAvailable: Boolean get() = headingMarker != null
    val accuracyCircleVisible: Boolean get() = accuracyCircle != null

    private fun requireContext(): Context = checkNotNull(context()) { "Fragment not attached to a context." }

    fun restoreShowCurrentLocation(show: Boolean) { showCurrentLocationOnMap = show }

    // ── GNSS fix collection ────────────────────────────────────────────────────

    /** Clears the live overlays and position when the active source changes (not on first emission). */
    fun observeProviderChanges(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousProvider: SourceSettings.ProviderChoice? = null
                activeProvider.collect { provider ->
                    if (previousProvider != null && previousProvider != provider) {
                        clearLiveTrail()
                        clearAccuracyCircle()
                        currentMarker?.remove(); currentMarker = null
                        removeHeadingIndicator()
                        lastFixLatLng = null
                        currentFix = null
                        lastCurrentMarkerHue = null
                    }
                    previousProvider = provider
                }
            }
        }
    }

    fun collectFixes(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    // Live position from the GNSS feed, only when it comes from the SELECTED source
                    // (same rule as the toolbar and Home map): during the External connecting window
                    // the live provider can still be Internal, and the map must not show that as the
                    // receiver's position.
                    kotlinx.coroutines.flow.combine(fixes, locationSource) { fix, src -> fix to src }
                        .conflate()
                        .sample(250)
                        .collect { (fix, src) ->
                            if (matchesSelectedSource(fix, src)) updateLiveTracking(fix) else clearLiveMarker()
                        }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // The map stops following GNSS until the screen restarts.
                    logFail("fix collector", e, 'E')
                }
            }
        }
        // Fixes stopped (signal lost / receiver disconnected): remove the live marker instead of
        // leaving a frozen position on the map that looks current. It reappears with the next fix.
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixStale.collect { stale -> if (stale) clearLiveMarker() }
            }
        }
    }

    /** Removes the live-position overlays (they come back with the next matching GNSS fix). */
    fun clearLiveMarker() {
        if (currentMarker == null && currentFix == null && lastFixLatLng == null) return
        clearAccuracyCircle()
        currentMarker?.remove(); currentMarker = null
        removeHeadingIndicator()
        currentFix = null
        lastFixLatLng = null   // recenter must not jump to a position that is no longer live
        lastCurrentMarkerHue = null
    }

    internal fun updateLiveTracking(fix: Fix) {
        try {
            if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) return
            val pos = LatLng(fix.latDeg, fix.lonDeg)
            currentFix = fix
            lastFixLatLng = pos   // always tracked — recenter/stakeout use this regardless of display toggle
            // The current-location overlays are display-only; the toggle hides them without affecting
            // fix collection, recenter, or stakeout (which run below/elsewhere off the latest fix).
            if (showCurrentLocationOnMap) {
                // Per fix: each step logs at most once a minute (logFail).
                try { updateCurrentMarker(fix.latDeg, fix.lonDeg, fix.rtkStatus) } catch (e: Exception) { logFail("live.updateCurrentMarker", e) }
                try { updateAccuracyCircle(pos, fix.hAccM) } catch (e: Exception) { logFail("live.updateAccuracyCircle", e) }
                try { updateLiveTrail(pos, fix) } catch (e: Exception) { logFail("live.updateLiveTrail", e) }
                try { updateHeadingIndicator() } catch (e: Exception) { logFail("live.updateHeadingIndicator", e) }
            }
            onLivePosition(pos)
        } catch (e: Exception) {
            logFail("live.updateLiveTracking", e, 'E')
        }
    }

    // ── My Location display toggle (map display only) ──────────────────────────

    /** Sets the My Location toggle and shows/hides the overlays accordingly. */
    fun setShowCurrentLocation(show: Boolean) {
        showCurrentLocationOnMap = show
        applyCurrentLocationVisibility()
    }

    /**
     * Shows/hides the live-location overlays (marker, accuracy circle, trail, heading arrow) only.
     * The latest fix is untouched, so recenter and stakeout keep working while hidden. When re-enabled,
     * the marker/accuracy/arrow are recreated from the latest known fix; the trail resumes on next fix.
     */
    private fun applyCurrentLocationVisibility() {
        if (showCurrentLocationOnMap) {
            currentFix?.let { f ->
                if (f.latDeg in -90.0..90.0 && f.lonDeg in -180.0..180.0) {
                    updateCurrentMarker(f.latDeg, f.lonDeg, f.rtkStatus)
                    updateAccuracyCircle(LatLng(f.latDeg, f.lonDeg), f.hAccM)
                }
            }
            updateHeadingIndicator()
        } else {
            currentMarker?.remove(); currentMarker = null; lastCurrentMarkerHue = null
            clearAccuracyCircle()
            clearLiveTrail()
            removeHeadingIndicator()
        }
    }

    /** onStop: the trail and accuracy circle restart with the next fixes. */
    fun onStop() {
        clearLiveTrail(); clearAccuracyCircle()
    }

    /** The view (and its map) is going away: remove every live overlay. The latest fix is kept. */
    fun onDestroyView() {
        try {
            clearLiveTrail(); clearAccuracyCircle()
            currentMarker?.remove(); currentMarker = null
            headingMarker?.remove(); headingMarker = null; lastAppliedHeadingDeg = null
        } catch (e: Exception) { logFail("onDestroyView cleanup", e) }
        // Drop the references even if a removal threw: they belong to the destroyed map.
        currentMarker = null; headingMarker = null; accuracyCircle = null; liveTrail = null
        lastCurrentMarkerHue = null
    }

    // ── Current position marker ────────────────────────────────────────────────

    private fun updateCurrentMarker(lat: Double, lon: Double, rtkStatus: RtkStatus = RtkStatus.NONE) {
        val pos = LatLng(lat, lon)
        lastFixLatLng = pos
        val hue = when (rtkStatus) {
            RtkStatus.FIX            -> BitmapDescriptorFactory.HUE_GREEN
            RtkStatus.FLOAT          -> BitmapDescriptorFactory.HUE_YELLOW
            RtkStatus.DGPS           -> BitmapDescriptorFactory.HUE_AZURE
            RtkStatus.SINGLE         -> BitmapDescriptorFactory.HUE_ORANGE
            RtkStatus.DEAD_RECKONING -> BitmapDescriptorFactory.HUE_VIOLET
            RtkStatus.NONE,
            RtkStatus.INVALID        -> BitmapDescriptorFactory.HUE_RED
        }
        if (currentMarker == null) {
            currentMarker = map()?.addMarker(
                MarkerOptions().position(pos).title("Current Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(hue))
            )
            lastCurrentMarkerHue = hue
        } else {
            try {
                currentMarker?.position = pos
                if (hue != lastCurrentMarkerHue) {
                    currentMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
                    lastCurrentMarkerHue = hue
                }
            } catch (e: Exception) { logFail("live.currentMarker position", e) }
        }
    }

    // ── Accuracy circle ────────────────────────────────────────────────────────

    private fun updateAccuracyCircle(position: LatLng, hAccM: Double?) {
        val map = map() ?: return
        val accuracy = hAccM?.takeIf { it > 0 && it < 1000 }
        if (accuracy == null) {
            accuracyCircle?.remove(); accuracyCircle = null
            return
        }
        val existing = accuracyCircle
        if (existing != null) {
            try { existing.center = position; existing.radius = accuracy; return }
            catch (e: Exception) { logFail("live.accuracyCircle update (recreating)", e); existing.remove(); accuracyCircle = null }
        }
        try {
            accuracyCircle = map.addCircle(
                CircleOptions().center(position).radius(accuracy)
                    .strokeColor(0x880000FF.toInt()).fillColor(0x220000FF.toInt()).strokeWidth(2f)
            )
        } catch (e: Exception) { logFail("live.accuracyCircle add", e) }
    }

    private fun clearAccuracyCircle() { accuracyCircle?.remove(); accuracyCircle = null }

    // ── Live trail ─────────────────────────────────────────────────────────────

    private fun updateLiveTrail(currentPos: LatLng, fix: Fix) {
        val map = map() ?: return
        if (!shouldAddTrailPoint(lastTrailFix, trailPoints.lastOrNull(), currentPos, fix)) return
        try {
            trailPoints.add(currentPos); lastTrailFix = fix
            if (trailPoints.size > MAX_TRAIL_POINTS) trailPoints.removeAt(0)
            if (trailPoints.size < 2) return
            val existing = liveTrail
            if (existing != null) {
                try { existing.points = trailPoints; return }
                catch (e: Exception) { logFail("live.liveTrail update (recreating)", e); existing.remove(); liveTrail = null }
            }
            liveTrail = map.addPolyline(
                // Not geodesic: at survey scale the great-circle and straight segments are identical, and the
                // geodesic version is recomputed for up to MAX_TRAIL_POINTS points on every new point.
                PolylineOptions().addAll(trailPoints).color(0xFFFF6B35.toInt()).width(4f).geodesic(false)
            )
        } catch (e: Exception) { logFail("live.liveTrail", e) }
    }

    private fun clearLiveTrail() { liveTrail?.remove(); liveTrail = null; trailPoints.clear(); lastTrailFix = null }

    // ── Heading indicator ──────────────────────────────────────────────────────

    /**
     * Draws/updates the heading arrow under the current-location marker. Heading source priority is
     * shared with stakeout (compass → course-over-ground when moving → none). The marker is FLAT so
     * Google Maps compensates for camera bearing automatically — no manual rotation needed. The arrow
     * is hidden when My Location is off, there's no live position, or no reliable direction exists.
     */
    fun updateHeadingIndicator() {
        val map = map()
        val pos = lastFixLatLng
        if (!showCurrentLocationOnMap || map == null || pos == null) { removeHeadingIndicator(); return }

        val (source, heading) = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.resolveHeading(
            preferCompass = true,
            compassHeadingDeg = compassHeading(),
            courseOverGroundDeg = currentFix?.courseDeg,
            speedMps = currentFix?.speedMps,
        )
        if (heading == null || source == io.github.pennstatecs.geokit.stakeout.HeadingSource.NORTH_UP) {
            removeHeadingIndicator(); return
        }

        // Throttle marker churn: skip tiny rotation deltas.
        val last = lastAppliedHeadingDeg
        val descriptor = headingArrowDescriptor() ?: return
        if (headingMarker == null) {
            headingMarker = map.addMarker(
                MarkerOptions().position(pos).icon(descriptor)
                    .anchor(0.5f, 0.5f).flat(true).rotation(heading.toFloat())
                    .zIndex(0.5f)   // under coordinate pins, with the current-location dot
            )
            lastAppliedHeadingDeg = heading
        } else {
            try {
                headingMarker?.position = pos
                if (last == null || io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.normalize180(heading - last).let { kotlin.math.abs(it) } >= 2.0) {
                    headingMarker?.rotation = heading.toFloat()
                    lastAppliedHeadingDeg = heading
                }
            } catch (e: Exception) { logFail("live.headingMarker update", e) }
        }
    }

    private fun removeHeadingIndicator() {
        try { headingMarker?.remove() } catch (_: Exception) {}
        headingMarker = null
        lastAppliedHeadingDeg = null
    }

    /** Lazily builds (and caches) the heading-arrow bitmap descriptor from the vector drawable. */
    private fun headingArrowDescriptor(): BitmapDescriptor? {
        headingDescriptor?.let { return it }
        return try {
            val d = ContextCompat.getDrawable(requireContext(), R.drawable.ic_location_heading) ?: return null
            val size = requireContext().dpToPx(36f)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bmp).also { headingDescriptor = it }
        } catch (e: Exception) { logFail("headingArrowDescriptor", e); null }
    }
}
