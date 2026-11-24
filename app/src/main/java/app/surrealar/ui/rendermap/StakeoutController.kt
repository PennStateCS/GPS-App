package app.surrealar.ui.rendermap

import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.StakeoutSettings
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.stakeout.HeadingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** Guidance audio cue (seam over ToneGenerator so tests can count acquire/release). */
internal interface GuidanceTone {
    fun play(arrived: Boolean)
    fun release()
}

/** Monotonic clock in ms (fun interface: no boxing on the per-fix path). */
internal fun interface ElapsedClock {
    fun nowMs(): Long
}

private class ToneGeneratorTone(private val tone: android.media.ToneGenerator) : GuidanceTone {
    override fun play(arrived: Boolean) {
        if (arrived) tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200)
        else tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80)
    }
    override fun release() = tone.release()
}

/**
 * Stakeout on the survey map: the one owner of the stakeout target (a saved point or a free map tap),
 * its marker (colour by distance) and line, the stakeout card readout (distance/bearing via geokit
 * Geodesic, ΔN/ΔE, status, accuracy warning) and the guidance mode (arrow, haptics, audio, keep
 * screen on, stale-position watcher).
 *
 * Distance/bearing maths and the guidance rules are geokit-core's; this class only presents them.
 * Guidance resources follow the screen: [pauseGuidanceFeedback]/[resumeGuidanceFeedback] (onPause /
 * onResume), released again in [onDestroyView]. The compass is the host's ([syncCompass] after any
 * guidance change; [wantsCompass] says whether guidance needs it).
 *
 * The stakeout itself outlives the view: every change is mirrored to a [StakeoutSession] (kept by the
 * activity-scoped MapUiStateViewModel), and [bindViews] restores it, so leaving the map for Details,
 * AR or another tab and coming back continues the same stakeout ([onMapReady] redraws the marker,
 * [revalidateTarget] re-checks a saved-point target against the database).
 */
internal class StakeoutController(
    private val map: () -> GoogleMap?,
    /** The fragment root (haptics + per-view keep-screen-on); null when there is no view. */
    private val view: () -> View?,
    /** The view-lifecycle scope (stale watcher dies with the view). */
    private val scope: () -> CoroutineScope,
    private val coordinateSystemService: CoordinateSystemService,
    private val currentFix: () -> Fix?,
    private val liveLatLng: () -> LatLng?,
    private val compassHeading: () -> Double?,
    private val isMagneticOnly: () -> Boolean,
    private val logHeadingSource: (HeadingSource) -> Unit,
    private val syncCompass: () -> Unit,
    private val logMapEvent: (category: String, message: String) -> Unit,
    private val showSnackbar: (String) -> Unit,
    /** The session kept outside this controller (MapUiStateViewModel), read when the view is bound. */
    private val loadSession: () -> StakeoutSession? = { null },
    /** Mirrors every target/mode/guidance change into that session. */
    private val saveSession: (StakeoutSession?) -> Unit = {},
    /** True while the GNSS feed is stale: a replayed old fix must not drive the readout. */
    private val isFixStale: () -> Boolean = { false },
    private val toneFactory: () -> GuidanceTone? = {
        ToneGeneratorTone(android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80))
    },
    private val clock: ElapsedClock = ElapsedClock { android.os.SystemClock.elapsedRealtime() },
) {
    internal companion object {
        // Stakeout thresholds come from Settings → Stakeout (toleranceMeters = green / "Within
        // tolerance", warningAccuracyMeters = low-accuracy warning). Amber spans out to this multiple
        // of the tolerance (the previous fixed 0.10 / 0.25 m pair had the same 2.5× ratio).
        const val STAKEOUT_AMBER_FACTOR = 2.5
        // Guidance falls back to the "waiting" state if no live fix arrives within this window.
        const val STAKEOUT_STALE_FIX_MS = 5000L
        const val STAKEOUT_STALE_TICK_MS = 1500L
    }

    // Stakeout
    var isStakeoutMode = false
        private set
    private var stakeoutTarget: LatLng? = null
    private var stakeoutMarker: Marker? = null
    private var stakeoutLine: Polyline? = null
    private var lastStakeoutMarkerHue: Float? = null
    private var stakeoutPanel: View? = null
    private var btnToggleStakeout: Button? = null   // GONE compat stub
    private var btnClearStakeout: View? = null
    private var txtStakeoutTarget: TextView? = null
    private var txtStakeoutDistance: TextView? = null
    private var txtStakeoutBearing: TextView? = null
    private var txtStakeoutDelta: TextView? = null
    private var txtStakeoutStatus: TextView? = null
    private var txtStakeoutAccuracy: TextView? = null
    private var stakeoutAccuracyDefaultColor: Int = 0

    // Stakeout guidance mode (display/feedback only — does not touch distance/bearing math)
    private var imgStakeoutArrow: ImageView? = null
    private var txtStakeoutTolerance: TextView? = null
    private var txtStakeoutHeadingNote: TextView? = null
    private var btnStakeoutGuidance: com.google.android.material.button.MaterialButton? = null
    /** Latest Settings → Stakeout values (collected by the host on the view lifecycle). */
    var settings = StakeoutSettings()
        private set
    var isGuidanceActive = false
        private set
    /** Coordinate id backing the active target (null for a free map-tap target). */
    var stakeoutTargetId: String? = null
        private set
    /** elapsedRealtime() of the last fix applied to the stakeout readout; null until the first. */
    private var lastStakeoutFixElapsedMs: Long? = null
    private var guidanceStaleJob: kotlinx.coroutines.Job? = null
    private val feedbackGate = io.github.pennstatecs.geokit.stakeout.StakeoutFeedbackGate()
    private var toneGenerator: GuidanceTone? = null
    /** Card target line (kept so the session can restore it). */
    private var targetLabel: String = "—"
    /**
     * The target is known to still exist. A saved-point target restored after view recreation is
     * unconfirmed until the first coordinate load ([revalidateTarget]); no marker or readout until then.
     */
    var isTargetConfirmed = true
        private set

    /** Guidance is running and set to steer by the compass. */
    val wantsCompass: Boolean get() = isGuidanceActive && settings.guidanceUsesCompassHeading

    fun bindViews(view: View) {
        stakeoutPanel = view.findViewById(R.id.stakeout_panel)
        btnToggleStakeout = view.findViewById(R.id.btn_toggle_stakeout)  // GONE stub
        btnClearStakeout = view.findViewById(R.id.btn_clear_stakeout)
        txtStakeoutTarget = view.findViewById(R.id.txt_stakeout_target)
        txtStakeoutDistance = view.findViewById(R.id.txt_stakeout_distance)
        txtStakeoutBearing = view.findViewById(R.id.txt_stakeout_bearing)
        txtStakeoutDelta = view.findViewById(R.id.txt_stakeout_delta)
        txtStakeoutStatus = view.findViewById(R.id.txt_stakeout_status)
        txtStakeoutAccuracy = view.findViewById(R.id.txt_stakeout_accuracy)
        // Capture the theme default text color so the low-accuracy warning can revert cleanly.
        stakeoutAccuracyDefaultColor = txtStakeoutAccuracy?.currentTextColor ?: 0

        imgStakeoutArrow = view.findViewById(R.id.img_stakeout_arrow)
        txtStakeoutTolerance = view.findViewById(R.id.txt_stakeout_tolerance)
        txtStakeoutHeadingNote = view.findViewById(R.id.txt_stakeout_heading_note)
        btnStakeoutGuidance = view.findViewById(R.id.btn_stakeout_guidance)

        btnToggleStakeout?.setOnClickListener { toggleStakeoutMode() }
        btnClearStakeout?.setOnClickListener { stopStakeout() }
        btnStakeoutGuidance?.setOnClickListener { if (isGuidanceActive) stopGuidance() else startGuidance() }
        restoreFromSession()
    }

    /**
     * Brings back a stakeout that was running when the previous view went away (Details/AR, tab
     * switch): target, card and guidance mode. The readout starts in the waiting state and is
     * recomputed from the next live fix (never from the position seen before leaving). Guidance
     * resources (tone, keep-screen-on, stale watcher, compass) are re-acquired by the host's normal
     * onResume path ([resumeGuidanceFeedback] + syncCompass), so nothing starts while not resumed.
     * A saved-point target waits for [revalidateTarget] before its marker/readout appear.
     */
    private fun restoreFromSession() {
        val s = loadSession() ?: return
        isStakeoutMode = true
        stakeoutTargetId = s.targetId
        stakeoutTarget = if (s.targetLat != null && s.targetLng != null) LatLng(s.targetLat, s.targetLng) else null
        targetLabel = s.targetLabel
        isTargetConfirmed = s.targetId == null
        lastStakeoutFixElapsedMs = null
        lastStakeoutMarkerHue = null
        stakeoutPanel?.visibility = View.VISIBLE
        txtStakeoutTarget?.text = s.targetLabel
        showStakeoutWaiting()
        isGuidanceActive = s.guidanceActive && stakeoutTarget != null
        feedbackGate.reset()   // no replayed arrival cue on return
        if (isGuidanceActive) showGuidanceControls()
        logMapEvent("STAKEOUT", "Stakeout restored targetId=${s.targetId ?: "free"} guidance=$isGuidanceActive")
    }

    /** The map is ready (also after view recreation): draws the target marker of a confirmed target. */
    fun onMapReady() {
        if (isStakeoutMode && isTargetConfirmed && stakeoutTarget != null) ensureTargetMarker()
    }

    /**
     * Every coordinate load (WGS84 points): re-checks a saved-point target. Deleted → stakeout ends
     * with an explanation; moved/renamed → the target follows the point (the readout waits for the
     * next fix). First confirmation after a restore draws the marker.
     */
    fun revalidateTarget(points: List<Coordinate>) {
        val id = stakeoutTargetId ?: return
        if (!isStakeoutMode) return
        val c = points.firstOrNull { it.id == id }
        if (c == null) {
            logMapEvent("STAKEOUT", "Stakeout target removed targetId=$id")
            stopStakeout(reason = "Stakeout target was removed")
            return
        }
        val latLng = LatLng(c.latitude, c.longitude)
        val moved = latLng != stakeoutTarget
        val renamed = c.name != targetLabel
        val firstConfirm = !isTargetConfirmed
        isTargetConfirmed = true
        if (moved || renamed) {
            stakeoutTarget = latLng
            targetLabel = c.name
            txtStakeoutTarget?.text = c.name
            try {
                stakeoutMarker?.position = latLng
                stakeoutMarker?.title = "Stakeout: ${c.name}"
            } catch (e: Exception) { logFail("stakeout marker update", e) }
            persist()
        }
        if (moved && !firstConfirm) { lastStakeoutFixElapsedMs = null; showStakeoutWaiting() }
        ensureTargetMarker()
    }

    private fun ensureTargetMarker() {
        val target = stakeoutTarget ?: return
        if (stakeoutMarker != null) return
        lastStakeoutMarkerHue = null
        stakeoutMarker = map()?.addMarker(
            MarkerOptions()
                .position(target)
                .title(if (stakeoutTargetId != null) "Stakeout: $targetLabel" else "Stakeout Target")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .zIndex(5f)   // keep the active target above the regular coordinate markers
        )
        if (stakeoutMarker != null) lastStakeoutMarkerHue = BitmapDescriptorFactory.HUE_RED
    }

    private fun persist() {
        saveSession(
            if (!isStakeoutMode) null
            else StakeoutSession(
                targetId = stakeoutTargetId,
                targetLat = stakeoutTarget?.latitude,
                targetLng = stakeoutTarget?.longitude,
                targetLabel = targetLabel,
                guidanceActive = isGuidanceActive,
            )
        )
    }

    private fun showGuidanceControls() {
        btnStakeoutGuidance?.text = "Stop Guidance"
        imgStakeoutArrow?.visibility = View.VISIBLE
        txtStakeoutTolerance?.visibility = View.VISIBLE
        txtStakeoutTolerance?.text = "Tolerance: " + coordinateSystemService.lengthText(settings.toleranceMeters, 2)
    }

    /**
     * Keep the latest stakeout settings in memory for the guidance loop, and apply changes to
     * running guidance immediately (e.g. toggling audio/compass/keep-screen-on mid-stakeout).
     */
    fun onSettingsChanged(s: StakeoutSettings) {
        settings = s
        reconcileGuidanceResources()
    }

    /**
     * The view (and its map) is going away. Releases the guidance resources (tone, keep-screen-on,
     * stale watcher) but keeps the stakeout and the guidance mode, so the next view restores them
     * (see [restoreFromSession]). Removes the line and drops the marker and view references (the
     * marker died with the map; it used to be kept and then touched on the next map).
     */
    fun onDestroyView() {
        guidanceStaleJob?.cancel(); guidanceStaleJob = null
        releaseToneGenerator()
        setKeepScreenOn(false)
        feedbackGate.reset()
        // A saved-point target is re-checked against the database when the view comes back.
        isTargetConfirmed = stakeoutTargetId == null
        try { stakeoutLine?.remove() } catch (e: Exception) { logFail("onDestroyView cleanup", e) }
        stakeoutLine = null
        stakeoutMarker = null
        lastStakeoutMarkerHue = null
        stakeoutPanel = null; btnToggleStakeout = null; btnClearStakeout = null
        txtStakeoutTarget = null; txtStakeoutDistance = null; txtStakeoutBearing = null
        txtStakeoutDelta = null; txtStakeoutStatus = null; txtStakeoutAccuracy = null
        imgStakeoutArrow = null; txtStakeoutTolerance = null; txtStakeoutHeadingNote = null
        btnStakeoutGuidance = null
    }

    // ── Stakeout ───────────────────────────────────────────────────────────────

    /** Stakes out a saved coordinate (selected-point card "Stakeout"). */
    fun startForCoordinate(coord: Coordinate) {
        val id = coord.id
        val latLng = LatLng(coord.latitude, coord.longitude)

        // Start stakeout mode if not already active
        if (!isStakeoutMode) {
            isStakeoutMode = true
            stakeoutPanel?.visibility = View.VISIBLE
        }

        stakeoutTarget = latLng
        stakeoutTargetId = id
        targetLabel = coord.name
        isTargetConfirmed = true
        lastStakeoutFixElapsedMs = null
        txtStakeoutTarget?.text = coord.name
        showStakeoutWaiting()

        stakeoutMarker?.remove()
        stakeoutMarker = null
        ensureTargetMarker()
        persist()

        refreshStakeout()
        logMapEvent("STAKEOUT", "Stakeout started targetId=${coord.id}")   // id only: no point names in logs
        showSnackbar("Stakeout started: ${coord.name}")
    }

    /** Ends stakeout. [reason] replaces the "Stakeout stopped" snackbar (e.g. the target was deleted). */
    fun stopStakeout(reason: String? = null) {
        if (!isStakeoutMode) return
        if (isGuidanceActive) stopGuidance()
        isStakeoutMode = false
        stakeoutPanel?.visibility = View.GONE
        stakeoutTarget = null
        stakeoutTargetId = null
        targetLabel = "—"
        isTargetConfirmed = true
        lastStakeoutFixElapsedMs = null
        txtStakeoutTarget?.text = "—"
        showStakeoutWaiting()
        stakeoutMarker?.remove(); stakeoutMarker = null; lastStakeoutMarkerHue = null
        stakeoutLine?.remove(); stakeoutLine = null
        persist()
        logMapEvent("STAKEOUT", "Stakeout stopped")
        showSnackbar(reason ?: "Stakeout stopped")
    }

    private fun toggleStakeoutMode() {
        if (isStakeoutMode) {
            stopStakeout()
        } else {
            isStakeoutMode = true
            stakeoutPanel?.visibility = View.VISIBLE
            stakeoutMarker?.remove(); stakeoutMarker = null
            persist()
        }
    }

    /** A map tap: sets a free stakeout target in stakeout mode. Returns false when not staking out. */
    fun onMapTap(latLng: LatLng): Boolean {
        if (!isStakeoutMode) return false
        try {
            stakeoutTarget = latLng
            stakeoutTargetId = null   // free map-tap target (not tied to a saved coordinate)
            isTargetConfirmed = true
            lastStakeoutFixElapsedMs = null
            targetLabel = String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude)
            txtStakeoutTarget?.text = targetLabel
            if (stakeoutMarker == null) {
                ensureTargetMarker()
            } else {
                try { stakeoutMarker?.position = latLng } catch (e: Exception) { logFail("stakeout marker move", e) }
            }
            persist()
            refreshStakeout()
        } catch (e: Exception) { logFail("map click stakeout", e) }
        return true
    }

    // ── Stakeout calculations ──────────────────────────────────────────────────

    fun updateStakeoutCalculations(currentPos: LatLng) {
        val target = stakeoutTarget ?: return
        if (!isStakeoutMode) return
        // A restored saved-point target is not used until the database confirms it still exists,
        // and a fix replayed while the feed is stale is not a live position.
        if (!isTargetConfirmed || isFixStale()) return
        lastStakeoutFixElapsedMs = clock.nowMs()
        try {
            // Distance + bearing: WGS84 geodesic (geokit-core Geodesic).
            val distance = calculateDistance(currentPos, target)
            val bearing = calculateBearing(currentPos, target)
            // Distance in the units chosen under Settings → Coordinates.
            txtStakeoutDistance?.text = coordinateSystemService.distanceText(distance)
            // Bearing shown with an 8-point compass label, e.g. "NE 42°".
            txtStakeoutBearing?.text =
                String.format(Locale.getDefault(), "%s %.0f°", compassPoint(bearing), bearing)
            val tol = settings.toleranceMeters
            val color = when {
                distance < tol                         -> 0xFF4CAF50.toInt()
                distance < tol * STAKEOUT_AMBER_FACTOR -> 0xFFFF9800.toInt()
                else                                   -> 0xFFF44336.toInt()
            }
            txtStakeoutDistance?.setTextColor(color)
            txtStakeoutBearing?.setTextColor(color)

            // Display-only north/east offsets (true north, local tangent plane) in the chosen units.
            val (dN, dE) = northEastDeltaMeters(currentPos, target)
            txtStakeoutDelta?.text =
                "ΔN ${coordinateSystemService.deltaText(dN, 2)}    ΔE ${coordinateSystemService.deltaText(dE, 2)}"

            // State line: within tolerance vs. which way to move.
            txtStakeoutStatus?.text =
                if (distance < tol) "Within tolerance"
                else "Move ${compassDirectionWord(bearing)}"

            updateStakeoutAccuracy()
            updateStakeoutMarkerColor(distance)
            updateStakeoutLine(currentPos, target)
            updateGuidance(distance, bearing)
        } catch (e: Exception) {
            logFail("stakeout.calculations", e)
        }
    }

    /** Target is set but there is no live fix yet — show a clear "waiting" state. Display-only. */
    private fun showStakeoutWaiting() {
        txtStakeoutDistance?.text = "—"
        txtStakeoutBearing?.text = "—"
        txtStakeoutDelta?.text = "—"
        txtStakeoutStatus?.text = "Waiting for live position…"
        txtStakeoutAccuracy?.visibility = View.GONE
    }

    /** Re-render the stakeout card from the latest fix, or the waiting state if none yet. */
    private fun refreshStakeout() {
        val fix = liveLatLng()
        if (fix != null) updateStakeoutCalculations(fix) else showStakeoutWaiting()
    }

    /** Live horizontal accuracy (the only GNSS detail in the card) + non-blocking low-accuracy warning. */
    private fun updateStakeoutAccuracy() {
        val tv = txtStakeoutAccuracy ?: return
        val acc = currentFix()?.hAccM
        if (acc == null || acc <= 0.0) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        if (acc > settings.warningAccuracyMeters) {
            tv.text = String.format(
                Locale.getDefault(), "Accuracy ±%.2f m · Low accuracy — stakeout may be unreliable", acc
            )
            tv.setTextColor(0xFFF44336.toInt())
        } else {
            tv.text = String.format(Locale.getDefault(), "Accuracy ±%.2f m", acc)
            tv.setTextColor(stakeoutAccuracyDefaultColor)
        }
    }

    /** 8-point compass abbreviation for a bearing in degrees (display-only; geokit-core helper). */
    private fun compassPoint(bearing: Double): String = io.github.pennstatecs.geokit.heading.Compass.point8(bearing)

    /** Human-readable "move" word for a bearing (display-only). */
    private fun compassDirectionWord(bearing: Double): String = when (compassPoint(bearing)) {
        "N"  -> "north"
        "NE" -> "northeast"
        "E"  -> "east"
        "SE" -> "southeast"
        "S"  -> "south"
        "SW" -> "southwest"
        "W"  -> "west"
        else -> "northwest"
    }

    /** Local north/east offsets (metres) from [from] to [to] — same WGS84 frame as distance/bearing. */
    private fun northEastDeltaMeters(from: LatLng, to: LatLng): Pair<Double, Double> =
        io.github.pennstatecs.geokit.ellipsoid.Geodesic.northEastDeltaM(from.latitude, from.longitude, to.latitude, to.longitude).let { it.northM to it.eastM }

    // ── Stakeout guidance mode (display + feedback only) ───────────────────────

    /** "Start Guidance" button (internal so tests can drive it without the layout). */
    internal fun startGuidance() {
        if (!isStakeoutMode || stakeoutTarget == null) { showSnackbar("Select a stakeout target first"); return }
        isGuidanceActive = true
        feedbackGate.reset()
        showGuidanceControls()
        persist()
        syncCompass()
        if (settings.enableAudio) ensureToneGenerator()
        if (settings.keepScreenOnDuringStakeout) setKeepScreenOn(true)
        startStaleWatcher()
        refreshStakeout()
        logMapEvent("STAKEOUT", "Stakeout guidance started toleranceM=${settings.toleranceMeters}")
    }

    fun stopGuidance() {
        isGuidanceActive = false
        persist()
        btnStakeoutGuidance?.text = "Start Guidance"
        imgStakeoutArrow?.visibility = View.GONE
        txtStakeoutTolerance?.visibility = View.GONE
        txtStakeoutHeadingNote?.visibility = View.GONE
        guidanceStaleJob?.cancel(); guidanceStaleJob = null
        syncCompass()   // My Location may still need the compass even after guidance stops
        releaseToneGenerator()
        setKeepScreenOn(false)
        feedbackGate.reset()
        logMapEvent("STAKEOUT", "Stakeout guidance stopped")
    }

    /**
     * Ticks while guidance is active; if no fresh fix arrives within [STAKEOUT_STALE_FIX_MS] the
     * readout drops to the waiting state and the feedback latch resets, so a frozen arrow never
     * looks live and re-acquiring position fires feedback cleanly. Lifecycle-scoped → dies with the view.
     */
    private fun startStaleWatcher() {
        guidanceStaleJob?.cancel()
        guidanceStaleJob = scope().launch {
            while (isGuidanceActive) {
                val stale = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.isPositionStale(
                    lastStakeoutFixElapsedMs, clock.nowMs(), STAKEOUT_STALE_FIX_MS
                )
                if (stale) {
                    txtStakeoutStatus?.text = "Waiting for live position…"
                    txtStakeoutHeadingNote?.visibility = View.GONE
                    feedbackGate.reset()
                }
                kotlinx.coroutines.delay(STAKEOUT_STALE_TICK_MS)
            }
        }
    }

    /** Applies live settings changes to running guidance resources (sensor/audio/screen). */
    private fun reconcileGuidanceResources() {
        if (!isGuidanceActive) return
        syncCompass()
        if (!settings.enableAudio) releaseToneGenerator()
        setKeepScreenOn(settings.keepScreenOnDuringStakeout)
        txtStakeoutTolerance?.text = "Tolerance: " + coordinateSystemService.lengthText(settings.toleranceMeters, 2)
    }

    /** Pause feedback (audio/screen/ticker) but keep the active target — for backgrounding. Compass
     *  is handled by the host's onPause/syncCompass since My Location may also be using it. */
    fun pauseGuidanceFeedback() {
        if (!isGuidanceActive) return
        guidanceStaleJob?.cancel(); guidanceStaleJob = null
        releaseToneGenerator()
        setKeepScreenOn(false)
        feedbackGate.reset()   // returning to the map shouldn't replay a stale arrival cue
    }

    fun resumeGuidanceFeedback() {
        if (!isGuidanceActive) return
        if (settings.enableAudio) ensureToneGenerator()
        if (settings.keepScreenOnDuringStakeout) setKeepScreenOn(true)
        startStaleWatcher()
    }

    /** Updates the arrow + fires throttled haptics/audio. Uses the passed-in distance/bearing — no new math. */
    private fun updateGuidance(distanceMeters: Double, bearingToTargetDeg: Double) {
        if (!isGuidanceActive) return
        val s = settings
        val (source, heading) = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.resolveHeading(
            preferCompass = s.guidanceUsesCompassHeading,
            compassHeadingDeg = compassHeading(),
            courseOverGroundDeg = currentFix()?.courseDeg,
            speedMps = currentFix()?.speedMps,
        )
        val relative = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.relativeBearing(bearingToTargetDeg, heading)
        // Arrow shows the relative direction when heading is known, else points at the map-north bearing.
        imgStakeoutArrow?.rotation = (relative ?: bearingToTargetDeg).toFloat()
        logHeadingSource(source)
        if (source == HeadingSource.NORTH_UP) {
            txtStakeoutHeadingNote?.visibility = View.VISIBLE
            txtStakeoutHeadingNote?.text = "Arrow is north-up until heading is available."
        } else if (source == HeadingSource.COMPASS && isMagneticOnly()) {
            // No position at all yet → no declination: the compass heading is magnetic (~11.6° off here).
            txtStakeoutHeadingNote?.visibility = View.VISIBLE
            txtStakeoutHeadingNote?.text = "Magnetic heading (no position yet)"
        } else {
            txtStakeoutHeadingNote?.visibility = View.GONE
        }
        val status = io.github.pennstatecs.geokit.stakeout.StakeoutGuidance.status(
            hasTarget = true, hasPosition = true, distanceMeters = distanceMeters, toleranceMeters = s.toleranceMeters
        )
        when (feedbackGate.onUpdate(status, clock.nowMs())) {
            io.github.pennstatecs.geokit.stakeout.StakeoutFeedback.ENTERED_TOLERANCE -> {
                // Distance to target only (no positions): the gate fires once per arrival.
                logMapEvent("STAKEOUT", "Stakeout target within tolerance distM=${"%.3f".format(java.util.Locale.US, distanceMeters)} toleranceM=${s.toleranceMeters}")
                if (s.enableHaptics) haptic(arrived = true); if (s.enableAudio) beep(arrived = true)
            }
            io.github.pennstatecs.geokit.stakeout.StakeoutFeedback.NAVIGATING_PULSE -> {
                if (s.enableHaptics) haptic(arrived = false); if (s.enableAudio) beep(arrived = false)
            }
            io.github.pennstatecs.geokit.stakeout.StakeoutFeedback.NONE -> {}
        }
    }

    private fun ensureToneGenerator() {
        if (toneGenerator == null) {
            toneGenerator = try {
                toneFactory()
            } catch (e: Exception) { logFail("ToneGenerator init", e); null }
        }
    }

    private fun releaseToneGenerator() {
        try { toneGenerator?.release() } catch (_: Exception) {}
        toneGenerator = null
    }

    private fun haptic(arrived: Boolean) {
        val v = view() ?: return
        v.performHapticFeedback(
            if (arrived) android.view.HapticFeedbackConstants.CONFIRM
            else android.view.HapticFeedbackConstants.CLOCK_TICK
        )
    }

    private fun beep(arrived: Boolean) {
        ensureToneGenerator()
        try {
            toneGenerator?.play(arrived)
        } catch (e: Exception) { logFail("stakeout beep", e) }
    }

    /** Uses a per-view keep-screen-on request so it never clobbers a global window flag. */
    private fun setKeepScreenOn(on: Boolean) {
        try { view()?.keepScreenOn = on } catch (e: Exception) { logFail("keepScreenOn", e) }
    }

    private fun updateStakeoutMarkerColor(distance: Double) {
        val tol = settings.toleranceMeters
        val hue = when {
            distance < tol                         -> BitmapDescriptorFactory.HUE_GREEN
            distance < tol * STAKEOUT_AMBER_FACTOR -> BitmapDescriptorFactory.HUE_ORANGE
            else                                   -> BitmapDescriptorFactory.HUE_RED
        }
        if (hue != lastStakeoutMarkerHue) {
            stakeoutMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(hue))
            lastStakeoutMarkerHue = hue
        }
    }

    private fun updateStakeoutLine(from: LatLng, to: LatLng) {
        val map = map() ?: return
        val existing = stakeoutLine
        if (existing != null) {
            try { existing.points = listOf(from, to); return }
            catch (e: Exception) { logFail("stakeoutLine update (recreating)", e); try { existing.remove() } catch (_: Exception) {}; stakeoutLine = null }
        }
        stakeoutLine = try {
            map.addPolyline(
                PolylineOptions().add(from, to).color(0xCCFF5722.toInt()).width(4f).geodesic(true)
            )
        } catch (_: Exception) { null }
    }
}
