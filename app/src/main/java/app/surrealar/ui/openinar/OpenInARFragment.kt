package app.surrealar.ui.openinar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import app.surrealar.util.ArSessionDiagnostics
import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.ArTrackLog
import app.surrealar.util.diagnostics.ArTrackRow
import app.surrealar.util.argbIntToRgba
import io.github.pennstatecs.geokit.ar.accuracy.ArPlacementError
import io.github.pennstatecs.geokit.ar.gesture.ArMoveMath
import io.github.pennstatecs.geokit.ar.model.ModelMove
import io.github.pennstatecs.geokit.ar.model.ModelPlacement
import io.github.pennstatecs.geokit.ar.model.ModelPlacementOrigin
import io.github.pennstatecs.geokit.ar.projection.projectToScreen
import io.github.pennstatecs.geokit.heading.Compass
import io.github.pennstatecs.geokit.ellipsoid.Geodesic

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import app.surrealar.R
import app.surrealar.databinding.FragmentOpenInArBinding
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.mock.MockInjectionStatus
import io.github.pennstatecs.geokit.gnss.model.Fix
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * AR Fragment that displays surveying coordinates as geospatial anchors in AR space.
 * Uses GNSS fixes from FixSwitchboard for accurate geospatial positioning.
 */
@AndroidEntryPoint
@SuppressLint("SetTextI18n")
class OpenInARFragment : Fragment(), GLSurfaceView.Renderer {

    private var _binding: FragmentOpenInArBinding? = null
    private val binding get() = _binding!!

    // Inject GNSS switchboard for current GPS location
    @Inject
    lateinit var fixSwitchboard: FixSwitchboard
    @Inject lateinit var coordinateSystemService: app.surrealar.coords.CoordinateSystemService
    /** Process-lifetime scope (Hilt singleton); used to close the ARCore session off the main thread. */
    @Inject lateinit var appScope: kotlinx.coroutines.CoroutineScope

    /** Read-only access to the selected GNSS source and mock-location setting for AR diagnostics. */
    @Inject
    lateinit var settingsRepo: SettingsRepository

    /** Model readiness, logged once per model per AR session so the report says why a model is off. */
    @Inject lateinit var readinessRepository: app.surrealar.ui.models.readiness.ModelReadinessRepository
    private val readinessLoggedModelIds = mutableSetOf<String>()

    private val viewModel: OpenInARViewModel by viewModels()

    // Current GNSS fix from switchboard (surveying-grade GPS)
    @Volatile private var currentGnssFix: Fix? = null
    /** elapsedRealtime when [currentGnssFix] last changed (ar-track.csv fix age). */
    @Volatile private var currentGnssFixMonoMs: Long = 0L

    // ar-track.csv recorder (debug builds; GL thread). The ENU origin is the session's first fix and is
    // cleared on pause, so each resume starts a new section (ARCore may relocalize across a pause).
    @Volatile private var arTrackOrigin: io.github.pennstatecs.geokit.local.LocalEnu? = null
    private var arTrackLastFix: Fix? = null
    private var arTrackLastRowMs = 0L
    /** Last logged GNSS provider — used to suppress repeated identical fix logs. */
    private var lastLoggedProvider: io.github.pennstatecs.geokit.gnss.model.Provider? = null

    // Source/mock context for AR diagnostics (collected on the main thread; read anywhere).
    @Volatile private var selectedLocationSource: String? = null
    @Volatile private var mockLocationEnabled: Boolean? = null
    /** Guards the one-per-AR-session diagnostic header. Reset in onCreateView. */
    private var loggedArSessionHeader = false

    // ARCore session management
    private var session: Session? = null
    private var installRequested = false
    private var availabilityPolling = false
    /**
     * True only after `session.resume()` has been called successfully.
     * Guards `onDrawFrame` against calling `Session.update` on a paused session —
     * the GL thread starts as soon as the surface is created, which can be before
     * [onResume] fires (causing AR_ERROR_SESSION_PAUSED spam otherwise).
     */
    @Volatile private var sessionReady = false

    /**
     * True once the session has been configured with geospatial mode ENABLED. When false, the device
     * either doesn't support geospatial or enabling it failed — every geo-anchor will silently fail,
     * so this drives a clearer status string and is the first thing recorded in the diagnostic export.
     */
    @Volatile private var geospatialAvailable = true

    // OpenGL renderers for different AR elements
    private var backgroundRenderer: BackgroundRenderer? = null  // Camera background (also owns OES texture)
    private var cubeRenderer: SimpleObjectRenderer? = null      // 3D objects/pins
    private var planeVisualizer: PlaneVisualizer? = null        // Detected planes
    private var pointCloudRenderer: PointCloudRenderer? = null  // Point cloud visualization
    // Filament-based renderer for GLB 3D models overlaid on the AR camera feed
    private var filamentRenderer: ArFilamentRenderer? = null

    // Data class representing a geospatial item to display in AR.
    // `label` removed — coord name is always read from coordWithModel.coordinate.name directly.
    private data class GeoItem(
        val lat: Double,
        val lng: Double,
        val alt: Double?,
        val rgba: FloatArray,
        val modelFilePath: String?,
        val modelId: String?,
        /** Full coordinate + model data — used for tap-to-inspect and label text. */
        val coordWithModel: CoordWithModel
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as GeoItem
            return lat == other.lat &&
                    lng == other.lng &&
                    alt == other.alt &&
                    rgba.contentEquals(other.rgba) &&
                    modelFilePath == other.modelFilePath &&
                    modelId == other.modelId &&
                    coordWithModel.coordinate.id == other.coordWithModel.coordinate.id
        }

        override fun hashCode(): Int {
            var result = lat.hashCode()
            result = 31 * result + lng.hashCode()
            result = 31 * result + (alt?.hashCode() ?: 0)
            result = 31 * result + rgba.contentHashCode()
            result = 31 * result + (modelFilePath?.hashCode() ?: 0)
            result = 31 * result + (modelId?.hashCode() ?: 0)
            result = 31 * result + coordWithModel.coordinate.id.hashCode()
            return result
        }
    }

    // Collections for managing geospatial anchors.
    // @Volatile + immutable List: main thread assigns a new snapshot; GL thread reads it safely.
    @Volatile private var geoItems: List<GeoItem> = emptyList()

    /**
     * Tracks each live ARCore geospatial anchor alongside its rendering metadata.
     *
     * Not a data class — [rgba] is a FloatArray and this type is never compared by value.
     * [rgba] is eagerly pre-computed from `coordWithModel.coordinate.color` so it is only
     * allocated once per anchor rather than on every render frame.
     * [modelFilePath] and [modelId] are derived from [coordWithModel] via properties.
     */
    private class AnchorEntry(
        val anchor: Anchor,
        val coordWithModel: CoordWithModel,
        /** Anchored on terrain (snapped to the detected ground) rather than at its stored height. */
        val onTerrain: Boolean = false,
        /** TerrainAnchorState name from the resolve future (terrain anchors only), for the debug panel. */
        val terrainState: String? = null,
    ) {
        /** Pre-computed normalised RGBA for GLES pin rendering. */
        val rgba: FloatArray = argbIntToRgba(coordWithModel.coordinate.color)
        val modelFilePath: String? get() = coordWithModel.modelFilePath
        val modelId: String?       get() = coordWithModel.modelId
    }

    // geoAnchors is read on the GL thread and mutated on the main thread (onDestroyView,
    // rebuildGeoAnchorsIfNeeded). CopyOnWriteArrayList makes iteration on the GL thread
    // safe without locking — writes create a new backing array so readers never block.
    private val geoAnchors: MutableList<AnchorEntry> = CopyOnWriteArrayList()

    /** A terrain anchor still resolving (resolveAnchorOnTerrainAsync); joins [geoAnchors] on success. */
    private class PendingTerrainAnchor(
        val future: com.google.ar.core.ResolveAnchorOnTerrainFuture,
        val coordWithModel: CoordWithModel,
    )
    private val pendingTerrainAnchors: MutableList<PendingTerrainAnchor> = CopyOnWriteArrayList()

    /** Detaches all geo anchors and cancels terrain resolves still in flight. GL thread (or after it stops). */
    private fun clearGeoAnchors() {
        // Best-effort teardown: an anchor/future that is already detached/done throws and needs nothing.
        for (entry in geoAnchors) try { entry.anchor.detach() } catch (_: Exception) {}
        geoAnchors.clear()
        for (p in pendingTerrainAnchors) runCatching { p.future.cancel() }
        pendingTerrainAnchors.clear()
        terrainResolvedCount = 0
        terrainFailedStates.clear()
    }

    /**
     * Polls terrain resolves (GL thread, once per frame). Polling instead of the callback keeps every
     * [geoAnchors] change on the GL thread. A resolved anchor starts drawing like any other; a failed
     * one is logged and marked failed.
     */
    private fun pollTerrainAnchors() {
        if (pendingTerrainAnchors.isEmpty()) return
        for (p in pendingTerrainAnchors) {
            val f = p.future
            if (f.state != com.google.ar.core.FutureState.DONE) {
                if (f.state == com.google.ar.core.FutureState.CANCELLED) pendingTerrainAnchors.remove(p)
                continue
            }
            pendingTerrainAnchors.remove(p)
            val c = p.coordWithModel.coordinate
            val state = runCatching { f.resultTerrainAnchorState }.getOrNull()
            val anchor = runCatching { f.resultAnchor }.getOrNull()
            if (state == com.google.ar.core.Anchor.TerrainAnchorState.SUCCESS && anchor != null) {
                geoAnchors.add(AnchorEntry(anchor, p.coordWithModel, onTerrain = true, terrainState = state.name))
                terrainResolvedCount++
                DiagnosticsLogger.d(DIAG) { "Terrain anchor resolved coordinateId=${c.id}" }
            } else {
                runCatching { anchor?.detach() }   // best effort: a failed resolve may have no anchor
                if (p.coordWithModel.modelFilePath != null)
                    ArSessionDiagnostics.setStatus(c.id, ArSessionDiagnostics.ModelStatus.FAILED, "terrain_anchor_failed")
                val stateName = state?.name ?: "UNKNOWN"
                terrainFailedStates[stateName] = (terrainFailedStates[stateName] ?: 0) + 1
                DiagnosticsLogger.w(DIAG, "Terrain anchor resolve failed coordinateId=${c.id} state=$stateName")
            }
        }
        // One summary per rebuild, once every resolve of the batch has settled.
        if (pendingTerrainAnchors.isEmpty() && (terrainResolvedCount > 0 || terrainFailedStates.isNotEmpty())) {
            val failed = terrainFailedStates.values.sum()
            DiagnosticsLogger.i(DIAG, "Terrain anchors settled resolved=$terrainResolvedCount failed=$failed" +
                if (failed > 0) " failedStates=[${terrainFailedStates.entries.joinToString(",") { "${it.key}:${it.value}" }}]" else "")
            terrainResolvedCount = 0
            terrainFailedStates.clear()
        }
    }
    /** Logging only: terrain resolves settled in the current batch (GL thread). */
    private var terrainResolvedCount = 0
    private val terrainFailedStates = HashMap<String, Int>()
    // @Volatile: written on main thread (setCoordinates), read on GL thread (rebuildGeoAnchorsIfNeeded).
    @Volatile private var geoAnchorsCreated = false

    // All debug test-anchor logic is encapsulated in TestAnchorController.
    private lateinit var testAnchorController: TestAnchorController

    // OpenGL transformation matrices
    private val proj = FloatArray(16)    // Projection matrix
    private val view = FloatArray(16)    // View matrix
    private val model = FloatArray(16)   // Model matrix
    private val modelScaled = FloatArray(16)  // Scaled model matrix
    private val vp = FloatArray(16)      // View-projection matrix
    private val mvp = FloatArray(16)     // Model-view-projection matrix

    // Display rotation and surface dimensions — written on main thread, read on GL thread.
    @Volatile private var displayRotation: Int = Surface.ROTATION_0
    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0

    // Cached 2-D screen positions of visible geospatial pins.
    // CoordWithModel is embedded so the touch listener never needs to access geoAnchors.
    // Written by GL thread each frame; read by main-thread touch listener (volatile).
    private data class PinScreenEntry(val x: Float, val y: Float, val coordWithModel: CoordWithModel)
    @Volatile private var pinScreenCache: List<PinScreenEntry> = emptyList()

    // ── Move model (see startModelMove) ──────────────────────────────────────────────────────────
    /** Coordinate id whose model is being moved, or null. Written on main, read on the GL thread. */
    @Volatile private var moveTargetId: String? = null
    /** Camera and the move target's anchor matrix from the last tracked frame (GL thread → touch handler). */
    private class MoveFrame(val camera: ArMoveMath.CameraState, val anchor: FloatArray?)
    @Volatile private var moveFrame: MoveFrame? = null
    /** GL thread only: the move target's anchor matrix captured by collectGeoAnchors this frame. */
    private var frameMoveAnchor: FloatArray? = null
    /** Session-only moves per coordinate id, on top of the saved placement; gone when AR closes (main thread). */
    private val sessionMoves = HashMap<String, ModelMove>()
    /** Main thread: the target's session move when Move mode started (Cancel restores it). */
    private var moveStartSession = ModelMove()
    private var moveTicker: kotlinx.coroutines.Job? = null
    private var nudgeStepM = 0.10f
    // Gesture state (main thread).
    private var dragStartHit: FloatArray? = null
    private var dragStartMove = ModelMove()
    private var dragPlaneY = 0f
    private var twisting = false
    private var twistStartAngle = 0f
    private var twistStartCy = 0f
    private var twistDistM = 0f
    private var twistStartMove = ModelMove()
    /** Test cube: placement requested (main → GL thread), and the temporary point itself (main thread). */
    @Volatile private var testCubeRequested = false
    private var testCube: CoordWithModel? = null
    /** The database points last received, so the test cube can be added or removed without waiting for Room. */
    private var lastDbItems: List<CoordWithModel> = emptyList()
    /** ARCore geospatial accuracy and the AR quality level, for the move panel (GL thread → main). */
    @Volatile private var lastGeoHAccM: Double? = null
    @Volatile private var lastGeoYawAccDeg: Double? = null
    @Volatile private var lastArQualityGood = false

    /**
     * Snapshot of ARCore Earth state extracted on the GL thread before posting to the main thread.
     * ARCore native objects ([Earth], [GeospatialPose]) must NOT be accessed from the main thread;
     * only primitive/string values extracted here are safe to use in [buildDebugText].
     */
    private data class EarthDebugSnapshot(
        val earthStateName: String,
        val camLat: Double?,
        val camLon: Double?,
        val camAlt: Double?,
        val camHeading: Double?
    )

    // Last label set posted to the overlay — used to skip redundant post() calls (60fps).
    @Volatile private var lastPostedLabels: List<CoordinateLabelOverlay.LabelEntry> = emptyList()

    // Last off-screen arrow set posted — used to skip redundant invalidations.
    @Volatile private var lastPostedArrows: List<OffScreenPinIndicatorOverlay.ArrowEntry> = emptyList()

    // Last model progress (inScene, total) — skip chip update when unchanged.
    @Volatile private var lastModelProgress: Pair<Int, Int>? = null
    // Coroutine job that hides the chip after all models finish loading.
    private var hideModelProgressJob: kotlinx.coroutines.Job? = null

    // Set true on main thread (button click); consumed on GL thread to detach & rebuild anchors.
    @Volatile private var shouldRebuildAnchors = false
    /** Logging only: why [shouldRebuildAnchors] was set ("user" / "altitude_mode" / "settings" / "initial"). */
    @Volatile private var rebuildReason: String = "initial"

    /**
     * RTK status at the time the current set of geo-anchors was created.
     * Used to auto re-anchor when quality improves (e.g., SINGLE → RTK FIX).
     * Written and read exclusively on the GL thread.
     */
    private var lastAnchorRtkStatus: io.github.pennstatecs.geokit.gnss.model.RtkStatus? = null

    /**
     * Timestamp (from [System.currentTimeMillis]) of the last anchor rebuild (manual or auto).
     * Enforces [REANCHOR_COOLDOWN_MS] to prevent ARCore resource exhaustion when RTK quality
     * steps up rapidly (e.g., SINGLE → DGPS → FLOAT → FIX in <500ms when switching to RS2+).
     * Written and read exclusively on the GL thread.
     */
    private var lastReanchorTimeMs: Long = 0L

    /**
     * True once the GNSS-accuracy gate has logged a skip for the current blocked period.
     * Prevents [rebuildGeoAnchorsIfNeeded] (which runs every GL frame until anchors are created)
     * from logging the same "accuracy too poor" skip on every frame. Reset on successful
     * anchor creation and on manual/auto re-anchor. GL-thread only.
     */
    private var loggedAccuracyGate = false

    /**
     * Wall-clock time (ms) when ARCore Earth most recently entered TRACKING (0 = not tracking).
     * Drives the [GeoAnchorGate] localization timeout. GL-thread only.
     */
    private var earthTrackingSinceMs: Long = 0L

    /**
     * ARCore horizontal accuracy (m) captured when the current anchors were created. Used to decide
     * an auto re-anchor once localization materially improves (see [GeoAnchorGate.shouldReanchorOnImprovement]).
     * Null until anchors are created. GL-thread only.
     */
    private var earthAccuracyAtCreationM: Double? = null

    /** True while holding placement waiting for AR localization — surfaces "Localizing…" status. GL-thread only. */
    @Volatile private var awaitingLocalization = false

    /** Log the "waiting for AR localization" skip once per blocked period (avoids per-frame spam). GL-thread only. */
    private var loggedLocalizationWait = false

    /** Throttle for the per-frame Earth-accuracy improvement check. GL-thread only. */
    private var lastImprovementCheckMs: Long = 0L

    /** ARCore yaw accuracy (°) when the current anchors were created — for heading re-anchoring. GL thread. */
    private var earthYawAccAtCreationDeg: Double? = null

    /** Gate profile last used (STANDARD / RTK) — shown in the debug panel and logs. */
    @Volatile private var currentGateProfile: GeoAnchorGate.Profile = GeoAnchorGate.Profile.STANDARD

    /** External antenna phase centre above the tablet camera (m), from Settings → Receiver → Antenna setup. */
    @Volatile private var arAntennaAboveCameraM: Double = 0.0
    private var loggedAntennaSetup = false

    /** Throttle for the AR_VS_GNSS export log (GL thread). */
    private var lastArVsGnssLogMs: Long = 0L

    /** AR_SETTINGS is logged once per session (then on change), so every export records scale/offset. */
    private var loggedArSettings = false

    /**
     * Volatile snapshot of the ViewModel's distanceFilterM — updated via StateFlow collection
     * on the main thread, read on the GL thread each frame.
     */
    @Volatile private var distanceFilterM: Double? = null

    /**
     * Coordinate IDs hidden by the distance filter on the last frame. Used by [collectGeoAnchors]
     * to log a "distance filter" skip only when the filtered set changes, never every frame.
     * GL-thread only.
     */
    private var lastDistanceSkippedIds: Set<String> = emptySet()

    // AR/model session diagnostics are accumulated in the process-wide ArSessionDiagnostics holder
    // (written to ar-last-session.txt on close, and read by model-integrity.txt). These two fields
    // only hold the last-seen tracking states so we log/record CHANGES rather than every frame.
    private var lastEarthTrackingLogged: String? = null
    private var lastCameraTrackingLogged: String? = null

    /** How the current anchors were placed ("localized"/"timeout"/"no_vps"); null until placed. GL-thread only. */
    @Volatile private var lastPlacedVia: String? = null
    /** VPS (visual positioning) coverage at this site: true/false, null = not checked / unknown / error. */
    @Volatile private var vpsAvailable: Boolean? = null
    private var vpsCheckStarted = false

    /**
     * Asks ARCore once per AR screen whether this site has VPS coverage (Street View–based visual
     * localization). Without it ARCore's position stays at GPS accuracy, so the anchor gate stops waiting
     * for horizontal accuracy and the quality hint says so instead of guessing. Coordinates go only to
     * ARCore (which already uses them for Geospatial) and are never logged.
     */
    private fun startVpsCheck(latDeg: Double, lonDeg: Double) {
        if (vpsCheckStarted) return
        val ses = session ?: return
        if (!latDeg.isFinite() || !lonDeg.isFinite()) return
        vpsCheckStarted = true
        runCatching {
            ses.checkVpsAvailabilityAsync(latDeg, lonDeg) { result ->
                vpsAvailable = when (result) {
                    com.google.ar.core.VpsAvailability.AVAILABLE -> true
                    com.google.ar.core.VpsAvailability.UNAVAILABLE -> false
                    else -> null   // network / auth / internal error: keep the normal gate
                }
                DiagnosticsLogger.i(DIAG, "VPS_AVAILABILITY result=$result")
            }
        }.onFailure {
            vpsCheckStarted = false   // try again on a later frame
            // Retried on later frames: at most one line a minute.
            DiagnosticsLogger.every(DIAG, "ar.frag.vpsStart", 60_000L, 'W', throwable = it) {
                "VPS availability check start failed"
            }
        }
    }
    /** AR-view-quality level for the AR_QUALITY log, debounced (see [LevelHysteresis]). GL-thread only. */
    private val qualityLogHysteresis = LevelHysteresis<ArViewQuality.Level>(QUALITY_LOG_HOLD_MS)

    /** Limits the per-frame status/debug UI post to ~5 per second (see [postDebugPanelUpdate]). */
    private val debugUiPostGate = UiPostGate(DEBUG_UI_POST_INTERVAL_MS)
    private val debugUiHandler = android.os.Handler(android.os.Looper.getMainLooper())


    companion object {
        private const val TAG = "OpenInARFragment"
        /**
         * Shared AR/model diagnostic tag. Reuses [ArFilamentRenderer.DIAG] ("AR_MDL") so the
         * fragment and the Filament renderer log under one greppable tag, and routes through
         * [DiagnosticsLogger] so the entries land in the exported diagnostic report (Logcat-only
         * `android.util.Log` calls never reach the export ZIP).
         */
        private const val DIAG = ArFilamentRenderer.DIAG
        /** Minimum GNSS horizontal accuracy required before creating geospatial anchors. */
        private const val MAX_GNSS_ACCURACY_M = ArGnssGate.MAX_ACCURACY_M
        /**
         * Default uniform scale applied to every GLB model rendered via Filament.
         * 1.0 = real-world metric scale (correct for BIM/survey models exported in metres).
         * Increase only if your models were exported at a non-metric unit (e.g. 0.01 for cm).
         * This value is folded into ModelPose.coordScale and applied inside ArFilamentRenderer
         * so the correction matrix, normalization threshold, and placement all use the
         * correct effective scale.
         */
        private const val MODEL_SCALE = 1f
        /** Near/far clip planes — must match [ArFilamentRenderer] constants. */
        private const val NEAR_CLIP = 0.1f
        /** Id of the temporary test cube point (never stored; not a test-grid key). */
        private const val TEST_CUBE_ID = "ar_test_cube"
        /** The test cube model shipped in assets: a 1 m textured cube standing on its origin. */
        private const val TEST_CUBE_ASSET_NAME = "surreal-test-cube-1m.glb"
        private const val TEST_CUBE_ASSET = "model_images/$TEST_CUBE_ASSET_NAME"
        private const val FAR_CLIP  = 2000f
        /**
         * Minimum milliseconds between any two anchor rebuilds (manual button resets this).
         * Prevents ARCore resource exhaustion when RTK quality steps through SINGLE→DGPS→FLOAT→FIX
         * in rapid succession after switching to an external receiver like the RS2+.
         */
        private const val REANCHOR_COOLDOWN_MS = 30_000L
        /** AR_VS_GNSS export-log cadence. */
        private const val AR_VS_GNSS_LOG_INTERVAL_MS = 5_000L
        private const val DEBUG_UI_POST_INTERVAL_MS = 200L
        private const val QUALITY_LOG_HOLD_MS = 1_500L
        /** Prefix of this screen's logOnChange/every keys; reset when the screen opens. */
        private const val LOG_KEY_PREFIX = "ar.frag."
        /** Test-grid model keys (see [TestAnchorController.gridKey]); excluded from the scene-state log. */
        private const val TEST_GRID_KEY_PREFIX = "test_grid_"
        private const val ANCHOR_WAIT_LOG_KEY = "ar.frag.anchorWait"
        /** GL renderer init retry after an onSurfaceCreated failure (see retryGlRenderersIfDue). */
        private const val GL_RENDERER_RETRY_INTERVAL_MS = 1_000L
        private const val GL_RENDERER_MAX_RETRIES = 5
    }

    /**
     * Altitude mode (see [AltitudePolicy]): AUTO (default) anchors each point at its stored
     * ellipsoidal height when that height is RTK-grade and on terrain otherwise; STORED / TERRAIN
     * force one or the other. Written on the main thread (settings/toolbar); read on the GL thread.
     */
    @Volatile private var altitudeMode: String = AltitudePolicy.AUTO
    @Volatile private var arModelScale: Float = MODEL_SCALE
    @Volatile private var arShowLabels: Boolean = true
    @Volatile private var arShowOffscreenArrows: Boolean = true
    @Volatile private var arDebugToolsEnabled: Boolean = false
    @Volatile private var arShowPlanes: Boolean = false
    @Volatile private var arShowPointCloud: Boolean = false
    /** User toggle (toolbar): hide model parts that are behind real surfaces. Off by default. */
    @Volatile private var arOcclusionEnabled: Boolean = false
    /** True while the session runs ARCore depth (supported AND Occlude on — see [applyDepthMode]). */
    @Volatile private var depthEnabled: Boolean = false
    /** Device supports ARCore depth (decides whether the Occlude toggle is offered). */
    @Volatile private var depthSupported: Boolean = false
    /** Builds the occlusion mesh from ARCore depth images. GL-thread only. */
    private val depthOcclusionSampler = DepthOcclusionSampler()

    /**
     * Edge margin in pixels for off-screen arrow indicators.
     * Computed once in [onSurfaceChanged] (density never changes at runtime).
     */
    @Volatile private var edgeMarginPx: Float = 0f

    // Drives Filament rendering at display refresh rate on the main thread.
    private var choreographerInstance: android.view.Choreographer? = null
    private var choreographerFrameCount = 0L
    // True if the previous Choreographer tick had at least one model pose.
    // Used to ensure one transparent "clear" frame is rendered after models are removed
    // so the Filament TextureView does not retain a stale image of the last rendered pins.
    private var choreoPrevHadModels = false
    private val filamentFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            choreographerInstance?.postFrameCallback(this)
            choreographerFrameCount++
            // Read one consistent snapshot: view matrix, proj matrix, model poses, and visible-keys
            // are all from the SAME GL frame (written atomically after collectGeoAnchors).
            val snapshot = arRenderSnapshot
            val currentPoses    = snapshot?.modelPoses    ?: emptyList()
            val currentVisibleKeys = snapshot?.visibleKeys ?: emptySet()
            // Always pump asset loading / scene management so models load as soon as
            // preload() is called — even before ARCore anchors start tracking or the
            // Filament TextureView surface has been fully created.
            filamentRenderer?.tickAndApplyLoads(currentPoses, currentVisibleKeys, snapshot?.liveTestKeys ?: emptySet())
            filamentRenderer?.updateOcclusion(snapshot?.occlusion)
            filamentRenderer?.updateLighting(snapshot?.light)
            // Keep rendering while any model is visible — a visible model whose anchor briefly stopped
            // tracking has no pose this frame but stays in the scene holding its last transform.
            val hasModels = currentPoses.isNotEmpty() || currentVisibleKeys.isNotEmpty()
            // Skip GPU rendering when no models are present and none were present last frame.
            // When models are just cleared (choreoPrevHadModels=true, hasModels=false), we
            // fall through and render one transparent frame so Filament clears the TextureView.
            if (!hasModels && !choreoPrevHadModels) {
                if ((choreographerFrameCount % 300L) == 0L)
                    DevLog.d(TAG) { "Choreographer#$choreographerFrameCount — modelPoses empty (no anchors tracking or no models)" }
                return
            }
            val vm = snapshot?.viewMatrix ?: return
            val pm = snapshot.projMatrix
            filamentRenderer?.renderFrame(frameTimeNs, vm, pm, hideModels =
                !ArTrackingVisibility.drawGeospatialModels(snapshot.cameraTracking, snapshot.earthTracking))
            // Update only after a successful render so we retry the clear frame if
            // camera matrices were unavailable this tick.
            choreoPrevHadModels = hasModels
        }
    }

    /**
     * Atomic snapshot of the three values the Choreographer needs to drive Filament for one frame.
     * Written as a single `@Volatile` reference on the GL thread after `collectGeoAnchors` so the
     * camera matrix and model poses are always from the same ARCore frame — eliminating the race
     * that occurred when the Choreographer read the three former independent `@Volatile` fields at
     * different moments and could combine a view matrix from frame N+1 with poses from frame N.
     */
    private data class ArRenderSnapshot(
        val viewMatrix: FloatArray,
        val projMatrix: FloatArray,
        val modelPoses: List<ArFilamentRenderer.ModelPose>,
        val visibleKeys: Set<String>,
        /** Test-grid model keys whose anchors still exist (tracking or not) — keep their assets loaded. */
        val liveTestKeys: Set<String> = emptySet(),
        /** False once ARCore camera tracking is lost — the matrices above are stale; hide models. */
        val cameraTracking: Boolean = true,
        /**
         * False while Earth (geospatial) tracking is lost although the camera tracks: anchor poses are
         * stale and labels are gone, so models are hidden too (see [ArTrackingVisibility]).
         */
        val earthTracking: Boolean = true,
        /** Real-world depth occluder for this frame; null when occlusion is off or no depth yet. */
        val occlusion: OcclusionFrame? = null,
        /** Latest ARCore Environmental HDR light estimate (null = none yet / keep current lighting). */
        val light: ArLighting.Estimate? = null
    )
    @Volatile private var arRenderSnapshot: ArRenderSnapshot? = null
    /** GL thread: last light estimate copied off the Frame, refreshed only when ARCore's timestamp changes. */
    private var lastLightEstimate: ArLighting.Estimate? = null
    private var lastLightEstimateTs = Long.MIN_VALUE

    /** GL thread. Copies ARCore's light estimate when it is new; otherwise returns the previous copy. */
    private fun captureLightEstimate(frame: com.google.ar.core.Frame): ArLighting.Estimate? {
        val le = runCatching { frame.lightEstimate }.getOrNull() ?: return lastLightEstimate
        if (le.state != com.google.ar.core.LightEstimate.State.VALID) return lastLightEstimate
        val ts = le.timestamp
        if (ts == lastLightEstimateTs) return lastLightEstimate
        lastLightEstimateTs = ts
        lastLightEstimate = runCatching {
            ArLighting.Estimate(
                valid = true,
                mainLightDirection = le.environmentalHdrMainLightDirection,
                mainLightIntensity = le.environmentalHdrMainLightIntensity,
                ambientSh = le.environmentalHdrAmbientSphericalHarmonics,
            )
        }.getOrNull() ?: lastLightEstimate
        return lastLightEstimate
    }

    /** Terrain mode: snaps pins/models to the ground ARCore has detected. GL thread only. */
    private val groundSnapper = GroundSnapper()

    @Volatile private var arVisibleIdsMirror: Set<String> = emptySet()
    @Volatile private var arVisibilityModeMirror: ArVisibilityMode = ArVisibilityMode.SELECTED
    /** Last visible-model count logged (rendered set change → diagnostics). GL-thread only. */
    private var lastLoggedVisibleCount: Int = -1
    /**
     * The set of coordinate IDs whose models are currently visible in the Filament scene.
     * Written by [collectGeoAnchors] on the GL thread after each frame's visibility pass;
     * read on the same GL thread when building [arRenderSnapshot].
     * Also read by [tickAndApplyLoads] via the snapshot, so this field is only needed
     * as a GL-thread-local staging field — it is never read from another thread.
     */
    private var modelVisibleKeys: Set<String> = emptySet()

    // State flags for error recovery and user experience
    private var hasWarnedLocationOff = false
    private var hasWarnedNoNetwork = false

    // Permission request launchers using modern Activity Result API
    // A permission request (even one the system answers at once after "Don't ask again") pauses and
    // resumes the fragment, and onResume asks again: a denial looped requests every onResume. Each
    // permission is requested at most once per fragment instance; once denied, the status says so and
    // AR waits until the permission is granted (e.g. in system Settings — checked on every onResume).
    private var cameraPermissionRequested = false
    private var locationPermissionRequested = false

    /** Back-off for CameraNotAvailableException (see [recoverCamera]). Main thread only. */
    private val cameraRecovery = CameraRecoveryPolicy()
    private val cameraRecoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Set (GL or main) when a recovery is in progress, so the GL thread hands it to main only once. */
    private val cameraRecoveryActive = java.util.concurrent.atomic.AtomicBoolean(false)

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The result can be delivered after the view is gone — guard binding access.
            if (granted) checkAvailabilityAndInstall()
            else _binding?.textArStatus?.text = getString(R.string.camera_permission_denied)
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) _binding?.textArStatus?.text = getString(R.string.location_permission_needed)
        }


    // Track previous brightness so we can restore it on pause
    private var previousBrightness = -1f
    // Mirrors ViewModel's debugVisible StateFlow; initialised to false to match the ViewModel default.
    // Also read on the GL thread (to decide whether to count planes/points), hence @Volatile.
    @Volatile private var debugOverlayVisible = false

    // Whether the floating toolbar is currently visible.
    private var toolbarVisible = false

    // ---------------------------------------------------------------------------------------------
    // Fragment Lifecycle Methods

    /**
     * Initialize view and AR components
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOpenInArBinding.inflate(inflater, container, false)
        ArSessionDiagnostics.startSession()
        lastEarthTrackingLogged = null
        lastCameraTrackingLogged = null
        loggedArSessionHeader = false
        lastPlacedVia = null
        qualityLogHysteresis.reset()
        debugUiPostGate.reset()
        DiagnosticsLogger.resetThrottles(LOG_KEY_PREFIX)   // each AR screen logs its first states afresh
        DiagnosticsLogger.i(DIAG, "AR screen opened")
        setupGlSurface()
        binding.textArStatus.text = getString(R.string.checking_ar_availability)
        return binding.root
    }

    /**
     * Attach observer for coordinate data and check AR availability
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        testAnchorController = TestAnchorController(requireContext())
        // Guard Filament init: a native/engine init failure (e.g. missing GLES features on an odd
        // device) must not take down the whole AR screen. On failure the renderer stays null and
        // every `filamentRenderer?.` call no-ops, so GLES pins/labels still work — only GLB models
        // are unavailable. Throwable (not Exception) to also catch UnsatisfiedLinkError.
        var createdRenderer: ArFilamentRenderer? = null
        filamentRenderer = try {
            ArFilamentRenderer().also { renderer ->
                createdRenderer = renderer
                renderer.init(binding.filamentSurface)
                // Surface the "geometry far from origin" warning as an in-app snackbar.
                // Fires once per affected model when it is first added to the Filament scene.
                // Uses a set so each model file is only warned about once per session.
                val warnedModels = mutableSetOf<String>()
                renderer.onModelPlacementWarning = { coordId, modelFileName, originToBottomCenterM ->
                    if (warnedModels.add(modelFileName)) {
                        val msg = "\"$modelFileName\": geometry is " +
                            "${"%.0f".format(originToBottomCenterM)} m from its origin — " +
                            "try Bottom Center or Custom Offset placement"
                        _binding?.root?.let { root ->
                            com.google.android.material.snackbar.Snackbar
                                .make(root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .show()
                        }
                        // The renderer already logged MODEL_PLACEMENT_WARN for this model.
                        DiagnosticsLogger.d(DIAG) { "MODEL_PLACEMENT_WARN_UI shown=true coordId=$coordId model=$modelFileName" }
                    }
                }
            }
        } catch (t: Throwable) {
            DiagnosticsLogger.e(DIAG, "AR renderer init failed: 3D models disabled, " +
                "pins and labels still render", t)
            // init() already released what it created; destroy() is idempotent and also covers a
            // failure after init() returned (e.g. in the callback setup above).
            createdRenderer?.destroy()
            null
        }

        // Prepare the test-pin asset and preload its keys once ready.
        testAnchorController.prepareAsset(viewLifecycleOwner.lifecycleScope) { _ ->
            filamentRenderer?.let { renderer ->
                testAnchorController.preloadModelKeys(renderer, viewLifecycleOwner.lifecycleScope)
            }
        }

        checkAvailabilityAndInstall()
        setupModelMove()

        // Back button — always visible, exits AR and returns to previous destination.
        binding.btnArBack.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "AR exit requested by user")
            // findNavController() can throw if the view is mid-teardown; never let the exit button crash.
            runCatching { findNavController().popBackStack() }
                .onFailure { DiagnosticsLogger.w(DIAG, "AR exit navigation failed", it) }
        }

        // Toolbar toggle — show/hide with a slide-in animation from the right edge (UI chrome, not logged).
        binding.btnToolbarToggle.setOnClickListener {
            if (toolbarVisible) hideToolbar() else showToolbar()
        }

        binding.btnSpawnTestPoints.setOnClickListener {
            if (testAnchorController.isActive) {
                DiagnosticsLogger.i(DIAG, "Test grid clear requested by user")
                testAnchorController.requestClear()
                binding.txtTestPoints.text = "Test"
            } else {
                DiagnosticsLogger.i(DIAG, "Test grid spawn requested by user")
                val r = filamentRenderer
                if (r != null) testAnchorController.preloadModelKeys(r, viewLifecycleOwner.lifecycleScope)
                else DiagnosticsLogger.w(DIAG, "Test grid renderer unavailable: pins spawn without 3D models")
                testAnchorController.requestSpawn()
                binding.txtTestPoints.text = "Clear"
            }
        }

        binding.btnTestCube.setOnClickListener {
            if (testCube != null) removeTestCube() else {
                DiagnosticsLogger.i(DIAG, "Test cube placement requested by user")
                testCubeRequested = true
                showMoveMessage("Placing a test cube in front of you…")
            }
        }

        binding.btnToggleDebugOverlay.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "AR debug overlay toggled by user enabled=${!debugOverlayVisible}")
            viewModel.toggleDebug()
        }

        binding.btnTogglePlanes.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "AR planes overlay toggled by user enabled=${!arShowPlanes}")
            viewModel.togglePlanes()
        }

        binding.btnToggleOcclusion.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "AR depth occlusion toggled by user enabled=${!arOcclusionEnabled}")
            viewModel.toggleOcclusion()
        }

        binding.btnTogglePointCloud.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "AR point cloud overlay toggled by user enabled=${!arShowPointCloud}")
            viewModel.togglePointCloud()
        }

        binding.btnReanchor.setOnClickListener {
            rebuildReason = "user"   // logged once on the GL thread when the rebuild happens
            shouldRebuildAnchors = true
            binding.btnReanchor.isEnabled = false
            // Re-enable on a lifecycle-bound coroutine instead of View.postDelayed so the
            // callback is cancelled when the view is destroyed. The old postDelayed dereferenced
            // `binding` (a non-null !! accessor) 2s later and crashed if the user left AR first.
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(2000)
                _binding?.btnReanchor?.isEnabled = true
            }
        }

        // The former distance-filter button now opens the model-visibility sheet (range moves inside it).
        binding.btnDistanceFilter.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "Model visibility sheet opened by user")
            ArModelsBottomSheet.show(this)
        }

        binding.btnAltitudeMode.setOnClickListener {
            // Persist through the ViewModel so the choice survives pause/resume and stays in sync with
            // the Settings screen; the arDisplaySettings collector then applies it (updates the mode,
            // the button text, and triggers a rebuild). Cycles Auto → Stored → Terrain.
            val next = AltitudePolicy.next(altitudeMode)
            DiagnosticsLogger.i(DIAG, "AR altitude mode changed by user $altitudeMode → $next")
            viewModel.setAltitudeMode(next)
        }


        // Observe coordinate + model data from ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.coordsWithModels.collect { items -> lastDbItems = items; setCoordinates(withTestCube(items)) }
            }
        }

        // Observe GNSS fixes from switchboard for current GPS location.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // currentFix (not the fixes replay buffer) goes null on a provider switch and when
                // fixes stop, so AR never anchors/gates against a frozen position.
                fixSwitchboard.currentFix.collect { fix ->
                    currentGnssFix = fix
                    currentGnssFixMonoMs = android.os.SystemClock.elapsedRealtime()
                    if (fix == null) {
                        _binding?.chipArGnssStatus?.let {
                            it.text = "Waiting for GNSS"
                            it.setTextColor(requireContext().getColor(R.color.app_error))
                        }
                        return@collect
                    }
                    logArSessionHeaderOnce()
                    updateArGnssChip(fix)
                    // Only log when the provider changes — fixes arrive at up to 10Hz
                    // and logging every one would flood logcat.
                    if (fix.provider != lastLoggedProvider) {
                        lastLoggedProvider = fix.provider
                        DevLog.d(TAG) { "GNSS provider changed: ${fix.provider}" }
                    }
                }
            }
        }

        // If mock publishing can't start (app not chosen as the mock location app in Developer
        // Options), AR anchors fall back to the phone's own location — tell the user once.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val publisher = runCatching { app.surrealar.SurRealApplication.mockLocationPublisher }.getOrNull()
                    ?: return@repeatOnLifecycle
                var shown = false
                publisher.errorEvents.collect {
                    if (shown) return@collect
                    shown = true
                    android.widget.Toast.makeText(requireContext(),
                        "AR can't use the external receiver: select SurReal AR as the mock location app in Developer Options",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        // Track the selected GNSS source (INTERNAL/EXTERNAL) for AR diagnostics.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.locationSource.collect { selectedLocationSource = it.name }
            }
        }

        // Track the mock-location setting for AR diagnostics (does ARCore/Android see a mocked provider?).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.mockLocationEnabled.collect { mockLocationEnabled = it }
            }
        }

        // Keep local volatile copy of distance filter in sync for the GL thread.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.distanceFilterM.collect { distanceFilterM = it }
            }
        }

        // Toolbar "Models" label reflects the current visibility mode + shown count.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.arModelsSummary.collect { s ->
                    binding.txtDistanceFilter.text = when (s.mode) {
                        ArVisibilityMode.SELECTED -> "${s.shown} shown"
                        ArVisibilityMode.NEARBY   -> "${s.shown} nearby"
                        ArVisibilityMode.ALL      -> "All ${s.total}"
                    }
                }
            }
        }

        // Mirror AR visibility mode + effective visible set for the GL-thread render gate.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.arVisibilityMode.collect { arVisibilityModeMirror = it }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effectiveVisibleIds.collect { arVisibleIdsMirror = it }
            }
        }

        // Sync debug overlay visibility from ViewModel (survives rotation).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.debugVisible.collect { visible ->
                    debugOverlayVisible = visible
                    binding.debugPanel.visibility = if (visible) View.VISIBLE else View.GONE
                    binding.txtDebugOverlay.text = if (visible) "Hide" else "Debug"
                }
            }
        }

        // Sync planes toggle from ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.showPlanes.collect { on ->
                    arShowPlanes = on
                    val tint = if (on) requireContext().getColor(R.color.app_info)
                               else    requireContext().getColor(android.R.color.white)
                    binding.iconTogglePlanes.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }

        // Sync point cloud toggle from ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.showPointCloud.collect { on ->
                    arShowPointCloud = on
                    val tint = if (on) requireContext().getColor(R.color.app_info)
                               else    requireContext().getColor(android.R.color.white)
                    binding.iconTogglePointCloud.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }

        // Sync depth occlusion toggle from ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.occlusionEnabled.collect { on ->
                    arOcclusionEnabled = on
                    applyDepthMode()
                    val tint = if (on) requireContext().getColor(R.color.app_info)
                               else    requireContext().getColor(android.R.color.white)
                    binding.iconToggleOcclusion.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
                    binding.txtToggleOcclusion.text = if (on) "Occlude ✓" else "Occlude"
                }
            }
        }

        // Receiver page antenna setup: how far the external antenna's phase centre is above the camera.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    settingsRepo.gnssReceiverSettings, settingsRepo.externalReceiverProfile,
                ) { receiver, profile -> app.surrealar.settings.model.AntennaSetup.resolve(receiver, profile) }
                    .collect { setup ->
                        if (setup.antennaAboveCameraM != arAntennaAboveCameraM || !loggedAntennaSetup) {
                            loggedAntennaSetup = true
                            DiagnosticsLogger.i(DIAG, "AR_ANTENNA antennaAboveCameraM=${"%.3f".format(setup.antennaAboveCameraM)} " +
                                "cameraBelowReceiverM=${"%.3f".format(setup.cameraBelowReceiverM)} " +
                                "phaseCenterOffsetM=${"%.3f".format(setup.phaseCenterOffsetM)} fromProfile=${setup.offsetFromProfile}")
                        }
                        arAntennaAboveCameraM = setup.antennaAboveCameraM
                    }
            }
        }

        // Apply AR Display settings (altitude mode, model scale, label/arrow visibility).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.arDisplaySettings.collect { ar ->
                    val mode = AltitudePolicy.normalize(ar.altitudeMode)
                    // Test grid follows the same toggle (it used to be terrain-only, so the button
                    // appeared to do nothing when only test pins were on screen).
                    testAnchorController.useTerrain = mode == AltitudePolicy.TERRAIN
                    if (mode != altitudeMode || _binding?.txtAltitudeMode?.text?.toString() != AltitudePolicy.label(mode)) {
                        // Logging only: the first emission just syncs the button label ("initial", not logged).
                        // (loggedArSettings is still false during the first emission; it is set below.)
                        rebuildReason = when {
                            !loggedArSettings -> "initial"
                            mode != altitudeMode -> "altitude_mode"
                            else -> "settings"
                        }
                        if (mode != altitudeMode) shouldRebuildAnchors = true
                        altitudeMode = mode
                        shouldRebuildAnchors = true
                        // Same as the Spawn button: make sure the grid's models are loaded (no-op
                        // for keys already cached) so the respawned pins always get their models.
                        if (testAnchorController.isActive) {
                            filamentRenderer?.let { testAnchorController.preloadModelKeys(it, viewLifecycleOwner.lifecycleScope) }
                        }
                        testAnchorController.requestRespawn()
                        _binding?.txtAltitudeMode?.text = AltitudePolicy.label(mode)
                    }
                    if (!loggedArSettings || ar.modelScale != arModelScale) {
                        loggedArSettings = true
                        // Anything but 1.0 draws every model the wrong size at its coordinates.
                        val msg = "AR_SETTINGS modelScale=${ar.modelScale} trueScale=${ar.modelScale == 1f} " +
                            "altitudeMode=${ar.altitudeMode}"
                        if (ar.modelScale != 1f) DiagnosticsLogger.w(DIAG, msg) else DiagnosticsLogger.i(DIAG, msg)
                    }
                    arModelScale = ar.modelScale
                    arShowLabels = ar.showLabels
                    arShowOffscreenArrows = ar.showOffscreenArrows
                    arDebugToolsEnabled = ar.showArDebugTools
                    _binding?.arDebugToolsGroup?.visibility =
                        if (ar.showArDebugTools) View.VISIBLE else View.GONE
                    // The debug-panel toggle must be reachable whenever the panel can be open — including
                    // "Show Debug Overlay by Default" with Debug Tools off (previously the panel opened
                    // with no way to close it, because the toggle lived inside the hidden tools group).
                    _binding?.debugOverlayToggleGroup?.visibility =
                        if (ar.showArDebugTools || ar.showDebugOverlay) View.VISIBLE else View.GONE
                    if (!ar.showArDebugTools) {
                        // Force debug visuals off when the master switch is disabled
                        arShowPlanes = false
                        arShowPointCloud = false
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Floating toolbar show / hide

    /**
     * Slides the toolbar card in from the right edge and makes it visible.
     * The toggle button icon rotates to signal the open state.
     */
    private fun showToolbar() {
        val toolbar = binding.arFloatingToolbar
        toolbar.translationX = toolbar.width.toFloat().coerceAtLeast(200f)
        toolbar.alpha = 0f
        toolbar.visibility = View.VISIBLE
        toolbar.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        binding.btnToolbarToggle.animate()
            .rotation(90f)
            .setDuration(200)
            .start()
        binding.btnToolbarToggle.contentDescription = "Hide AR tools"
        toolbarVisible = true
    }

    /**
     * Slides the toolbar card back out to the right and hides it when the animation ends.
     */
    private fun hideToolbar() {
        val toolbar = binding.arFloatingToolbar
        toolbar.animate()
            .translationX(toolbar.width.toFloat().coerceAtLeast(200f))
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { toolbar.visibility = View.GONE }
            .start()
        binding.btnToolbarToggle.animate()
            .rotation(0f)
            .setDuration(160)
            .start()
        binding.btnToolbarToggle.contentDescription = "Show AR tools"
        toolbarVisible = false
    }

    // ---------------------------------------------------------------------------------------------
    // Display rotation helper

    /**
     * Returns the current display rotation constant (Surface.ROTATION_*).
     * Called on the main thread; result is stored in [displayRotation] for GL thread use.
     */
    private fun readDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireContext().display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }

    /**
     * Resume AR session and check all prerequisites
     */
    override fun onResume() {
        super.onResume()
        DevLog.d(TAG) { "onResume called" }
        // ARCore Geospatial localizes from Android location: with an external receiver selected,
        // publish its fixes as mock locations while AR is open so anchors follow the RS2+. The
        // publisher only acts when the selected source is External; Internal is unaffected.
        runCatching { app.surrealar.SurRealApplication.mockLocationPublisher.setArSessionActive(true) }
            .onFailure { DiagnosticsLogger.w(DIAG, "Mock location AR auto-enable failed", it) }

        displayRotation = readDisplayRotation()
        DevLog.d(TAG) { "Display rotation: $displayRotation" }
        cameraRecovery.reset()   // returning to AR gets a fresh camera-recovery budget

        if (!checkAndRequestCameraPermission()) {
            DiagnosticsLogger.w(DIAG, "AR session start blocked: camera permission not granted")
            return
        }
        if (!checkAndRequestLocationPermission()) {
            DiagnosticsLogger.w(DIAG, "AR session start blocked: location permission not granted")
            return
        }

        if (session == null) {
            DevLog.d(TAG) { "Creating new AR session" }
            checkAvailabilityAndInstall()
        }

        val locationEnabled = isLocationServicesEnabled()
        val networkAvailable = hasNetwork()
        DevLog.d(TAG) { "Location enabled: $locationEnabled, Network: $networkAvailable" }

        if (!locationEnabled && !hasWarnedLocationOff) {
            hasWarnedLocationOff = true
            DiagnosticsLogger.w(DIAG, "Geospatial unavailable: location services disabled")
        }
        if (!networkAvailable && !hasWarnedNoNetwork) {
            hasWarnedNoNetwork = true
            DiagnosticsLogger.w(DIAG, "Geospatial cannot localize: no network connection")
        }

        // Resume AR session and related components
        try {
            session?.resume()
            // Only a session that actually resumed is ready. On a cold start ARCore's availability can
            // still be UNKNOWN_CHECKING here: the session is then created later and resumed in
            // tryCreateSession() (see resumeLateSession).
            sessionReady = session != null
            binding.glSurfaceViewAr.onResume()

            // Ensure the camera texture name is registered with the session.
            // onSurfaceCreated may have run before the session was created (first launch),
            // in which case the setCameraTextureName call there was a no-op. Calling it here
            // guarantees ARCore receives camera frames from the very first update() call,
            // preventing the "IMU buffer overflow / Last visual features at: 0 ns" condition.
            backgroundRenderer?.let { br ->
                session?.setCameraTextureName(br.textureId)
            }

            // Start Filament render loop (driven by display vsync)
            choreographerInstance = android.view.Choreographer.getInstance()
            choreographerInstance?.postFrameCallback(filamentFrameCallback)
            DiagnosticsLogger.i(DIAG, "AR session resumed: camera active, render loop started")
            binding.textArStatus.text = "AR camera active"

            val window = requireActivity().window
            val lp = window.attributes
            previousBrightness = lp.screenBrightness
            lp.screenBrightness = 1.0f
            window.attributes = lp
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            DevLog.d(TAG) { "Screen brightness set to maximum, wake lock enabled" }
        } catch (e: CameraNotAvailableException) {
            DiagnosticsLogger.e(DIAG, "AR session resume failed: camera unavailable", e)
            binding.textArStatus.text = getString(R.string.camera_unavailable)
            // Pause + bounded retry once the fragment is RESUMED (it is not yet, inside onResume).
            if (cameraRecoveryActive.compareAndSet(false, true)) {
                cameraRecoveryHandler.post { recoverCamera("resume") }
            }
        } catch (e: Exception) {
            DiagnosticsLogger.e(DIAG, "AR session resume failed", e)
            binding.textArStatus.text = "AR error: ${e.message}"
        }
    }

    /**
     * GL thread: Session.update() threw CameraNotAvailableException. Stops further update() calls (the
     * camera is gone, so each would throw again) and hands recovery to the main thread once.
     */
    private fun onCameraUnavailableInFrame() {
        sessionReady = false
        if (cameraRecoveryActive.compareAndSet(false, true)) {
            cameraRecoveryHandler.post { recoverCamera("frame") }
        }
    }

    /**
     * Main thread: pauses the session and schedules a resume after [cameraRecovery]'s back-off
     * (1 s … 16 s, 5 attempts). Out of attempts, it stays paused with a status message until the next
     * onResume. Never spins: frames stop (sessionReady=false) while the camera is unavailable.
     */
    private fun recoverCamera(source: String) {
        val ses = session
        if (ses == null || _binding == null ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            cameraRecoveryActive.set(false)   // onResume/onPause own the session from here
            return
        }
        cameraRecoveryActive.set(true)
        sessionReady = false
        runCatching { ses.pause() }
            .onFailure { DiagnosticsLogger.w(DIAG, "AR session pause for camera recovery failed", it) }
        when (val d = cameraRecovery.onCameraUnavailable(android.os.SystemClock.elapsedRealtime())) {
            is CameraRecoveryPolicy.Decision.Retry -> {
                DiagnosticsLogger.w(DIAG, "Camera unavailable: AR paused, resume retry scheduled " +
                    "delayMs=${d.delayMs} attempt=${d.attempt}/${d.maxAttempts} source=$source")
                setArStatus("${getString(R.string.camera_unavailable)}: retrying in " +
                    "${(d.delayMs + 999) / 1000} s (${d.attempt}/${d.maxAttempts})")
                cameraRecoveryHandler.postDelayed({ attemptCameraResume() }, d.delayMs)
            }
            CameraRecoveryPolicy.Decision.GiveUp -> {
                // Stays active: no frames run and nothing re-posts until onResume resets the budget.
                DiagnosticsLogger.e(DIAG, "Camera unavailable: AR recovery gave up " +
                    "attempts=${cameraRecovery.maxAttempts} source=$source")
                setArStatus("${getString(R.string.camera_unavailable)}: close other camera apps, then reopen AR")
            }
        }
    }

    /** Main thread: one scheduled resume of [recoverCamera]. */
    private fun attemptCameraResume() {
        val ses = session
        if (ses == null || _binding == null ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            cameraRecoveryActive.set(false)
            return
        }
        try {
            ses.resume()
            cameraRecoveryActive.set(false)
            sessionReady = true
            DiagnosticsLogger.i(DIAG, "AR session resumed after camera unavailable")
            setArStatus("AR camera active")
        } catch (e: CameraNotAvailableException) {
            recoverCamera("retry")
        } catch (e: Exception) {
            cameraRecoveryActive.set(false)
            DiagnosticsLogger.e(DIAG, "AR session resume failed during camera recovery", e)
            setArStatus("AR error: ${e.message}")
        }
    }

    /**
     * Called when the device rotates (because MainActivity declares configChanges).
     * The Fragment is NOT recreated — update displayRotation so the GL thread
     * picks up the new orientation on the next onDrawFrame call.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        displayRotation = readDisplayRotation()
        DevLog.d(TAG) { "Config changed — display rotation: $displayRotation" }
    }

    /**
     * Pause AR session and components
     */
    override fun onPause() {
        super.onPause()
        arTrackOrigin = null   // next resume starts a new ar-track.csv session
        // Release the AR auto-enable; publishing continues only if the user's Settings toggle is on.
        runCatching { app.surrealar.SurRealApplication.mockLocationPublisher.setArSessionActive(false) }
            .onFailure { DiagnosticsLogger.w(DIAG, "Mock location AR auto-disable failed", it) }
        choreographerInstance?.removeFrameCallback(filamentFrameCallback)
        choreographerInstance = null
        sessionReady = false
        cameraRecoveryHandler.removeCallbacksAndMessages(null)   // onResume restarts the session itself
        cameraRecoveryActive.set(false)
        binding.glSurfaceViewAr.onPause()
        try { session?.pause() } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "AR session pause failed", e)
        }

        // Reset one-shot warning flags so they re-fire if the user navigates away
        // and back while location/network state has changed.
        hasWarnedLocationOff = false
        hasWarnedNoNetwork   = false

        // Give the window back to the user's Field Display settings (keep awake / max brightness). Only
        // if they haven't loaded: restore what AR found and let the screen sleep, as before.
        try {
            val restored = (activity as? app.surrealar.MainActivity)?.reapplyDisplayBehaviorSettings() == true
            if (!restored) {
                val window = requireActivity().window
                val lp = window.attributes
                lp.screenBrightness = previousBrightness
                window.attributes = lp
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            DevLog.d(TAG) { if (restored) "Screen settings restored from Field Display settings" else "Screen brightness restored to $previousBrightness" }
        } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "AR screen brightness and sleep restore failed", e)
        }
    }

    /**
     * Clean up AR resources when view is destroyed
     */
    override fun onDestroyView() {
        super.onDestroyView()
        // Session-only moves end with the AR screen (saved moves are on the points).
        moveTicker?.cancel(); moveTicker = null
        moveTargetId = null
        moveFrame = null
        sessionMoves.clear()
        testCube = null; testCubeRequested = false
        DiagnosticsLogger.i(DIAG, "AR screen closed " +
            "anchors=${geoAnchors.size} modelPoses=${arRenderSnapshot?.modelPoses?.size ?: 0} items=${geoItems.size}")
        writeArSessionSummary()
        choreographerInstance?.removeFrameCallback(filamentFrameCallback)
        choreographerInstance = null
        choreoPrevHadModels = false
        cameraRecoveryHandler.removeCallbacksAndMessages(null)
        cameraRecoveryActive.set(false)
        filamentRenderer?.destroy()
        filamentRenderer = null
        arRenderSnapshot  = null
        depthOcclusionSampler.reset()
        pinScreenCache = emptyList()
        lastPostedLabels = emptyList()
        lastPostedArrows = emptyList()
        lastModelProgress = null
        hideModelProgressJob?.cancel()
        hideModelProgressJob = null
        debugUiHandler.removeCallbacksAndMessages(null)
        debugUiPostGate.reset()
        // Clean up test anchors via controller
        testAnchorController.cleanup()
        // Clean up geospatial anchors (and terrain resolves in flight)
        clearGeoAnchors()
        geoAnchorsCreated = false
        lastAnchorRtkStatus = null
        lastReanchorTimeMs = 0L
        earthTrackingSinceMs = 0L
        earthAccuracyAtCreationM = null; earthYawAccAtCreationDeg = null
        awaitingLocalization = false
        loggedLocalizationWait = false
        lastPlacedVia = null
        // Clean up AR session. onPause already stopped the GL thread (GLSurfaceView.onPause blocks until
        // it pauses) and paused the session. Close can take a long time, so per the ARCore docs it runs
        // on a background thread, after the field is cleared so nothing here can touch it again.
        val closing = session
        session = null
        sessionReady = false
        if (closing != null) {
            appScope.launch(Dispatchers.Default) {
                try { closing.close() } catch (e: Exception) {
                    DiagnosticsLogger.w(DIAG, "AR session close failed", e)
                }
            }
        }
        _binding = null
    }

    /**
     * Writes a compact summary of the just-ended AR/model session to `ar-last-session.txt`. Unlike
     * the rolling event log, this single file survives event-log rotation, so the export always has
     * a snapshot of the most recent session (counts only — not an event log).
     */
    private fun writeArSessionSummary() {
        if (!ArSessionDiagnostics.hasSession()) return
        ArSessionDiagnostics.endSession()
        DiagnosticsLogger.writeSessionSummary("ar-last-session.txt", ArSessionDiagnostics.buildSummaryText())
    }

    // ---------------------------------------------------------------------------------------------
    // Permission Management & ARCore Installation

    /**
     * Check camera permission and request if needed
     */
    private fun checkAndRequestCameraPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) true else {
            if (!cameraPermissionRequested) {
                cameraPermissionRequested = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                setArStatus(getString(R.string.camera_permission_denied))
            }
            false
        }
    }

    /**
     * Check location permission and request if needed
     */
    private fun checkAndRequestLocationPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            == PackageManager.PERMISSION_GRANTED
        ) true else {
            if (!locationPermissionRequested) {
                locationPermissionRequested = true
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                setArStatus(getString(R.string.location_permission_needed))
            }
            false
        }
    }

    /**
     * Sets the AR status text only if the view still exists. Safe to call from async ARCore
     * availability/install callbacks, which can fire after onDestroyView() has cleared `_binding`.
     */
    private fun setArStatus(text: String) {
        _binding?.textArStatus?.text = text
    }

    /**
     * Create a new ARCore session with required configuration
     */
    private fun tryCreateSession() {
        // Reachable from onViewCreated, onResume, the camera-permission result and the availability
        // poll. A second Session would replace the first without close(), leaking its native memory.
        if (session != null) {
            DevLog.d(TAG) { "tryCreateSession skipped: a session already exists" }
            return
        }
        var created: Session? = null
        try {
            val ses = Session(requireContext())
            created = ses
            session = ses
            configureSession()
            resumeLateSession(ses)
        } catch (e: CameraNotAvailableException) {
            DiagnosticsLogger.e(DIAG, "AR session create failed: camera unavailable", e)
            setArStatus(getString(R.string.camera_unavailable))
            discardSession(created)
        } catch (e: Exception) {
            DiagnosticsLogger.e(DIAG, "AR session create failed", e)
            setArStatus("Session error: ${e.message ?: "Unknown"}")
            discardSession(created)
        }
    }

    /**
     * Closes a session whose setup threw part-way (e.g. Config(ses) or a support query), so it is
     * neither leaked nor left half-configured; the next onResume tries again from scratch.
     */
    private fun discardSession(ses: Session?) {
        if (ses == null) return
        if (session === ses) { session = null; sessionReady = false }
        runCatching { ses.close() }
            .onFailure { DiagnosticsLogger.w(DIAG, "AR session close after failed setup failed", it) }
    }

    /**
     * Resumes [ses] when it was created after onResume already ran — the availability poll on a cold
     * start (UNKNOWN_CHECKING → 200 ms later). onResume only resumes a session that existed then, so
     * without this the camera stayed black (update() threw every frame) until the user left AR and came
     * back. A session created inside onResume is skipped here (the fragment is not RESUMED yet) and
     * resumed by onResume as before.
     */
    private fun resumeLateSession(ses: Session) {
        if (sessionReady || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        try {
            ses.resume()
            sessionReady = true
            DiagnosticsLogger.i(DIAG, "AR session resumed after late creation: camera active")
            setArStatus("AR camera active")
        } catch (e: CameraNotAvailableException) {
            DiagnosticsLogger.e(DIAG, "AR session resume failed: camera unavailable", e)
            setArStatus(getString(R.string.camera_unavailable))
            // RESUMED here (checked above): pause + bounded retry, as for a camera lost mid-session.
            if (cameraRecoveryActive.compareAndSet(false, true)) recoverCamera("late_resume")
            else try { ses.pause() } catch (_: Exception) {}   // best effort after a failed resume
        } catch (e: Exception) {
            DiagnosticsLogger.e(DIAG, "AR session resume failed", e)
            setArStatus("AR error: ${e.message}")
        }
    }

    /**
     * Diagnostics only (no behaviour change): logs every camera config ARCore offers on this device
     * and which one the session is using. The selected config's GPU texture size/crop determines how
     * "zoomed in" the AR camera view looks — the session currently leaves selection to ARCore.
     */
    private fun logCameraConfigs(ses: Session) {
        runCatching {
            val current = ses.cameraConfig
            fun desc(c: com.google.ar.core.CameraConfig) =
                "id=${c.cameraId} img=${c.imageSize.width}x${c.imageSize.height} " +
                "tex=${c.textureSize.width}x${c.textureSize.height} fps=${c.fpsRange.lower}-${c.fpsRange.upper} " +
                "depthSensor=${c.depthSensorUsage} stereo=${c.stereoCameraUsage}"
            val all = ses.getSupportedCameraConfigs(com.google.ar.core.CameraConfigFilter(ses))
            DiagnosticsLogger.i(DIAG, "AR_CAMERA_CONFIG ${desc(current)} supported=${all.size}")
            all.forEachIndexed { i, c -> DiagnosticsLogger.d(DIAG) { "Camera config supported index=$i ${desc(c)}" } }
        }.onFailure { DiagnosticsLogger.w(DIAG, "Camera config listing failed", it) }
    }

    /**
     * Turns ARCore depth on while Occlude is on and off otherwise, on the live session (Google's samples
     * reconfigure a resumed session the same way). Depth-from-motion costs processing every frame and
     * nothing else uses it. No-op until the session is configured or when the device lacks depth.
     */
    private fun applyDepthMode() {
        val ses = session ?: return
        if (!depthSupported) return
        val want = arOcclusionEnabled
        if (want == depthEnabled) return
        try {
            val cfg = ses.config
            cfg.depthMode = if (want) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            ses.configure(cfg)
            backgroundRenderer?.let { ses.setCameraTextureName(it.textureId) }
            depthEnabled = want
            DiagnosticsLogger.i(DIAG, "AR depth ${if (want) "OFF → ON" else "ON → OFF"} reason=occlusionToggle")
        } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "AR depth switch failed", e)
        }
    }

    /**
     * Configure ARCore session with geospatial and other features
     */
    private fun configureSession() {
        val ses = session ?: return
        // Geospatial mode is what places anchors at real-world lat/lon. If it can't be enabled, every
        // geo-anchor silently fails — the #1 cause of "AR doesn't work" reports — so all three outcomes
        // are recorded in the diagnostic export (not just logcat), and the unavailable case is surfaced.
        var geoEnabled = false
        var depthRequested = false
        val config = Config(ses).apply {
            if (ses.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                try {
                    geospatialMode = Config.GeospatialMode.ENABLED
                    geoEnabled = true
                    DiagnosticsLogger.i(DIAG, "Geospatial mode enabled")
                } catch (e: Exception) {
                    DiagnosticsLogger.e(DIAG, "Geospatial mode enable failed: " +
                        "coordinates cannot be anchored in AR", e)
                }
            } else {
                DiagnosticsLogger.w(DIAG, "Geospatial mode not supported on this device: " +
                    "coordinates cannot be anchored in AR")
            }
            // Configure other AR features
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            // Fixed focus: Google's guidance is that it tracks better (ARCore's default); auto focus is for
            // close-up subjects/recording. Field use looks mostly at things metres away.
            focusMode = Config.FocusMode.FIXED
            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            // Depth (hardware ToF/IR sensor when present, otherwise depth-from-motion) only powers the
            // "Occlude" toggle, so it runs only while that is on (applyDepthMode switches it live).
            depthSupported = ses.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (depthSupported && arOcclusionEnabled) {
                depthMode = Config.DepthMode.AUTOMATIC
                depthRequested = true
            } else {
                depthMode = Config.DepthMode.DISABLED
            }
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        }
        try {
            ses.configure(config)
            if (backgroundRenderer != null) ses.setCameraTextureName(backgroundRenderer!!.textureId)
            geospatialAvailable = geoEnabled
            depthEnabled = depthRequested
            // What ARCore actually holds (read back), not what we asked for — merged into one line.
            val effective = runCatching {
                val eff = ses.config
                " effectivePlaneFinding=${eff.planeFindingMode} effectiveFocus=${eff.focusMode} effectiveDepth=${eff.depthMode} " +
                    "effectiveLight=${eff.lightEstimationMode} effectiveGeospatial=${eff.geospatialMode} " +
                    "effectiveInstantPlacement=${eff.instantPlacementMode} effectiveUpdate=${eff.updateMode}"
            }.getOrElse { " effective=unknown error=${it.javaClass.simpleName}" }
            DiagnosticsLogger.i(DIAG, "AR session configured geospatial=$geoEnabled " +
                "depth=$depthRequested depthSupported=$depthSupported focus=FIXED$effective")
            logCameraConfigs(ses)
            // The occlusion toggle is only offered on devices with ARCore depth.
            val offerOcclusion = depthSupported
            _binding?.root?.post {
                _binding?.occlusionGroup?.visibility = if (offerOcclusion) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            DiagnosticsLogger.e(DIAG, "AR session configure failed", e)
            setArStatus("AR config error: ${e.message}")
        }
    }

    /**
     * Handle ARCore installation process
     */
    private fun checkInstallation() {
        try {
            val status =
                ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)
            when (status) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    setArStatus("Requesting ARCore install...")
                }

                ArCoreApk.InstallStatus.INSTALLED -> tryCreateSession()
            }
        } catch (_: UnavailableUserDeclinedInstallationException) {
            DiagnosticsLogger.w(DIAG, "ARCore install declined by user: AR unavailable")
            setArStatus("ARCore install declined")
        } catch (e: Exception) {
            DiagnosticsLogger.e(DIAG, "ARCore install check failed", e)
            setArStatus("Install check failed: ${e.message ?: "Unknown"}")
        }
    }

    /**
     * Check ARCore availability and trigger installation if needed
     */
    private fun checkAvailabilityAndInstall() {
        // Reachable from async callbacks (permission result, availability poll). Bail out if the
        // fragment is detached or the view is gone so requireContext()/binding cannot crash.
        if (!isAdded || _binding == null) return
        val availability = ArCoreApk.getInstance().checkAvailability(requireContext())
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> tryCreateSession()
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> checkInstallation()

            ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                // Poll until availability is determined. Use a lifecycle-bound coroutine instead
                // of View.postDelayed so the retry is cancelled on onDestroyView; the `_binding`
                // guard also stops checkAvailabilityAndInstall() (which touches binding) from
                // running after the view is gone — `isAdded` alone can still be true then.
                if (!availabilityPolling) {
                    availabilityPolling = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(200)
                        availabilityPolling = false
                        if (isAdded && _binding != null) checkAvailabilityAndInstall()
                    }
                }
            }

            else -> {
                DiagnosticsLogger.w(DIAG, "ARCore unavailable: AR cannot start availability=$availability")
                setArStatus(when (availability) {
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "Device not compatible"
                    else -> "AR not supported"
                })
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Geospatial Anchoring & Earth Tracking

    /**
     * Create geospatial anchors for all coordinate points when Earth tracking is stable.
     * [earth] is passed in from [processEarthAndAnchors] to avoid a redundant access
     * to the non-volatile [session] field from the GL thread.
     */
    private fun rebuildGeoAnchorsIfNeeded(earth: Earth) {
        if (geoAnchorsCreated || geoItems.isEmpty()) return
        if (earth.trackingState != TrackingState.TRACKING) return

        // Use GNSS fix from switchboard for positioning instead of ARCore's camera pose
        val gnssFix = currentGnssFix
        if (gnssFix == null) {
            // Not logging here — this fires every frame until a fix arrives;
            // the debug panel shows "⚠️ BLOCKER: currentGnssFix is null" instead.
            return
        }

        // GNSS gate (ArGnssGate): no satellite solution never anchors; otherwise the shared accuracy
        // (receiver 68 % radius, else DOP × UERE by accuracy class) must be within MAX_GNSS_ACCURACY_M.
        val accuracyM: Double? = gnssGateAccuracyM(gnssFix)
        // Normal waiting states (I, not W). This runs every GL frame until anchors exist, so the wait
        // reason is logged only when it changes and holds 3 s; the key is reset when the gate passes.
        if (!ArGnssGate.hasSolution(gnssFix.rtkStatus)) {
            loggedAccuracyGate = true
            DiagnosticsLogger.logOnChange(DIAG, ANCHOR_WAIT_LOG_KEY, "gnss_no_solution", stableMs = 3_000L) {
                "Anchors waiting reason=gnss_no_solution rtk=${gnssFix.rtkStatus} items=${geoItems.size}"
            }
            return
        }
        if (accuracyM != null && accuracyM > MAX_GNSS_ACCURACY_M) {
            loggedAccuracyGate = true
            DiagnosticsLogger.logOnChange(DIAG, ANCHOR_WAIT_LOG_KEY, "gnss_accuracy", stableMs = 3_000L) {
                "Anchors waiting reason=gnss_accuracy " +
                    "accuracyM=${"%.1f".format(accuracyM)} maxM=$MAX_GNSS_ACCURACY_M items=${geoItems.size}"
            }
            return
        }
        loggedAccuracyGate = false

        // ── ARCore Earth localization gate ──────────────────────────────────────────────────────
        // The GNSS gate above validates the RECEIVER, not ARCore's visual localization. TrackingState
        // == TRACKING alone can still be tens of metres off right after Earth first tracks, and
        // committing anchors then maps every coordinate to ≈ the device origin (all models on the
        // user). Wait until ARCore's own cameraGeospatialPose accuracy is good enough, or place after
        // a timeout so models still appear where VPS is unavailable.
        if (earthTrackingSinceMs == 0L) earthTrackingSinceMs = System.currentTimeMillis()
        val camAccPose = runCatching { earth.cameraGeospatialPose }.getOrNull()
        val arHAcc = camAccPose?.horizontalAccuracy ?: Double.POSITIVE_INFINITY
        val arYawAcc = camAccPose?.orientationYawAccuracy ?: Double.POSITIVE_INFINITY
        val trackingElapsed = System.currentTimeMillis() - earthTrackingSinceMs
        // RTK receiver → position is no longer the limit, heading is: demand ≤5° yaw (and wait longer).
        val profile = GeoAnchorGate.profileFor(
            isExternal = gnssFix.provider != io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL,
            rtkFixOrFloat = gnssFix.rtkStatus == io.github.pennstatecs.geokit.gnss.model.RtkStatus.FIX ||
                gnssFix.rtkStatus == io.github.pennstatecs.geokit.gnss.model.RtkStatus.FLOAT,
        )
        if (profile != currentGateProfile) currentGateProfile = profile
        // The profile flaps with RTK FIX/FLOAT ↔ SINGLE: log it only once it holds 5 s.
        DiagnosticsLogger.logOnChange(DIAG, "ar.frag.gateProfile", profile, stableMs = 5_000L) {
            "Anchor gate profile changed profile=$profile " +
                "maxHAccM=${profile.maxHorizontalM} maxYawDeg=${profile.maxYawDeg} timeoutMs=${profile.timeoutMs} " +
                "fixProvider=${gnssFix.provider} rtk=${gnssFix.rtkStatus}"
        }
        startVpsCheck(gnssFix.latDeg, gnssFix.lonDeg)
        val gate = GeoAnchorGate.decide(arHAcc, arYawAcc, trackingElapsed, profile, vpsAvailable)
        if (gate == GeoAnchorGate.Decision.WAIT) {
            awaitingLocalization = true
            loggedLocalizationWait = true
            DiagnosticsLogger.logOnChange(DIAG, ANCHOR_WAIT_LOG_KEY, "ar_localizing", stableMs = 3_000L) {
                "Anchors waiting reason=ar_localizing profile=$profile " +
                    "arHAccM=${"%.1f".format(arHAcc)} maxHAccM=${profile.maxHorizontalM} " +
                    "arYawDeg=${"%.1f".format(arYawAcc)} maxYawDeg=${profile.maxYawDeg} " +
                    "elapsedMs=$trackingElapsed timeoutMs=${profile.timeoutMs} items=${geoItems.size}"
            }
            return
        }
        awaitingLocalization = false
        loggedLocalizationWait = false
        DiagnosticsLogger.resetThrottles(ANCHOR_WAIT_LOG_KEY)   // the next blocked period logs again
        // Remember the accuracy we committed at, so a later improvement can trigger a re-anchor.
        earthAccuracyAtCreationM = arHAcc.takeIf { it.isFinite() }
        earthYawAccAtCreationDeg = arYawAcc.takeIf { it.isFinite() }
        // Expected sideways error at the farthest model right now (h + d·tan yaw) — the number that
        // says whether this placement can be trusted at range.
        // Every anchored point (pins too — a pin placed wrong is as misleading as a model).
        val farthestM = geoItems.maxOfOrNull { Geodesic.distanceM(gnssFix.latDeg, gnssFix.lonDeg, it.lat, it.lng) }
        val expErr = farthestM?.let { ArPlacementError.expectedErrorM(arHAcc, arYawAcc, it) }
        DiagnosticsLogger.i(DIAG, "Anchor gate passed decision=$gate profile=$profile " +
            "arHAccM=${"%.2f".format(arHAcc)} arYawDeg=${"%.1f".format(arYawAcc)} elapsedMs=$trackingElapsed " +
            "farthestPointM=${farthestM?.let { "%.1f".format(it) } ?: "unknown"} " +
            "expectedErrAtFarthestM=${expErr?.let { "%.2f".format(it) } ?: "unknown"}")
        val placedVia = when (gate) {
            GeoAnchorGate.Decision.PLACE_TIMEOUT -> "timeout"
            GeoAnchorGate.Decision.PLACE_NO_VPS -> "no_vps"
            else -> "localized"
        }

        // ── Diagnostics: the exact placement INPUTS (fix + Earth pose + mock/source state) ──────────
        // Lets a future export distinguish a good geospatial estimate from a low-confidence timeout,
        // and see which location source (incl. mock injection) was feeding ARCore. Missing accuracy is
        // logged as "unknown" — never 0.0. Runs once per (re)anchor, not per frame.
        val anchorMode = altitudeMode
        val replacedPrevious = geoAnchors.isNotEmpty() || pendingTerrainAnchors.isNotEmpty()
        val fixAgeMs = System.currentTimeMillis() - gnssFix.timeUtc.toEpochMilli()
        val earthVAcc = camAccPose?.verticalAccuracy?.takeIf { it.isFinite() }
        ArSessionDiagnostics.apply {
            arSelectedSource = selectedLocationSource
            arCurrentFixProvider = gnssFix.provider.name
            arMockEnabledDuringAr = mockLocationEnabled
            arMockInjectionActiveDuringAr = MockInjectionStatus.providerActive
            arMockAppApproved = isMockLocationAppApproved()
            arLastMockFixAgeAtAnchorMs = MockInjectionStatus.lastSourceFixAgeMs()
            arEarthTrackingAtAnchor = earth.trackingState.name
            arHAccAtAnchorM = arHAcc.takeIf { it.isFinite() }
            arVAccAtAnchorM = earthVAcc
            arYawAccAtAnchorDeg = arYawAcc.takeIf { it.isFinite() }
            if (placedVia != "localized") anchorsViaTimeout += 1   // timeout or no-VPS: low confidence
            modelTransformSource = "anchor-driven"
        }
        DiagnosticsLogger.i(DIAG, "ANCHOR_PLACEMENT_INPUT anchorMode=$anchorMode placementReason=$placedVia " +
            "replacedPrevious=$replacedPrevious items=${geoItems.size} " +
            "selectedSource=${selectedLocationSource ?: "unknown"} " +
            "fixProvider=${gnssFix.provider} fixAgeMs=$fixAgeMs " +
            // No coordinates or absolute heights in the diagnostic log (project rule); accuracies only.
            "fixHAccM=${gnssFix.hAccM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "fixVAccM=${gnssFix.vAccM?.let { "%.2f".format(it) } ?: "unknown"} rtk=${gnssFix.rtkStatus} " +
            "gnssGateAccM=${accuracyM?.let { "%.1f".format(it) } ?: "unknown"} " +
            "mockEnabled=${mockLocationEnabled?.toString() ?: "unknown"} " +
            "mockInjectionActive=${MockInjectionStatus.providerActive} " +
            "lastMockFixProvider=${MockInjectionStatus.lastSourceFixProvider ?: "unknown"} " +
            "lastMockFixAgeMs=${MockInjectionStatus.lastSourceFixAgeMs()?.toString() ?: "unknown"} " +
            "earthHAccM=${arHAcc.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "unknown"} " +
            "earthVAccM=${earthVAcc?.let { "%.2f".format(it) } ?: "unknown"} " +
            "earthYawAccDeg=${arYawAcc.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "unknown"} " +
            "earthTracking=${earth.trackingState} cameraTracking=${lastCameraTrackingLogged ?: "unknown"} " +
            "transformSource=anchor-driven")
        // Per-coordinate placement inputs: distance/bearing from the CURRENT fix to each target
        // (for display/diagnosis only — these never feed the model transform).
        // Per item: debug builds only (computed only then).
        if (DiagnosticsLogger.debugEnabled) for (item in geoItems) {
            val c = item.coordWithModel.coordinate
            DiagnosticsLogger.d(DIAG) {
                val dM  = Geodesic.distanceM(gnssFix.latDeg, gnssFix.lonDeg, c.latitude, c.longitude)
                val brg = Geodesic.initialBearingDeg(gnssFix.latDeg, gnssFix.lonDeg, c.latitude, c.longitude)
                "Anchor input coordId=${c.id} " +
                    "hasAltitude=${c.altitude.isFinite()} " +
                    "distFromFixM=${"%.1f".format(dM)} bearingDeg=${"%.0f".format(brg)} " +
                    "hasModel=${item.modelFilePath != null}"
            }
        }

        // Clean up existing anchors (and terrain resolves in flight)
        clearGeoAnchors()

        // Use GNSS ellipsoidal altitude as fallback for coordinates with no recorded altitude.
        // altEllipsoidalM (WGS84 HAE) is required because ARCore's createAnchor() expects it.
        // If ellipsoidal alt is unavailable, derive it from MSL + geoid separation.
        // Never use raw altMslM alone — omitting geoid separation causes ±15–85m errors.
        val fallbackAlt = fixEllipsoidalAltM(gnssFix) ?: 0.0

        // Record the RTK status at the time of anchor creation for auto re-anchor logic.
        lastAnchorRtkStatus = gnssFix.rtkStatus

        // Create new anchors for each coordinate — snapshot geoItems (volatile read) so the
        // main thread can safely call setCoordinates() concurrently without a CME.
        val items = geoItems

        val earthStateName = earth.earthState?.name ?: "unknown"
        // Per point (AltitudePolicy): stored ellipsoidal height when it is trustworthy (RTK-grade), else
        // terrain. Terrain anchors (resolveAnchorOnTerrain at 0 m above terrain) start PAUSED and turn
        // TRACKING once ARCore has the terrain elevation; they are then snapped to the detected ground.
        val mode = altitudeMode
        var failed = 0
        var stored = 0
        var terrain = 0
        for (item in items) {
            val c = item.coordWithModel.coordinate
            val choice = AltitudePolicy.choose(mode, item.alt, c.verticalAccuracyM, c.rtkStatus)
            try {
                if (choice.onTerrain) {
                    // Async resolve (the sync call is deprecated); polled in pollTerrainAnchors().
                    val future = earth.resolveAnchorOnTerrainAsync(item.lat, item.lng, 0.0, 0f, 0f, 0f, 1f, null)
                    pendingTerrainAnchors.add(PendingTerrainAnchor(future, item.coordWithModel))
                } else {
                    val anchor = earth.createAnchor(item.lat, item.lng, item.alt ?: fallbackAlt, 0f, 0f, 0f, 1f)
                    geoAnchors.add(AnchorEntry(anchor, item.coordWithModel))
                }
                if (choice.onTerrain) terrain++ else stored++
                // Per point (debug builds): which height source it got and why — no absolute heights.
                DiagnosticsLogger.d(DIAG) { "Anchor height chosen coordinateId=${c.id} mode=$mode " +
                    "choice=$choice hasStoredAlt=${item.alt != null} " +
                    "vAccM=${c.verticalAccuracyM?.let { "%.2f".format(it) } ?: "unknown"} rtk=${c.rtkStatus ?: "unknown"}" +
                    (if (!choice.onTerrain && item.alt == null) " heightSource=userFix" else "") }
            } catch (e: Exception) {
                failed++
                if (item.coordWithModel.modelFilePath != null)
                    ArSessionDiagnostics.setStatus(c.id, ArSessionDiagnostics.ModelStatus.FAILED, "anchor_failed")
                DiagnosticsLogger.w(DIAG, "Anchor creation failed coordinateId=${c.id} mode=$mode " +
                    "choice=$choice", e)
            }
        }
        DiagnosticsLogger.i(DIAG, "Anchors created count=${geoAnchors.size + pendingTerrainAnchors.size} " +
            "resolving=${pendingTerrainAnchors.size} failed=$failed mode=$mode " +
            "stored=$stored terrain=$terrain transformSource=anchor-driven replacedPrevious=$replacedPrevious " +
            "placedVia=$placedVia trackingState=${earth.trackingState} earthState=$earthStateName " +
            "gnssAccM=${accuracyM?.let { "%.1f".format(it) } ?: "unknown"} " +
            "arHAccM=${arHAcc.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
            "arYawDeg=${arYawAcc.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"}" +
            (if (terrain > 0) " terrainPending=true" else ""))
        recordAnchorOutcome(items.size, geoAnchors.size + pendingTerrainAnchors.size, failed)

        lastPlacedVia = placedVia
        geoAnchorsCreated = true
    }

    /** Records anchor-build tallies into the session diagnostics (GL thread). */
    private fun recordAnchorOutcome(attempted: Int, created: Int, failed: Int) {
        ArSessionDiagnostics.anchorsAttempted += attempted
        ArSessionDiagnostics.anchorsCreated = maxOf(ArSessionDiagnostics.anchorsCreated, created)
        ArSessionDiagnostics.anchorFailures += failed
    }

    /**
     * Whether this app is the OS-selected mock-location app (AppOps `OPSTR_MOCK_LOCATION` == ALLOWED).
     * Returns null when the value cannot be determined. This is the app-level signal for "could ARCore
     * be reading a mocked Android location" — distinct from the in-app mock-enabled setting.
     */
    private fun isMockLocationAppApproved(): Boolean? = try {
        val ctx = requireContext()
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            aom.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(), ctx.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { null }

    /**
     * Logs one AR session header (source + fix + mock + geospatial state) the first time a fix arrives
     * after the AR screen opens. Answers, from a future export: what location source was active, was
     * mock location enabled/approved/injecting, and was ARCore geospatial available. Missing values are
     * logged as "unknown" — never 0.0. Also seeds the AR/Mock export summary. Main thread only.
     */
    private fun logArSessionHeaderOnce() {
        if (loggedArSessionHeader) return
        loggedArSessionHeader = true
        val fix = currentGnssFix
        val fixAgeMs = fix?.let { System.currentTimeMillis() - it.timeUtc.toEpochMilli() }
        val mockApproved = isMockLocationAppApproved()
        ArSessionDiagnostics.arSelectedSource = selectedLocationSource
        ArSessionDiagnostics.arCurrentFixProvider = fix?.provider?.name
        ArSessionDiagnostics.arMockEnabledDuringAr = mockLocationEnabled
        ArSessionDiagnostics.arMockInjectionActiveDuringAr = MockInjectionStatus.providerActive
        ArSessionDiagnostics.arMockAppApproved = mockApproved
        DiagnosticsLogger.i(DIAG, "AR_SESSION_HEADER " +
            "selectedSource=${selectedLocationSource ?: "unknown"} " +
            "activeFixProvider=${fix?.provider?.name ?: "none"} " +
            "fixAgeMs=${fixAgeMs?.toString() ?: "unknown"} " +
            "fixHAccM=${fix?.hAccM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "fixVAccM=${fix?.vAccM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "rtk=${fix?.rtkStatus?.name ?: "unknown"} " +
            "mockLocationEnabled=${mockLocationEnabled?.toString() ?: "unknown"} " +
            "mockInjectionActive=${MockInjectionStatus.providerActive} " +
            "mockAppApproved=${mockApproved?.toString() ?: "unknown"} " +
            "lastMockInjectionAgeMs=${MockInjectionStatus.lastInjectionAgeMs()?.toString() ?: "unknown"} " +
            "sdk=${Build.VERSION.SDK_INT} geospatialEnabled=$geospatialAvailable " +
            "earthTracking=${lastEarthTrackingLogged ?: "unknown"}")
        viewModel.logArVisibilityState("ar_start")
    }


    /**
     * Check if device location services are enabled
     */
    private fun isLocationServicesEnabled(): Boolean {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Check if device has internet connectivity for geospatial localization
     */
    private fun hasNetwork(): Boolean {
        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


    // ---------------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        // BackgroundRenderer creates and owns the OES camera texture.
        // Wrap in try-catch: a shader compilation failure here would leave backgroundRenderer
        // null, causing onDrawFrame to return before setCameraTextureName() is called →
        // ARCore never receives camera frames → persistent "Last visual features at: 0 ns".
        // New surface (or context): fresh retry budget.
        glRendererInitFailures = 0
        glRendererInitGaveUp = false
        glRendererInitLastAttemptMs = android.os.SystemClock.elapsedRealtime()
        // A new surface means a new GL context: objects from the old one are gone with it. Clear them so
        // createGlRenderers builds the whole set and a failure never leaves a dead-context renderer.
        backgroundRenderer = null
        cubeRenderer = null
        planeVisualizer = null
        pointCloudRenderer = null
        // Whatever failed stays null; onDrawFrame retries just the missing ones (bounded, see
        // retryGlRenderersIfDue).
        createGlRenderers()

        // Pre-register the camera texture name with the session so that ARCore can start
        // delivering camera frames immediately without waiting for the first onDrawFrame call.
        // If the session is not created yet (first launch), onDrawFrame will set it anyway.
        backgroundRenderer?.let { session?.setCameraTextureName(it.textureId) }
    }

    // GL renderer init retry (GL thread only). onSurfaceCreated is attempt 1; onDrawFrame retries at most
    // every GL_RENDERER_RETRY_INTERVAL_MS, up to GL_RENDERER_MAX_RETRIES more times, then gives up.
    private var glRendererInitFailures = 0
    private var glRendererInitGaveUp = false
    private var glRendererInitLastAttemptMs = 0L

    /** True once every GLES renderer exists (GL thread). */
    private fun glRenderersComplete(): Boolean =
        backgroundRenderer != null && cubeRenderer != null && planeVisualizer != null && pointCloudRenderer != null

    /**
     * Creates the GLES renderers that don't exist yet (GL thread), each independently, so a later one
     * failing neither discards nor re-creates (leaks) one that succeeded. Returns false, logging the
     * failure, while any is still missing.
     */
    private fun createGlRenderers(): Boolean {
        var failure: Exception? = null
        fun <T : Any> make(existing: T?, create: () -> T): T? = existing ?: try {
            create()
        } catch (e: Exception) {
            if (failure == null) failure = e
            null
        }
        backgroundRenderer   = make(backgroundRenderer) { BackgroundRenderer() }
        cubeRenderer         = make(cubeRenderer) { SimpleObjectRenderer() }
        planeVisualizer      = make(planeVisualizer) { PlaneVisualizer() }
        pointCloudRenderer   = make(pointCloudRenderer) { PointCloudRenderer() }
        val e = failure ?: return true
        val missing = listOfNotNull(
            "background".takeIf { backgroundRenderer == null }, "pins".takeIf { cubeRenderer == null },
            "planes".takeIf { planeVisualizer == null }, "pointCloud".takeIf { pointCloudRenderer == null },
        )
        val impact = if (backgroundRenderer == null) "camera view stays black until a retry succeeds"
            else "camera view works, missing overlays are not drawn"
        glRendererInitFailures++
        if (glRendererInitFailures == 1) {
            DiagnosticsLogger.e(DIAG, "AR camera renderer init failed (will retry): $impact missing=$missing", e)
        } else if (glRendererInitFailures > GL_RENDERER_MAX_RETRIES) {
            glRendererInitGaveUp = true
            DiagnosticsLogger.e(DIAG, "AR camera renderer init failed: giving up, $impact " +
                "missing=$missing attempts=$glRendererInitFailures", e)
        }
        return false
    }

    /**
     * onDrawFrame side of the retry: while any GLES renderer is missing (its constructor threw), re-runs
     * [createGlRenderers] for just the missing ones when due. The GL context is current here. Returns
     * true once all renderers exist.
     */
    private fun retryGlRenderersIfDue(): Boolean {
        if (glRendererInitGaveUp) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - glRendererInitLastAttemptMs < GL_RENDERER_RETRY_INTERVAL_MS) return false
        glRendererInitLastAttemptMs = now
        if (!createGlRenderers()) return false
        DiagnosticsLogger.i(DIAG, "AR camera renderer init recovered after ${glRendererInitFailures + 1} attempts")
        glRendererInitFailures = 0
        return true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        edgeMarginPx = 40f * resources.displayMetrics.density
        try {
            session?.setDisplayGeometry(displayRotation, width, height)
        } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "AR display geometry update failed widthPx=$width heightPx=$height rotation=$displayRotation", e)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Renderer init failed in onSurfaceCreated: retry here (bounded) instead of staying black forever.
        // Only the camera background is required to draw; the overlay renderers are null-safe below.
        if (!glRenderersComplete()) retryGlRenderersIfDue()
        if (backgroundRenderer == null) return

        val s = session ?: return
        if (!sessionReady) return
        try {
            val texId = backgroundRenderer?.textureId ?: return
            s.setCameraTextureName(texId)
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                s.setDisplayGeometry(displayRotation, surfaceWidth, surfaceHeight)
            }

            val frame  = s.update()
            val camera = frame.camera

            // Camera tracking-state CHANGE detection (recorded/logged once per change, not per frame).
            val camTracking = camera.trackingState.name
            if (camTracking != lastCameraTrackingLogged) {
                lastCameraTrackingLogged = camTracking
                ArSessionDiagnostics.lastCameraTrackingState = camTracking
            }
            // The log line waits until the state holds 2 s (tracking flickers when the view is covered).
            DiagnosticsLogger.logOnChange(DIAG, "ar.frag.cameraState", camTracking, stableMs = 2_000L) {
                val reasonName = runCatching { camera.trackingFailureReason?.name }.getOrNull()
                "Camera tracking changed trackingState=$camTracking" + (reasonName?.let { " failureReason=$it" } ?: "")
            }

            // 1) Camera background
            backgroundRenderer?.draw(frame)

            // 2) Earth / anchor management
            val earth       = s.earth
            if (testCubeRequested) { testCubeRequested = false; placeTestCube(frame, camera, earth) }
            val earthStatus = processEarthAndAnchors(earth)

            // Debug builds: ARCore local camera pose next to the GNSS fix, for RTK alignment work.
            if (app.surrealar.BuildConfig.DEBUG) {
                runCatching { recordArTrack(frame, camera, earth) }
                    .onFailure { DiagnosticsLogger.every(DIAG, "ar_track.error", 60_000L, 'W') { "AR track row write failed error=${it.javaClass.simpleName}" } }
            }

            // 3) 3-D rendering (only when camera is tracking)
            var planeCount = 0
            var pointCount = 0
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getViewMatrix(view, 0)
                camera.getProjectionMatrix(proj, 0, NEAR_CLIP, FAR_CLIP)
                Matrix.multiplyMM(vp, 0, proj, 0, view, 0)


                // Counts used to come only from drawing the overlays, so the debug panel read
                // "Planes=0 Points=0" while tracking whenever the overlays were off. Count without
                // drawing while the panel is open.
                pointCount = when {
                    arDebugToolsEnabled && arShowPointCloud -> pointCloudRenderer?.draw(frame, vp) ?: 0
                    debugOverlayVisible -> PointCloudRenderer.count(frame)
                    else -> 0
                }
                planeCount = when {
                    arDebugToolsEnabled && arShowPlanes -> camera.pose.let { cp ->
                        planeVisualizer?.drawAllPlanes(s, vp, cp.tx(), cp.ty(), cp.tz()) ?: 0
                    }
                    debugOverlayVisible -> PlaneVisualizer.count(s)
                    else -> 0
                }

                val margin        = edgeMarginPx
                val pinPositions  = mutableListOf<PinScreenEntry>()
                val labelEntries  = mutableListOf<CoordinateLabelOverlay.LabelEntry>()
                val arrowEntries  = mutableListOf<OffScreenPinIndicatorOverlay.ArrowEntry>()
                val newModelPoses = mutableListOf<ArFilamentRenderer.ModelPose>()

                // Detected ground/floor for Terrain mode (snaps content to the surface ARCore sees).
                if (geoAnchors.any { it.onTerrain } || pendingTerrainAnchors.isNotEmpty() ||
                    altitudeMode == AltitudePolicy.TERRAIN) groundSnapper.update(s, camera.pose)
                else groundSnapper.reset()

                frameMoveAnchor = null
                // Without Earth tracking collectGeoAnchors draws nothing (stale geospatial poses); the
                // test grid (also Earth anchors) follows suit, and models are hidden via the snapshot.
                val earthTracking = earth?.trackingState == TrackingState.TRACKING
                collectGeoAnchors(earth, vp, margin, pinPositions, labelEntries, arrowEntries, newModelPoses)
                moveFrame = if (moveTargetId != null) moveFrameFor(camera) else null
                if (earthTracking) testAnchorController.render(vp, model, modelScaled, mvp, cubeRenderer,
                    surfaceWidth, surfaceHeight, margin, labelEntries, arrowEntries, newModelPoses,
                    groundSnapper)

                pinScreenCache = pinPositions
                // Depth occlusion mesh (world space) from this frame's depth image, when enabled.
                val occlusion = if (arOcclusionEnabled && depthEnabled) {
                    depthOcclusionSampler.update(frame, view, proj)
                } else {
                    depthOcclusionSampler.reset()
                    null
                }
                // Publish a single atomic snapshot so the Choreographer always reads a camera matrix
                // and model poses from the SAME ARCore frame — see ArRenderSnapshot kdoc.
                arRenderSnapshot = ArRenderSnapshot(
                    viewMatrix  = view.copyOf(),
                    projMatrix  = proj.copyOf(),
                    modelPoses  = newModelPoses,
                    visibleKeys = modelVisibleKeys,
                    liveTestKeys = testAnchorController.liveModelKeys(),
                    earthTracking = earthTracking,
                    occlusion   = occlusion,
                    light       = captureLightEstimate(frame)
                )
                postOverlayUpdates(labelEntries, arrowEntries)
            } else {
                // Camera tracking lost: there is no valid view matrix this frame. Previously the last
                // snapshot kept being rendered by the Choreographer, so 3-D models stayed frozen on
                // screen at the old camera pose (no longer world-locked) while the camera moved, and
                // labels/arrows/tap targets stayed at stale screen positions. Flag the snapshot so
                // Filament draws a transparent frame instead (models stay cached and in the scene —
                // nothing is removed or reloaded) and clear the 2-D overlays until tracking resumes.
                pinScreenCache = emptyList()
                arRenderSnapshot?.let { prev ->
                    if (prev.cameraTracking) arRenderSnapshot = prev.copy(cameraTracking = false)
                }
                postOverlayUpdates(emptyList(), emptyList())
            }

            // 4) Status bar, debug panel, model progress chip (posted to main thread)
            val featuresCounted = camera.trackingState == TrackingState.TRACKING &&
                (debugOverlayVisible || (arDebugToolsEnabled && (arShowPointCloud || arShowPlanes)))
            // Always read (a cheap enum getter): the AR quality hint uses it, not just the debug panel.
            val camFailure = runCatching { camera.trackingFailureReason?.name }.getOrNull()
            val optics = if (debugOverlayVisible) computeOptics(camera) else null
            postDebugPanelUpdate(earth, earthStatus, planeCount, pointCount, featuresCounted, camFailure, optics)

        } catch (_: CameraNotAvailableException) {
            // The camera was taken (e.g. by another app): stop calling update() and let the main thread
            // pause and retry with back-off (recoverCamera) instead of throwing every frame.
            DiagnosticsLogger.every(DIAG, "ar.frag.draw.cameraUnavailable", 30_000L, 'W') {
                "Camera unavailable: AR frames not updating, recovery scheduled"
            }
            onCameraUnavailableInFrame()
        } catch (e: Exception) {
            DiagnosticsLogger.every(DIAG, "ar.frag.draw.error", 10_000L, 'E', throwable = e) {
                "AR frame update failed"
            }
        }
    }

    // ── onDrawFrame helpers (all called from the GL thread) ──────────────────

    /**
     * Handles a pending re-anchor request then processes earth tracking state.
     * Returns a short human-readable earth status string for the debug panel.
     */
    private fun processEarthAndAnchors(earth: Earth?): String {
        pollTerrainAnchors()
        // Process manual re-anchor request from main thread BEFORE rebuilding.
        if (shouldRebuildAnchors) {
            shouldRebuildAnchors = false
            val reason = rebuildReason
            val hadAnchors = geoAnchors.size + pendingTerrainAnchors.size
            // Logged once here (GL thread). "initial" = first settings emission syncing the button: debug only.
            if (reason == "initial") DiagnosticsLogger.d(DIAG) { "Anchors re-anchor requested reason=initial anchors=$hadAnchors" }
            else DiagnosticsLogger.i(DIAG, "Anchors re-anchor requested: clearing for rebuild reason=$reason anchors=$hadAnchors")
            clearGeoAnchors()
            geoAnchorsCreated = false
            lastAnchorRtkStatus = null
            lastReanchorTimeMs = System.currentTimeMillis()
            loggedAccuracyGate = false
            earthAccuracyAtCreationM = null; earthYawAccAtCreationDeg = null
            ArSessionDiagnostics.reanchorAttempts++
        }

        if (geospatialAvailable) {
            // Geospatial is enabled, so a null Earth is unexpected once it lasts (held 3 s, logged on change).
            val earthMissing = earth == null
            DiagnosticsLogger.logOnChange(DIAG, "ar.frag.earthMissing", earthMissing,
                level = if (earthMissing) 'W' else 'I', stableMs = 3_000L) {
                if (earthMissing) "Geospatial Earth unavailable: anchors cannot be placed"
                else "Geospatial Earth available"
            }
        }
        if (earth == null) return if (geospatialAvailable) "Geo: N/A" else "Geo: unavailable on this device"

        // Earth/geospatial tracking-state CHANGE detection (recorded once per change, never per frame;
        // the log line waits until the state holds 3 s).
        val earthTracking = earth.trackingState.name
        DiagnosticsLogger.logOnChange(DIAG, "ar.frag.earthState", earthTracking, stableMs = 3_000L) {
            val earthStateName = runCatching { earth.earthState?.name }.getOrNull() ?: "unknown"
            "Earth tracking changed trackingState=$earthTracking earthState=$earthStateName"
        }
        if (earthTracking != lastEarthTrackingLogged) {
            lastEarthTrackingLogged = earthTracking
            val earthStateName = runCatching { earth.earthState?.name }.getOrNull() ?: "n/a"
            ArSessionDiagnostics.lastEarthTrackingState = earthTracking
            ArSessionDiagnostics.lastEarthState = earthStateName
            // (Re)start the localization timeout clock on entering TRACKING; clear it (and the
            // waiting state) when tracking is lost so a fresh TRACKING period is timed from scratch.
            if (earth.trackingState == TrackingState.TRACKING) {
                earthTrackingSinceMs = System.currentTimeMillis()
            } else {
                earthTrackingSinceMs = 0L
                awaitingLocalization = false
                loggedLocalizationWait = false
            }
        }

        return when (earth.trackingState) {
            TrackingState.TRACKING -> {
                // Auto re-anchor only for a meaningful quality jump: non-RTK → RTK.
                // Incremental steps (SINGLE→DGPS or FLOAT→FIX) are deliberately excluded
                // because switching to RS2+ causes rapid steps through all quality levels
                // in <500ms, which would flood ARCore with create/cancel terrain-anchor
                // requests and crash the session. The manual Re-anchor button handles
                // deliberate upgrades; the cooldown prevents a repeat within 30s.
                val currentFix = currentGnssFix
                if (geoAnchorsCreated && currentFix != null) {
                    val newRank  = currentFix.rtkStatus.qualityRank()
                    val lastRank = lastAnchorRtkStatus?.qualityRank() ?: 0
                    val rtk = io.github.pennstatecs.geokit.gnss.model.RtkStatus.FLOAT.qualityRank()
                    val isRtkUpgrade = newRank >= rtk && lastRank < rtk   // non-RTK → RTK threshold
                    val cooldownElapsed = System.currentTimeMillis() - lastReanchorTimeMs >= REANCHOR_COOLDOWN_MS
                    if (isRtkUpgrade && cooldownElapsed) {
                        ArSessionDiagnostics.reanchorAttempts++
                        ArSessionDiagnostics.anchorsRebuiltAfterInitial++
                        DiagnosticsLogger.i(DIAG, "Anchors auto re-anchor reason=rtk_upgrade " +
                            "rtk=$lastAnchorRtkStatus → ${currentFix.rtkStatus} cleared=${geoAnchors.size}")
                        clearGeoAnchors()
                        geoAnchorsCreated = false
                        lastAnchorRtkStatus = null
                        lastReanchorTimeMs = System.currentTimeMillis()
                        loggedAccuracyGate = false
                        earthAccuracyAtCreationM = null; earthYawAccAtCreationDeg = null
                    }
                }

                // Auto re-anchor when ARCore's OWN localization materially improves after a rough or
                // timeout placement — so a placement committed near the user self-corrects instead of
                // requiring a manual re-anchor. Throttled + cooldown-gated to avoid churn.
                val nowMs = System.currentTimeMillis()
                val committedAcc = earthAccuracyAtCreationM
                if (geoAnchorsCreated && committedAcc != null && nowMs - lastImprovementCheckMs >= 2_000L) {
                    lastImprovementCheckMs = nowMs
                    val curPose = runCatching { earth.cameraGeospatialPose }.getOrNull()
                    val curAcc = curPose?.horizontalAccuracy
                    val curYaw = curPose?.orientationYawAccuracy
                    val cooldownElapsed = nowMs - lastReanchorTimeMs >= REANCHOR_COOLDOWN_MS
                    val reason = if (curAcc == null) null else GeoAnchorGate.reanchorReason(
                        committedAcc, curAcc, cooldownElapsed,
                        yawAtCreationDeg = earthYawAccAtCreationDeg ?: Double.NaN,
                        currentYawDeg = curYaw ?: Double.NaN,
                    )
                    if (reason != null) {
                        ArSessionDiagnostics.reanchorAttempts++
                        ArSessionDiagnostics.anchorsRebuiltAfterInitial++
                        DiagnosticsLogger.i(DIAG, "Anchors auto re-anchor reason=ar_localization_improved kind=$reason " +
                            "hAccM=${"%.2f".format(committedAcc)} → ${curAcc?.let { "%.2f".format(it) } ?: "unknown"} " +
                            "yawAccDeg=${earthYawAccAtCreationDeg?.let { "%.1f".format(it) } ?: "unknown"} → ${curYaw?.let { "%.1f".format(it) } ?: "unknown"} " +
                            "cleared=${geoAnchors.size}")
                        clearGeoAnchors()
                        geoAnchorsCreated = false
                        earthAccuracyAtCreationM = null; earthYawAccAtCreationDeg = null
                        lastReanchorTimeMs = nowMs
                        loggedAccuracyGate = false
                    }
                }

                rebuildGeoAnchorsIfNeeded(earth)
                // Never spawn the test grid from an impossible fix (e.g. lon −760°): the anchors could
                // never resolve. The request stays pending and the debug panel names the blocker.
                val fix = currentGnssFix
                if (fix != null && io.github.pennstatecs.geokit.validation.LatLonValidation.isValidLatLon(fix.latDeg, fix.lonDeg)) {
                    testAnchorController.processActionIfNeeded(earth, fix.latDeg, fix.lonDeg, fixEllipsoidalAltM(fix))
                }
                if (awaitingLocalization) "Geo: Localizing…" else "Geo: TRACKING"
            }
            TrackingState.PAUSED  -> "Geo: Localizing..."
            TrackingState.STOPPED -> "Geo: Off (AR active)"
        }
    }

    /**
     * Iterates [geoAnchors], renders each pin or queues a Filament model pose, and
     * appends to the label/arrow/pin-position collections for this frame.
     */
    private fun collectGeoAnchors(
        earth: Earth?,
        vp: FloatArray,
        edgeMargin: Float,
        pinPositions: MutableList<PinScreenEntry>,
        labelEntries: MutableList<CoordinateLabelOverlay.LabelEntry>,
        arrowEntries: MutableList<OffScreenPinIndicatorOverlay.ArrowEntry>,
        newModelPoses: MutableList<ArFilamentRenderer.ModelPose>
    ) {
        if (earth?.trackingState != TrackingState.TRACKING) return
        // "Where is the user" comes from the GNSS feed (internal or external receiver), the same
        // position the bottom-sheet distances use, so the in-range filter and the list agree. ARCore's
        // camera pose is only a stand-in while the feed has no live fix. (Anchor/camera TRANSFORMS
        // still come from ARCore below — that is rendering, not the user's location.)
        val feedFix = currentGnssFix
        val camGeoPose = earth.cameraGeospatialPose
        val userLat = feedFix?.latDeg ?: camGeoPose.latitude
        val userLon = feedFix?.lonDeg ?: camGeoPose.longitude
        val filterM    = distanceFilterM
        val distanceSkipped = mutableSetOf<String>()
        val mode = arVisibilityModeMirror
        val visibleIds = arVisibleIdsMirror
        val visibleKeys = HashSet<String>()

        for (entry in geoAnchors) {
            val coord = entry.coordWithModel.coordinate
            val entryModelFilePath = entry.modelFilePath
            val distM = Geodesic.distanceM(userLat, userLon, coord.latitude, coord.longitude)
            // Model-visibility decision (shared with the bottom sheet via ArVisibilityLogic): eligibility
            // (has model + in range unless ALL) AND selection (SELECTED mode). Computed regardless of this
            // anchor's tracking so a visible-but-not-tracking model stays in the Filament scene (no
            // flicker). Toggling one item only changes this set — the anchors are untouched, no rebuild.
            val inRange = ArVisibilityLogic.inRange(mode, distM, filterM)
            val modelVisible = entryModelFilePath != null &&
                ArVisibilityLogic.renderable(mode, inRange, coord.id in visibleIds || coord.id == TEST_CUBE_ID)
            if (modelVisible) visibleKeys += coord.id
            if (!inRange) distanceSkipped += coord.id

            if (entry.anchor.trackingState != TrackingState.TRACKING) continue
            // Hidden model coords render nothing (no model/pin/label); out-of-range pins are skipped.
            if (entryModelFilePath != null) {
                if (!modelVisible) continue
            } else if (!inRange) {
                continue
            }

            entry.anchor.pose.toMatrix(model, 0)
            // Terrain mode: put the model on the ground/floor ARCore detected under (or near) it.
            // Far models with no detected surface keep their terrain-anchor height.
            if (entry.onTerrain) groundSnapper.groundY(model[12], model[14])?.let { model[13] = it }
            System.arraycopy(model, 0, modelScaled, 0, 16)

            if (entryModelFilePath != null) {
                // Pass the raw anchor pose — do NOT pre-scale the basis columns here.
                // ArFilamentRenderer folds arModelScale into effScale via ModelPose.coordScale
                // so the correction matrix and bounding-box normalization both see the
                // correct full scale (fixes the inconsistency that caused arModelScale to be
                // applied to the world matrix but NOT to the correction matrix's effScale).
                newModelPoses += ArFilamentRenderer.ModelPose(
                    key        = coord.id,
                    worldMatrix = model.copyOf(),
                    filePath   = entryModelFilePath,
                    coordScale = arModelScale
                )
                if (coord.id == moveTargetId) frameMoveAnchor = model.copyOf()   // for the move gestures
            } else {
                Matrix.scaleM(modelScaled, 0, 0.1f, 0.3f, 0.1f)
                Matrix.multiplyMM(mvp, 0, vp, 0, modelScaled, 0)
                cubeRenderer?.draw(mvp, entry.rgba[0], entry.rgba[1], entry.rgba[2], entry.rgba[3])
            }

            val bearing = Geodesic.initialBearingDeg(userLat, userLon, coord.latitude, coord.longitude)
            val subtext = "${coordinateSystemService.distanceText(distM)} ${Compass.point8(bearing)}"   // units per Settings → Coordinates

            val pinWorldPos = floatArrayOf(model[12], model[13] + 0.6f, model[14], 1f)
            val screenProj = projectToScreen(pinWorldPos, vp, surfaceWidth, surfaceHeight) ?: continue
            if (screenProj.onScreen) {
                pinPositions  += PinScreenEntry(screenProj.sx, screenProj.sy, entry.coordWithModel)
                if (arShowLabels) {
                    val labelText  = if (entryModelFilePath != null)
                        CoordinateLabelOverlay.MODEL_TAG + coord.name else coord.name
                    labelEntries  += CoordinateLabelOverlay.LabelEntry(labelText, screenProj.sx, screenProj.sy, subtext)
                }
            } else if (arShowOffscreenArrows) {
                val arrow = computeEdgeArrow(screenProj.sx, screenProj.sy, surfaceWidth, surfaceHeight, edgeMargin)
                if (arrow != null) {
                    arrowEntries += OffScreenPinIndicatorOverlay.ArrowEntry(
                        edgeX    = arrow.first.x,
                        edgeY    = arrow.first.y,
                        angleDeg = arrow.second,
                        name     = coord.name,
                        distStr  = subtext,
                        isModel  = entryModelFilePath != null
                    )
                }
            }
        }

        // Publish the visible-model set for Filament scene membership; log rendered count on change.
        modelVisibleKeys = visibleKeys
        // Per frame: the count is logged once it holds 3 s (range edges make it flicker while walking).
        val visibleCount = visibleKeys.size
        DiagnosticsLogger.logOnChange(DIAG, "ar.frag.modelsRendered", visibleCount, stableMs = 3_000L) {
            "AR_MODELS_RENDERED count=$visibleCount mode=$mode " +
                "rangeM=${filterM?.let { "%.0f".format(it) } ?: "all"} selectedIds=${visibleIds.size}"
        }
        lastLoggedVisibleCount = visibleCount

        // Diagnostics status follows every change of the distance-filtered set (never every frame).
        if (distanceSkipped != lastDistanceSkippedIds) {
            val newlyHidden = distanceSkipped - lastDistanceSkippedIds
            // A coordinate that re-enters range is no longer distance-skipped; let later load
            // events restore its status. Newly-hidden model coordinates are marked SKIPPED.
            newlyHidden.forEach { id ->
                ArSessionDiagnostics.setStatus(id, ArSessionDiagnostics.ModelStatus.SKIPPED, "distance_filter")
            }
            lastDistanceSkippedIds = distanceSkipped
        }
        // The log line waits until the hidden count holds 3 s (points at the range edge flicker in and out).
        val hiddenCount = distanceSkipped.size
        if (filterM != null) {
            DiagnosticsLogger.logOnChange(DIAG, "ar.frag.distanceSkip", hiddenCount, stableMs = 3_000L) {
                "Models hidden by distance filter maxDistanceM=$filterM hidden=$hiddenCount"
            }
        }
    }

    /** Posts label and arrow overlay updates to the main thread, skipping unchanged frames. */
    private fun postOverlayUpdates(
        labelEntries: List<CoordinateLabelOverlay.LabelEntry>,
        arrowEntries: List<OffScreenPinIndicatorOverlay.ArrowEntry>
    ) {
        if (labelEntries != lastPostedLabels) {
            lastPostedLabels = labelEntries
            // Track the peak number of labels rendered this session (changes only when the set does).
            ArSessionDiagnostics.labelsShown = maxOf(ArSessionDiagnostics.labelsShown, labelEntries.size)
            _binding?.labelOverlay?.post { _binding?.labelOverlay?.updateLabels(labelEntries) }
        }
        if (arrowEntries != lastPostedArrows) {
            lastPostedArrows = arrowEntries
            _binding?.offScreenOverlay?.post { _binding?.offScreenOverlay?.updateArrows(arrowEntries) }
        }
    }

    /** Per-anchor data extracted on the GL thread for safe use in [buildDebugText] on the main thread. */
    private data class AnchorDebugEntry(
        val name: String,
        val coordId: String,
        val tracking: Boolean,
        val distM: Double,
        val bear: Double,
        val hasModel: Boolean,
        val terrainState: String?
    )

    /**
     * Posts a status bar + debug text + model-progress chip update to the main thread.
     * All UI writes happen inside `post {}` so they are safe to call from the GL thread.
     * All ARCore state ([Earth], anchor [TrackingState]) is extracted here on the GL thread
     * into plain data before posting — ARCore objects must never be read on the main thread.
     */
    private fun postDebugPanelUpdate(
        earth: Earth?,
        earthStatus: String,
        planeCount: Int,
        pointCount: Int,
        featuresCounted: Boolean,
        cameraFailureReason: String?,
        optics: ArDebugReport.Optics?
    ) {
        // Extract all ARCore earth values NOW on the GL thread before posting (one pose read, reused
        // for both the debug snapshot and the AR-view-quality indicator).
        val camGeo = runCatching {
            if (earth != null && earth.trackingState == TrackingState.TRACKING) earth.cameraGeospatialPose else null
        }.getOrNull()
        val snapshot: EarthDebugSnapshot? = if (earth != null) {
            EarthDebugSnapshot(
                earthStateName = earth.earthState?.name ?: "null",
                camLat         = camGeo?.latitude,
                camLon         = camGeo?.longitude,
                camAlt         = camGeo?.altitude,
                camHeading     = camGeo?.heading
            )
        } else null

        // ── AR view quality (ARCore geospatial confidence — NOT the GNSS fix) ────────────────────────
        val qHAcc = camGeo?.horizontalAccuracy
        val qVAcc = camGeo?.verticalAccuracy
        val qYaw  = camGeo?.orientationYawAccuracy
        val placement = when {
            !geoAnchorsCreated          -> ArViewQuality.Placement.WAITING
            geoAnchors.isEmpty() && pendingTerrainAnchors.isEmpty() -> ArViewQuality.Placement.FAILED
            lastPlacedVia == "timeout" || lastPlacedVia == "no_vps" -> ArViewQuality.Placement.PLACED_BY_TIMEOUT
            else                        -> ArViewQuality.Placement.PLACED
        }
        val quality = ArViewQuality.evaluate(
            earthTracking  = earth?.trackingState == TrackingState.TRACKING,
            cameraTracking = lastCameraTrackingLogged == "TRACKING",
            hAccM = qHAcc, vAccM = qVAcc, yawDeg = qYaw,
            placement = placement,
            trackingFailure = cameraFailureReason,
            vpsAvailable = vpsAvailable,
        )
        // Logged only once a new level has held for QUALITY_LOG_HOLD_MS, so a value hovering at a
        // threshold can't flood the log.
        lastGeoHAccM = qHAcc; lastGeoYawAccDeg = qYaw; lastArQualityGood = quality.level == ArViewQuality.Level.GOOD
        val qualityChange = qualityLogHysteresis.update(quality.level, android.os.SystemClock.elapsedRealtime())
        if (qualityChange != null) {
            DiagnosticsLogger.i(DIAG, "AR_QUALITY ${qualityChange.from ?: "unknown"} → ${qualityChange.to} " +
                "hAccM=${qHAcc?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "vAccM=${qVAcc?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "yawDeg=${qYaw?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "earthTracking=${earth?.trackingState ?: "unknown"} cameraTracking=${lastCameraTrackingLogged ?: "unknown"} " +
                "placement=$placement modelsRendered=${quality.modelsRenderable} " +
                "placedByTimeout=${placement == ArViewQuality.Placement.PLACED_BY_TIMEOUT}")
        }

        // Extract per-anchor states on the GL thread (anchor.trackingState is an ARCore call).
        val camLat = snapshot?.camLat
        val camLon = snapshot?.camLon
        // The full per-anchor list (distance + bearing + tracking per anchor) is only shown in the debug
        // panel, so it is built only while the panel is open.
        val panelOpen = debugOverlayVisible
        val anchorDebug: List<AnchorDebugEntry> = if (!panelOpen) emptyList() else geoAnchors.map { entry ->
            val coord = entry.coordWithModel.coordinate
            val distM = if (camLat != null && camLon != null)
                Geodesic.distanceM(camLat, camLon, coord.latitude, coord.longitude) else -1.0
            val bear = if (camLat != null && camLon != null)
                Geodesic.initialBearingDeg(camLat, camLon, coord.latitude, coord.longitude) else 0.0
            AnchorDebugEntry(
                name     = coord.name,
                coordId  = coord.id,
                tracking = entry.anchor.trackingState == TrackingState.TRACKING,
                distM    = distM,
                bear     = bear,
                hasModel = entry.modelFilePath != null,
                terrainState = entry.terrainState
            )
        } + (if (!panelOpen) emptyList() else pendingTerrainAnchors.map { p ->
            // Still resolving: shown so the panel's terrain tally keeps reading TASK_IN_PROGRESS.
            val coord = p.coordWithModel.coordinate
            AnchorDebugEntry(
                name = coord.name, coordId = coord.id, tracking = false,
                distM = if (camLat != null && camLon != null)
                    Geodesic.distanceM(camLat, camLon, coord.latitude, coord.longitude) else -1.0,
                bear = if (camLat != null && camLon != null)
                    Geodesic.initialBearingDeg(camLat, camLon, coord.latitude, coord.longitude) else 0.0,
                hasModel = p.coordWithModel.modelFilePath != null,
                terrainState = "TASK_IN_PROGRESS",
            )
        })

        // ARCore ↔ GNSS agreement (live AR accuracy). Shown in the debug panel every frame it's open and
        // logged to the export every 5 s so field sessions record how well AR matched the receiver; with
        // the panel closed it is only computed when the log is due.
        val feedFix = currentGnssFix
        val nowLogMs = System.currentTimeMillis()
        val arVsGnssLogDue = nowLogMs - lastArVsGnssLogMs >= AR_VS_GNSS_LOG_INTERVAL_MS
        val needArVsGnss = camGeo != null && feedFix != null && (panelOpen || arVsGnssLogDue)
        // Farthest anchor (pins and models) from the AR camera; same value either way.
        val farthestModelM: Double? = when {
            !needArVsGnss -> null
            panelOpen -> anchorDebug.filter { it.distM >= 0 }.maxOfOrNull { it.distM }
            camLat == null || camLon == null -> null
            else -> {
                var maxM: Double? = null
                for (entry in geoAnchors) {
                    val c = entry.coordWithModel.coordinate
                    val d = Geodesic.distanceM(camLat, camLon, c.latitude, c.longitude)
                    if (d >= 0 && (maxM == null || d > maxM)) maxM = d
                }
                maxM
            }
        }
        val arVsGnss = if (needArVsGnss && camGeo != null && feedFix != null) ArVsGnss.compute(
            arLat = camGeo.latitude, arLon = camGeo.longitude, arAlt = camGeo.altitude,
            gnssLat = feedFix.latDeg, gnssLon = feedFix.lonDeg, gnssEllipsoidalM = fixEllipsoidalAltM(feedFix),
            // The antenna offset is the external receiver's; an internal-GNSS fix is the tablet itself.
            antennaAboveCameraM = if (feedFix.provider != io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL) arAntennaAboveCameraM else 0.0,
            arHAccM = qHAcc, arYawAccDeg = qYaw, farthestModelM = farthestModelM, speedMps = feedFix.speedMps,
        ) else null
        if (arVsGnss != null && arVsGnssLogDue) {
            lastArVsGnssLogMs = nowLogMs
            // Sampled every 5 s. Export: a summary when the grade changes and holds, plus a 60 s heartbeat;
            // the full detail every sample in debug builds only.
            val summary = "AR_VS_GNSS grade=${arVsGnss.grade} horizM=${"%.2f".format(arVsGnss.horizontalM)} " +
                "vertM=${arVsGnss.verticalM?.let { "%+.2f".format(it) } ?: "unknown"} " +
                "expectedErrM=${arVsGnss.expectedErrAtFarthestM?.let { "%.2f".format(it) } ?: "unknown"} " +
                "fixProvider=${feedFix?.provider} rtk=${feedFix?.rtkStatus}"
            val gradeLogged = DiagnosticsLogger.logOnChange(DIAG, "ar.frag.arVsGnssGrade", arVsGnss.grade,
                stableMs = AR_VS_GNSS_LOG_INTERVAL_MS - 1_000L) { summary }   // = same grade on 2 samples
            if (!gradeLogged) DiagnosticsLogger.every(DIAG, "ar.frag.arVsGnssHeartbeat", 60_000L) { summary }
            DiagnosticsLogger.d(DIAG) { "AR_VS_GNSS kind=detail grade=${arVsGnss.grade} horizM=${"%.2f".format(arVsGnss.horizontalM)} " +
                "bearingDeg=${"%.0f".format(arVsGnss.bearingDeg)} vertM=${arVsGnss.verticalM?.let { "%+.2f".format(it) } ?: "unknown"} " +
                "arHAccM=${arVsGnss.arHAccM?.let { "%.2f".format(it) } ?: "unknown"} " +
                "arYawDeg=${arVsGnss.arYawAccDeg?.let { "%.1f".format(it) } ?: "unknown"} " +
                "farthestPointM=${farthestModelM?.let { "%.1f".format(it) } ?: "unknown"} " +
                "expectedErrM=${arVsGnss.expectedErrAtFarthestM?.let { "%.2f".format(it) } ?: "unknown"} " +
                "fixProvider=${feedFix?.provider} rtk=${feedFix?.rtkStatus} fixHAccM=${feedFix?.hAccM?.let { "%.3f".format(it) } ?: "unknown"} " +
                "speedMps=${feedFix?.speedMps?.let { "%.2f".format(it) } ?: "unknown"} " +
                "antennaAboveCameraM=$arAntennaAboveCameraM profile=$currentGateProfile anchors=${geoAnchors.size}" }
        }

        // Debug-panel-only ARCore reads (GL thread), skipped while the panel is closed.
        val panelArState: PanelArState? = if (panelOpen) {
            PanelArState(
                earthTracking = earth?.trackingState?.name,
                earthState    = runCatching { earth?.earthState?.name }.getOrNull(),
                hAccM = qHAcc, vAccM = qVAcc, yawDeg = qYaw,
                cameraTracking = lastCameraTrackingLogged,
                cameraFailureReason = cameraFailureReason,
                featuresCounted = featuresCounted,
                planes = planeCount, points = pointCount,
                testSpawnPending = testAnchorController.isSpawnPending,
                testGrid = testAnchorController.debugInfo(),
                optics = optics,
                arVsGnss = arVsGnss,
            )
        } else null

        // At most one UI update per DEBUG_UI_POST_INTERVAL_MS, and never while the previous one is
        // still queued. Posted via a Handler so the GL thread never touches the view binding.
        if (!debugUiPostGate.tryAcquire(android.os.SystemClock.elapsedRealtime())) return
        val queued = debugUiHandler.post {
            debugUiPostGate.release()
            if (_binding == null) return@post
            val gnssFix   = currentGnssFix
            val topStatus = if (gnssFix == null) "AR active • Waiting for GNSS • $earthStatus"
                            else "AR active • $earthStatus"

            // Always update the top status bar.
            _binding?.textArStatus?.text = topStatus

            // AR view quality surfacing (only when there are model-linked coordinates to place):
            //  - before placement → prominent centred banner with live AR accuracy + guidance
            //  - after placement  → compact persistent "AR quality" chip
            // This is the ARCore geospatial (AR) confidence, distinct from the GNSS fix chip.
            val haveModels = geoItems.any { it.modelFilePath != null }
            when {
                !haveModels -> {
                    _binding?.localizingBanner?.visibility = View.GONE
                    _binding?.chipArQuality?.visibility = View.GONE
                }
                quality.modelsRenderable -> {
                    _binding?.localizingBanner?.visibility = View.GONE
                    _binding?.chipArQuality?.let { chip ->
                        val timeoutTag = when {
                            lastPlacedVia == "no_vps" -> " · no VPS"
                            placement == ArViewQuality.Placement.PLACED_BY_TIMEOUT -> " · timeout"
                            else -> ""
                        }
                        // When quality is low, a second line tells the user how to improve it.
                        val hint = quality.helperText?.takeIf {
                            quality.level == ArViewQuality.Level.LOW || quality.level == ArViewQuality.Level.NOT_READY
                        }
                        chip.text = "${quality.statusLabel} · ${quality.accuracyText}$timeoutTag" +
                            (hint?.let { "\n$it" } ?: "")
                        chip.visibility = View.VISIBLE
                    }
                }
                else -> {
                    _binding?.chipArQuality?.visibility = View.GONE
                    _binding?.txtLocalizingTitle?.text = quality.statusLabel
                    _binding?.txtLocalizingHint?.text = buildString {
                        append("AR accuracy: ${quality.accuracyText}")
                        quality.headingText?.let { append(" · Heading: $it") }
                        quality.helperText?.let { append("\n$it") }
                    }
                    _binding?.localizingBanner?.visibility = View.VISIBLE
                }
            }

            // Only build the debug string (and pay its allocation cost) when the panel is visible.
            if (debugOverlayVisible && panelArState != null) {
                _binding?.textArDebug?.text = buildDebugText(snapshot, panelArState, gnssFix, anchorDebug)
            }

            updateModelProgressChip()
        }
        if (!queued) debugUiPostGate.release()
    }

    /** ARCore state read on the GL thread for the debug panel (plain data, safe on main thread). */
    private data class PanelArState(
        val earthTracking: String?,
        val earthState: String?,
        val hAccM: Double?,
        val vAccM: Double?,
        val yawDeg: Double?,
        val cameraTracking: String?,
        val cameraFailureReason: String?,
        val featuresCounted: Boolean,
        val planes: Int,
        val points: Int,
        val testSpawnPending: Boolean,
        val testGrid: ArDebugReport.TestGridInfo?,
        val optics: ArDebugReport.Optics?,
        val arVsGnss: ArVsGnss? = null,
    )

    private val opticsProj = FloatArray(16)

    /**
     * Camera FOV (from the GPU texture intrinsics) vs. the FOV of ARCore's projection matrix, i.e.
     * what is actually on screen after ARCore crops the image to the display aspect. GL thread only.
     */
    private fun computeOptics(camera: com.google.ar.core.Camera): ArDebugReport.Optics? = runCatching {
        val ti = camera.textureIntrinsics
        val (fx, fy) = ti.focalLength.let { it[0].toDouble() to it[1].toDouble() }
        val (w, h) = ti.imageDimensions.let { it[0] to it[1] }
        if (fx <= 0 || fy <= 0 || w <= 0 || h <= 0) return@runCatching null
        var camH = Math.toDegrees(2 * kotlin.math.atan(w / (2 * fx)))
        var camV = Math.toDegrees(2 * kotlin.math.atan(h / (2 * fy)))
        camera.getProjectionMatrix(opticsProj, 0, NEAR_CLIP, FAR_CLIP)
        val scrH = Math.toDegrees(2 * kotlin.math.atan(1.0 / opticsProj[0]))
        val scrV = Math.toDegrees(2 * kotlin.math.atan(1.0 / opticsProj[5]))
        // The camera texture is always landscape; swap when the screen is portrait.
        if (scrV > scrH && camH > camV) { val t = camH; camH = camV; camV = t }
        ArDebugReport.Optics(w, h, camH, camV, scrH, scrV)
    }.getOrNull()

    /**
     * Writes one ar-track.csv row (GL thread, debug builds): on each new fix at most every
     * [ArTrackLog.MIN_INTERVAL_MS], or every [ArTrackLog.MAX_STALE_MS] without one. Positions are metres
     * from the session's first fix; nothing is written until there is a fix to use as the origin.
     * Write-only: changes nothing about AR placement.
     */
    private fun recordArTrack(frame: Frame, camera: Camera, earth: Earth?) {
        val now = android.os.SystemClock.elapsedRealtime()
        val fix = currentGnssFix
        val newFix = fix != null && fix !== arTrackLastFix
        val since = now - arTrackLastRowMs
        if (since < ArTrackLog.MIN_INTERVAL_MS) return
        if (!newFix && since < ArTrackLog.MAX_STALE_MS) return

        val origin = arTrackOrigin ?: run {
            val f = fix ?: return
            io.github.pennstatecs.geokit.local.LocalEnu(f.latDeg, f.lonDeg, fixEllipsoidalAltM(f) ?: 0.0).also {
                arTrackOrigin = it
                ArTrackLog.startSession(
                    antennaAboveCameraM = arAntennaAboveCameraM,
                    receiver = f.receiverLabel ?: f.provider.name,
                    device = Build.MODEL ?: "?",
                )
            }
        }
        arTrackLastRowMs = now
        arTrackLastFix = fix

        val tracking = camera.trackingState == TrackingState.TRACKING
        val pose = if (tracking) camera.pose else null
        val geo = if (earth != null && earth.trackingState == TrackingState.TRACKING)
            runCatching { earth.cameraGeospatialPose }.getOrNull() else null
        val fixHae = fix?.let { fixEllipsoidalAltM(it) }
        val fixEnu = fix?.let { f ->
            origin.toEnu(f.latDeg, f.lonDeg, fixHae ?: origin.originHeightM).also { if (fixHae == null) it[2] = Double.NaN }
        }

        ArTrackLog.append(ArTrackRow(
            monoMs = now,
            frameNs = frame.timestamp,
            camState = camera.trackingState.name,
            camPos = pose?.let { floatArrayOf(it.tx(), it.ty(), it.tz()) },
            camQuat = pose?.let { floatArrayOf(it.qx(), it.qy(), it.qz(), it.qw()) },
            earthState = earth?.earthState?.name,
            geoEnu = geo?.let { origin.toEnu(it.latitude, it.longitude, it.altitude) },
            geoHeadingDeg = geo?.heading,
            geoHAccM = geo?.horizontalAccuracy,
            geoVAccM = geo?.verticalAccuracy,
            geoYawAccDeg = geo?.orientationYawAccuracy,
            fixNew = newFix,
            fixAgeMs = fix?.let { now - currentGnssFixMonoMs },
            fixUtcMs = fix?.timeUtc?.toEpochMilli(),
            provider = fix?.provider?.name,
            rtk = fix?.rtkStatus?.name,
            fixEnu = fixEnu,
            fixHAccM = fix?.hAccM,
            fixVAccM = fix?.vAccM,
            sats = fix?.satsUsed,
            diffAgeS = fix?.diffAgeS,
            speedMps = fix?.speedMps,
            courseDeg = fix?.courseDeg,
            antennaAboveCameraM = arAntennaAboveCameraM,
        ))
    }

    /**
     * WGS84 ellipsoidal altitude (HAE) of a fix, as ARCore's createAnchor() expects: altEllipsoidalM,
     * else MSL + geoid separation. Never raw MSL alone (±15–85 m error). Null when unavailable.
     */
    private fun fixEllipsoidalAltM(fix: Fix): Double? = fix.altEllipsoidalM ?: run {
        val msl = fix.altMslM
        val geoid = fix.geoidSeparationM
        if (msl != null && geoid != null) msl + geoid else null
    }

    /** Horizontal accuracy the anchor gate uses — the app-wide estimate (see [ArGnssGate.accuracyM]). */
    private fun gnssGateAccuracyM(fix: Fix): Double? = ArGnssGate.accuracyM(fix)

    /** Builds the debug panel text via [ArDebugReport]. Must be called on the main thread. */
    private fun buildDebugText(
        snapshot: EarthDebugSnapshot?,
        ar: PanelArState,
        gnssFix: Fix?,
        anchorDebug: List<AnchorDebugEntry>
    ): String {
        val items = geoItems
        val renderPoses = arRenderSnapshot?.modelPoses ?: emptyList()
        val renderer = filamentRenderer
        val fixInfo = gnssFix?.let { f ->
            ArDebugReport.FixInfo(
                provider = when (f.provider) {
                    io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL -> "Internal"
                    else -> f.receiverLabel ?: f.provider.label
                },
                rtk      = f.rtkStatus.name,
                sats     = f.satsUsed,
                hAccM    = f.hAccM,
                gateAccM = gnssGateAccuracyM(f),
                ageMs    = runCatching { System.currentTimeMillis() - f.timeUtc.toEpochMilli() }.getOrNull(),
                lat      = f.latDeg,
                lon      = f.lonDeg,
            )
        }
        return ArDebugReport.build(ArDebugReport.Input(
            geospatialEnabled = geospatialAvailable,
            depthEnabled      = depthEnabled,
            cameraTracking    = ar.cameraTracking,
            cameraFailureReason = ar.cameraFailureReason,
            featuresCounted   = ar.featuresCounted,
            planes            = ar.planes,
            points            = ar.points,
            earthTracking     = ar.earthTracking,
            earthState        = ar.earthState,
            earthHAccM        = ar.hAccM,
            earthVAccM        = ar.vAccM,
            earthYawDeg       = ar.yawDeg,
            camLat            = snapshot?.camLat,
            camLon            = snapshot?.camLon,
            camAlt            = snapshot?.camAlt,
            camHeading        = snapshot?.camHeading,
            fix               = fixInfo,
            gnssGateMaxM      = MAX_GNSS_ACCURACY_M,
            items             = items.size,
            itemsWithModels   = items.count { it.modelFilePath != null },
            geoAnchorsCreated = geoAnchorsCreated,
            awaitingLocalization = awaitingLocalization,
            geoAnchors        = anchorDebug.map { e ->
                ArDebugReport.AnchorRow(
                    name       = e.name,
                    tracking   = if (e.tracking) "TRACKING" else "not tracking",
                    terrainState = e.terrainState,
                    distM      = e.distM.takeIf { it >= 0 },
                    bearingDeg = e.bear,
                    model      = if (e.hasModel) renderer?.modelLoadState(e.coordId)?.name ?: "NO_RENDERER" else "PIN",
                )
            },
            testSpawnPending  = ar.testSpawnPending,
            testGrid          = ar.testGrid,
            arVsGnss          = ar.arVsGnss,
            gateProfile       = currentGateProfile.let { p ->
                "$p  H≤${"%.0f".format(p.maxHorizontalM)}m yaw≤${"%.0f".format(p.maxYawDeg)}° timeout ${p.timeoutMs / 1000}s"
            },
            modelsInScene     = renderPoses.count {
                renderer?.modelLoadState(it.key) == ArFilamentRenderer.ModelLoadState.IN_SCENE
            },
            modelPoses        = renderPoses.size,
            optics            = ar.optics,
        ))
    }

    /** Updates the model-load-progress chip. Must be called on the main thread. */
    private fun updateModelProgressChip() {
        val posesSnapshot = arRenderSnapshot?.modelPoses ?: emptyList()
        val total   = posesSnapshot.size
        val inScene = posesSnapshot.count {
            filamentRenderer?.modelLoadState(it.key) == ArFilamentRenderer.ModelLoadState.IN_SCENE
        }
        val progress = inScene to total
        if (progress == lastModelProgress) return
        lastModelProgress = progress
        // Fires only on change (early-return above). Export: real models only (test-grid pins excluded),
        // and only once loading has settled; each intermediate step is debug-only.
        val realPoses = posesSnapshot.filter { !it.key.startsWith(TEST_GRID_KEY_PREFIX) }
        val realInScene = realPoses.count {
            filamentRenderer?.modelLoadState(it.key) == ArFilamentRenderer.ModelLoadState.IN_SCENE
        }
        if (realPoses.isNotEmpty() && realInScene == realPoses.size) {
            DiagnosticsLogger.logOnChange(DIAG, "ar.frag.sceneSettled", realInScene) {
                "AR scene settled modelsInScene=$realInScene total=${realPoses.size} activeAnchors=${geoAnchors.size}"
            }
        }
        DiagnosticsLogger.d(DIAG) { "AR scene state modelsInScene=$inScene total=$total activeAnchors=${geoAnchors.size}" }
        ArSessionDiagnostics.activeAnchorCount = geoAnchors.size
        // Mark models that have reached the scene (only changes when progress changes — not per frame).
        posesSnapshot.forEach { pose ->
            if (filamentRenderer?.modelLoadState(pose.key) == ArFilamentRenderer.ModelLoadState.IN_SCENE)
                ArSessionDiagnostics.setStatus(pose.key, ArSessionDiagnostics.ModelStatus.IN_SCENE)
        }

        val loading = total - inScene
        when {
            total == 0 -> {
                hideModelProgressJob?.cancel()
                _binding?.chipModelProgress?.visibility = View.GONE
            }
            loading > 0 -> {
                hideModelProgressJob?.cancel()
                hideModelProgressJob = null
                _binding?.chipModelProgress?.let {
                    it.text = "⏳ $inScene/$total models"
                    it.visibility = View.VISIBLE
                }
            }
            else -> {
                if (hideModelProgressJob == null) {
                    _binding?.chipModelProgress?.let {
                        it.text = "✓ $total/$total models"
                        it.visibility = View.VISIBLE
                    }
                    // Guard: only launch if the view is still attached.
                    if (_binding != null) {
                        hideModelProgressJob = viewLifecycleOwner.lifecycleScope.launch {
                            kotlinx.coroutines.delay(2000)
                            _binding?.chipModelProgress?.visibility = View.GONE
                            hideModelProgressJob = null
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Coordinate data — handled by OpenInARViewModel; setCoordinates() is called via StateFlow.

    /** Map DB rows (with resolved model paths) to internal GeoItem list and mark anchors for rebuild. */
    // ── Move model ─────────────────────────────────────────────────────────────────────────────────
    // Moves the rendered model relative to its surveyed point; the point itself never changes. By
    // default a move lasts for this AR session only (it can line a model up with reality when ARCore's
    // own position is off). "Save to point" stores it (modelMove* + modelYawDeg), allowed only while AR
    // quality is Good so ARCore's error isn't baked into the point.

    /** A saved move whose database update has not come back through setCoordinates yet: move + start time. */
    private val pendingSaves = HashMap<String, Pair<ModelMove, Long>>()

    /** The point's saved placement, including a just-saved move until the database reflects it. */
    private fun basePlacement(item: CoordWithModel): ModelPlacement {
        val id = item.coordinate.id
        val pending = pendingSaves[id] ?: return item.placement
        if (item.coordinate.updatedAt >= pending.second) { pendingSaves.remove(id); return item.placement }
        return item.placement + pending.first
    }

    /** What the renderer shows: the saved placement plus this session's move. */
    private fun effectivePlacement(item: CoordWithModel): ModelPlacement =
        basePlacement(item) + (sessionMoves[item.coordinate.id] ?: ModelMove())

    private fun moveItem(id: String): CoordWithModel? =
        geoItems.firstOrNull { it.coordWithModel.coordinate.id == id }?.coordWithModel?.takeIf { it.modelFilePath != null }

    private fun setupModelMove() {
        childFragmentManager.setFragmentResultListener(PinInspectBottomSheet.REQUEST_MOVE_MODEL, viewLifecycleOwner) { _, b ->
            b.getString(PinInspectBottomSheet.RESULT_COORD_ID)?.let(::startModelMove)
        }
        val p = binding.arMovePanel
        p.btnNudgeLeft.setOnClickListener { nudgeView(-nudgeStepM, 0f) }
        p.btnNudgeRight.setOnClickListener { nudgeView(nudgeStepM, 0f) }
        p.btnNudgeAway.setOnClickListener { nudgeView(0f, nudgeStepM) }
        p.btnNudgeToward.setOnClickListener { nudgeView(0f, -nudgeStepM) }
        p.btnNudgeUp.setOnClickListener { nudgeBy(ModelMove(upM = nudgeStepM)) }
        p.btnNudgeDown.setOnClickListener { nudgeBy(ModelMove(upM = -nudgeStepM)) }
        p.btnTurnLeft.setOnClickListener { nudgeBy(ModelMove(yawDeg = turnStepDeg())) }     // counter-clockwise = +yaw
        p.btnTurnRight.setOnClickListener { nudgeBy(ModelMove(yawDeg = -turnStepDeg())) }
        p.btnNudgeStep.setOnClickListener {
            nudgeStepM = if (nudgeStepM >= 0.1f) 0.01f else 0.10f
            p.btnNudgeStep.text = if (nudgeStepM >= 0.1f) "10 cm" else "1 cm"
        }
        p.btnMoveReset.setOnClickListener { resetModelMove() }
        p.btnMoveCancel.setOnClickListener { endModelMove(save = false, restore = true, reason = "cancel") }
        p.btnMoveDone.setOnClickListener { endModelMove(save = p.chkMoveSave.isChecked, restore = false, reason = "done") }
    }

    /** 10° per tap at the 10 cm step, 1° at the 1 cm step. */
    private fun turnStepDeg() = if (nudgeStepM >= 0.1f) 10f else 1f

    /** Enters Move mode for [coordId]'s model (from the pin sheet's "Move model"). */
    private fun startModelMove(coordId: String) {
        val item = moveItem(coordId) ?: run { showMoveMessage("This point's model isn't in AR right now"); return }
        if (moveTargetId != null) endModelMove(save = false, restore = false, reason = "switched")
        moveTargetId = coordId
        moveStartSession = sessionMoves[coordId] ?: ModelMove()
        dragStartHit = null; twisting = false
        val p = binding.arMovePanel
        p.txtMoveTitle.text = getString(R.string.ar_move_title, item.coordinate.name)
        p.chkMoveSave.isChecked = false
        p.chkMoveSave.visibility = if (coordId == TEST_CUBE_ID) View.GONE else View.VISIBLE   // nothing to save to
        p.root.visibility = View.VISIBLE
        updateMovePanel()
        moveTicker?.cancel()
        moveTicker = viewLifecycleOwner.lifecycleScope.launch {
            while (true) { kotlinx.coroutines.delay(500); updateMovePanel() }   // accuracy + save availability
        }
        DiagnosticsLogger.i(DIAG, "AR model move started coordinateId=$coordId")
    }

    /**
     * Leaves Move mode. [restore] (Cancel) puts back the session move from when Move mode started;
     * [save] stores the session move on the point when AR quality is Good, else it stays session-only.
     */
    private fun endModelMove(save: Boolean, restore: Boolean, reason: String) {
        val id = moveTargetId ?: return
        moveTicker?.cancel(); moveTicker = null
        moveTargetId = null; moveFrame = null; dragStartHit = null; twisting = false
        _binding?.arMovePanel?.root?.visibility = View.GONE
        if (restore) applySessionMove(id, moveStartSession)
        val item = moveItem(id)
        val move = sessionMoves[id] ?: ModelMove()
        when {
            item == null || move.isZero -> {
                DiagnosticsLogger.i(DIAG, "AR model move ended coordinateId=$id reason=$reason moved=${!move.isZero}")
            }
            save && id == TEST_CUBE_ID -> Unit   // the test cube has no stored point
            save && !lastArQualityGood -> {
                DiagnosticsLogger.w(DIAG, "AR model move not saved: AR quality not Good coordinateId=$id")
                showMoveMessage("AR quality dropped: moved for this session only (not saved)")
            }
            save -> {
                val baseYaw = basePlacement(item).yawDeg
                // Hand the move from the session to a pending save: the model stays where it is on screen.
                sessionMoves.remove(id)
                pendingSaves[id] = move to System.currentTimeMillis()
                filamentRenderer?.updatePlacement(id, effectivePlacement(item))
                viewModel.saveModelMove(id, move, baseYaw) { err ->
                    if (err == null) {
                        showMoveMessage("Model placement saved to the point")
                    } else {
                        // Not stored: keep it as a session move so nothing visibly changes.
                        pendingSaves.remove(id)
                        applySessionMove(id, (sessionMoves[id] ?: ModelMove()) + move)
                        showMoveMessage("Not saved (${err.message ?: err.javaClass.simpleName}): kept for this session")
                    }
                }
            }
            else -> {
                DiagnosticsLogger.i(DIAG, "AR model move ended coordinateId=$id reason=$reason sessionOnly=true " +
                    "dE=${"%.2f".format(move.eastM)} dN=${"%.2f".format(move.northM)} dUp=${"%.2f".format(move.upM)} dYaw=${"%.1f".format(move.yawDeg)}")
                if (reason == "done") showMoveMessage("Moved for this session only (not saved)")
            }
        }
    }

    /** Back to the surveyed point, and undoes this session's turn. Saved only if "Save to point" is then used. */
    private fun resetModelMove() {
        val id = moveTargetId ?: return
        val base = basePlacement(moveItem(id) ?: return)
        applySessionMove(id, ModelMove(eastM = -base.moveEastM, northM = -base.moveNorthM, upM = -base.moveUpM))
    }

    /** Sets [id]'s session move and re-renders it (main thread). */
    private fun applySessionMove(id: String, move: ModelMove) {
        if (move.isZero) sessionMoves.remove(id) else sessionMoves[id] = move
        moveItem(id)?.let { filamentRenderer?.updatePlacement(id, effectivePlacement(it)) }
        if (id == moveTargetId) updateMovePanel()
    }

    private fun nudgeBy(delta: ModelMove) {
        val id = moveTargetId ?: return
        applySessionMove(id, (sessionMoves[id] ?: ModelMove()) + delta)
    }

    /** A nudge relative to the view: [rightM] to the right, [forwardM] away from the user. */
    private fun nudgeView(rightM: Float, forwardM: Float) {
        val frame = moveFrame
        val anchor = frame?.anchor ?: run { showMoveMessage("Point the camera at the model first"); return }
        val enu = ArMoveMath.viewNudgeToEnu(frame.camera, anchor, rightM, forwardM) ?: return
        nudgeBy(ModelMove(eastM = enu[0], northM = enu[1]))
    }

    private fun updateMovePanel() {
        val id = moveTargetId ?: return
        val p = _binding?.arMovePanel ?: return
        val item = moveItem(id) ?: return
        val eff = effectivePlacement(item)
        val turn = sessionMoves[id]?.yawDeg ?: 0f
        p.txtMoveOffset.text = String.format(java.util.Locale.US, "E %+.2f m · N %+.2f m · Up %+.2f m · Turn %+.0f°",
            eff.moveEastM, eff.moveNorthM, eff.moveUpM, turn)
        val good = lastArQualityGood
        val h = lastGeoHAccM?.let { String.format(java.util.Locale.US, "±%.1f m", it) } ?: "—"
        val y = lastGeoYawAccDeg?.let { String.format(java.util.Locale.US, "±%.0f°", it) } ?: "—"
        p.txtMoveAccuracy.text = "AR accuracy $h · heading $y" + if (good) "" else " · saving needs AR quality Good"
        p.chkMoveSave.isEnabled = good
        if (!good && p.chkMoveSave.isChecked) p.chkMoveSave.isChecked = false
        p.txtMoveHint.text = if (moveFrame?.anchor == null) "Point the camera at the model to move it"
                             else getString(R.string.ar_move_hint)
    }

    /** Move mode gestures: one finger drags across the ground; two fingers twist to turn and slide to raise/lower. */
    private fun handleMoveTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginDrag(ev.getX(0), ev.getY(0))
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount >= 2) beginTwist(ev)
            MotionEvent.ACTION_MOVE -> when {
                twisting && ev.pointerCount >= 2 -> updateTwist(ev)
                !twisting && ev.pointerCount == 1 -> updateDrag(ev.getX(0), ev.getY(0))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                twisting = false
                dragStartHit = null
                if (ev.pointerCount - 1 == 1) {
                    val keep = if (ev.actionIndex == 0) 1 else 0
                    beginDrag(ev.getX(keep), ev.getY(keep))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragStartHit = null; twisting = false
                if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            }
        }
        return true
    }

    private fun beginDrag(x: Float, y: Float) {
        val id = moveTargetId ?: return
        val frame = moveFrame ?: return
        val anchor = frame.anchor ?: return
        val item = moveItem(id) ?: return
        // Drag on the horizontal plane at the model's height (anchor height plus its up shift).
        dragPlaneY = anchor[13] + effectivePlacement(item).moveUpM
        dragStartHit = ArMoveMath.hitHorizontalPlane(frame.camera, x, y, dragPlaneY) ?: return
        dragStartMove = sessionMoves[id] ?: ModelMove()
    }

    private fun updateDrag(x: Float, y: Float) {
        val id = moveTargetId ?: return
        val start = dragStartHit ?: return
        val frame = moveFrame ?: return
        val anchor = frame.anchor ?: return
        val hit = ArMoveMath.hitHorizontalPlane(frame.camera, x, y, dragPlaneY) ?: return
        val enu = ArMoveMath.worldDeltaToEnu(anchor, hit[0] - start[0], 0f, hit[2] - start[2])
        applySessionMove(id, dragStartMove + ModelMove(eastM = enu[0], northM = enu[1]))
    }

    private fun beginTwist(ev: MotionEvent) {
        val id = moveTargetId ?: return
        dragStartHit = null
        val frame = moveFrame
        val anchor = frame?.anchor
        twistDistM = if (frame != null && anchor != null) ArMoveMath.distance(frame.camera, anchor[12], anchor[13], anchor[14]) else 5f
        twistStartAngle = ArMoveMath.pointerAngleDeg(ev.getX(0), ev.getY(0), ev.getX(1), ev.getY(1))
        twistStartCy = (ev.getY(0) + ev.getY(1)) / 2f
        twistStartMove = sessionMoves[id] ?: ModelMove()
        twisting = true
    }

    private fun updateTwist(ev: MotionEvent) {
        val id = moveTargetId ?: return
        val yaw = ArMoveMath.twistToYawDeg(twistStartAngle, ArMoveMath.pointerAngleDeg(ev.getX(0), ev.getY(0), ev.getX(1), ev.getY(1)))
        val up = moveFrame?.let { ArMoveMath.verticalDragToUpM(it.camera, (ev.getY(0) + ev.getY(1)) / 2f - twistStartCy, twistDistM) } ?: 0f
        applySessionMove(id, twistStartMove + ModelMove(upM = up, yawDeg = yaw))
    }

    /** GL thread: the camera this frame (display-oriented, as the view matrix uses) and the move target's anchor. */
    private fun moveFrameFor(camera: com.google.ar.core.Camera): MoveFrame {
        val pose = camera.displayOrientedPose
        val q = FloatArray(4).also { pose.getRotationQuaternion(it, 0) }
        return MoveFrame(
            ArMoveMath.CameraState(pose.tx(), pose.ty(), pose.tz(), q[0], q[1], q[2], q[3],
                proj[0], proj[5], proj[8], proj[9], surfaceWidth, surfaceHeight),
            frameMoveAnchor,
        )
    }

    /** The point sheet (details, and Move model when it has a model) for [cwm]. Main thread. */
    private fun openPinSheet(cwm: CoordWithModel) {
        val coord = cwm.coordinate
        // Position/height in the user's coordinate system (AR coordinates are WGS84).
        val p = runCatching {
            coordinateSystemService.convertWgs84(coord.latitude, coord.longitude, coord.altitude, coord.timestamp)
        }.getOrNull()
        PinInspectBottomSheet.show(
            childFragmentManager,
            name      = coord.name,
            lat       = coord.latitude,
            lon       = coord.longitude,
            alt       = coord.altitude,
            hAccM     = coord.horizontalAccuracyM,
            rtkStatus = coord.rtkStatus?.name,
            provider  = coord.provider.name,
            modelId   = cwm.modelId,
            timestamp = coord.timestamp,
            positionText = p?.let { app.surrealar.coords.CoordinateFormat.horizontal(it, latLonDecimals = 7) },
            heightText   = p?.let { app.surrealar.coords.CoordinateFormat.height(it) },
            coordinateId = coord.id,
        )
    }

    /**
     * The visible model whose on-screen box contains ([x], [y]), nearest the camera when several do, or
     * null. Uses the renderer's world box per model and the last frame's camera matrices. Main thread.
     */
    private fun modelAtScreen(x: Float, y: Float): CoordWithModel? {
        val snap = arRenderSnapshot ?: return null
        if (!snap.cameraTracking) return null
        val renderer = filamentRenderer ?: return null
        val vp = FloatArray(16).also { Matrix.multiplyMM(it, 0, snap.projMatrix, 0, snap.viewMatrix, 0) }
        val v = snap.viewMatrix
        // Camera position from the view matrix: c = -Rᵀ·t.
        val camX = -(v[0] * v[12] + v[1] * v[13] + v[2] * v[14])
        val camY = -(v[4] * v[12] + v[5] * v[13] + v[6] * v[14])
        val camZ = -(v[8] * v[12] + v[9] * v[13] + v[10] * v[14])
        val slack = 16f * resources.displayMetrics.density
        var best: Pair<String, Float>? = null
        for (key in snap.visibleKeys) {
            val b = renderer.worldBounds(key) ?: continue
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var behind = false
            for (i in 0 until 8) {
                val corner = floatArrayOf(if (i and 1 == 0) b[0] else b[3], if (i and 2 == 0) b[1] else b[4], if (i and 4 == 0) b[2] else b[5], 1f)
                val sp = projectToScreen(corner, vp, surfaceWidth, surfaceHeight)
                if (sp == null) { behind = true; continue }
                minX = minOf(minX, sp.sx); maxX = maxOf(maxX, sp.sx); minY = minOf(minY, sp.sy); maxY = maxOf(maxY, sp.sy)
            }
            // A corner behind the camera means the user is at or inside the model: count it as hit.
            val inside = behind || (x in (minX - slack)..(maxX + slack) && y in (minY - slack)..(maxY + slack))
            if (!inside) continue
            val cx = (b[0] + b[3]) / 2f - camX; val cy = (b[1] + b[4]) / 2f - camY; val cz = (b[2] + b[5]) / 2f - camZ
            val d = cx * cx + cy * cy + cz * cz
            if (best == null || d < best.second) best = key to d
        }
        val key = best?.first ?: return null
        return geoItems.firstOrNull { it.coordWithModel.coordinate.id == key }?.coordWithModel
    }

    // ── Test cube ──────────────────────────────────────────────────────────────────────────────────
    // A temporary point with the bundled 1 m test cube (assets/model_images), placed in front of the user so model
    // manipulation can be tried without a real point. It goes through the normal anchor/model pipeline
    // (pin, Move model, gestures) but is never stored and disappears when AR closes.

    private fun withTestCube(items: List<CoordWithModel>): List<CoordWithModel> =
        testCube?.let { items + it } ?: items

    /**
     * GL thread: where to put the cube — the detected floor under the middle of the view (within 6 m),
     * else 2 m ahead and 1.4 m below the camera (about the floor with a hand-held tablet) — converted to
     * latitude/longitude/height with Earth, then handed to the main thread.
     */
    private fun placeTestCube(frame: com.google.ar.core.Frame, camera: com.google.ar.core.Camera, earth: com.google.ar.core.Earth?) {
        if (earth == null || earth.trackingState != TrackingState.TRACKING || camera.trackingState != TrackingState.TRACKING) {
            activity?.runOnUiThread { showMoveMessage("AR isn't localized yet: try the test cube again in a moment") }
            return
        }
        val floor = runCatching {
            frame.hitTest(surfaceWidth / 2f, surfaceHeight * 0.6f).firstOrNull { h ->
                val t = h.trackable
                t is com.google.ar.core.Plane && t.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    t.isPoseInPolygon(h.hitPose) && h.distance <= 6f
            }?.hitPose
        }.getOrNull()
        val pose = floor ?: run {
            val cp = camera.pose
            val z = cp.zAxis                                  // camera looks along -Z
            val len = kotlin.math.sqrt(z[0] * z[0] + z[2] * z[2]).takeIf { it > 1e-3f } ?: 1f
            com.google.ar.core.Pose.makeTranslation(cp.tx() - 2f * z[0] / len, cp.ty() - 1.4f, cp.tz() - 2f * z[2] / len)
        }
        val geo = runCatching { earth.getGeospatialPose(pose) }.getOrNull()
        if (geo == null) { activity?.runOnUiThread { showMoveMessage("Couldn't place the test cube: AR position unavailable") }; return }
        val onFloor = floor != null
        val lat = geo.latitude; val lon = geo.longitude; val alt = geo.altitude
        activity?.runOnUiThread { addTestCube(lat, lon, alt, onFloor) }
    }

    /** Main thread: writes the cube model if needed and adds the temporary point. */
    private fun addTestCube(lat: Double, lon: Double, altM: Double, onFloor: Boolean) {
        if (_binding == null) return
        val scope = viewLifecycleOwner.lifecycleScope
        scope.launch {
            // The bundled 1 m test cube (Filament loads models from files, so copy it out of the APK once).
            val ctx = requireContext().applicationContext
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    File(ctx.cacheDir, "debug_models/$TEST_CUBE_ASSET_NAME").apply {
                        parentFile?.mkdirs()
                        // 120 KB: read it rather than openFd (fails on compressed assets); rewrite if it changed.
                        val bytes = ctx.assets.open(TEST_CUBE_ASSET).use { it.readBytes() }
                        if (!exists() || length() != bytes.size.toLong()) writeBytes(bytes)
                    }
                }.onFailure { DiagnosticsLogger.w(DIAG, "Test cube model copy failed", it) }.getOrNull()
            } ?: run { showMoveMessage("Couldn't load the test cube model"); return@launch }
            val now = System.currentTimeMillis()
            val entity = app.surrealar.data.local.entity.CoordinateEntity(
                id = TEST_CUBE_ID, name = "Test cube", latitude = lat, longitude = lon, altitude = altM,
                timestamp = now, icon = "", color = 0xFFFF8C0D.toInt(),
                verticalAccuracyM = 0.01,   // AUTO altitude uses this exact height (not terrain)
                modelId = TEST_CUBE_ID, renderEnabled = true, createdAt = now, updatedAt = now,
            )
            testCube = CoordWithModel(entity, TEST_CUBE_ID, file.absolutePath,
                ModelPlacement(placementOrigin = ModelPlacementOrigin.BOTTOM_CENTER))   // stands on its point
            setCoordinates(withTestCube(lastDbItems))
            _binding?.txtTestCube?.text = "Remove"
            DiagnosticsLogger.i(DIAG, "Test cube placed onFloor=$onFloor")
            showMoveMessage("Test cube placed: tap its pin, then Move model")
        }
    }

    private fun removeTestCube() {
        if (moveTargetId == TEST_CUBE_ID) endModelMove(save = false, restore = false, reason = "test cube removed")
        testCube = null
        sessionMoves.remove(TEST_CUBE_ID)
        setCoordinates(lastDbItems)
        _binding?.txtTestCube?.text = "Cube"
        DiagnosticsLogger.i(DIAG, "Test cube removed")
    }

    private fun showMoveMessage(msg: String) {
        _binding?.root?.let { com.google.android.material.snackbar.Snackbar.make(it, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
    }

    private fun setCoordinates(items: List<CoordWithModel>) {
        val withModels = items.filter { it.modelFilePath != null }
        val visibleModels = withModels.count { it.coordinate.renderEnabled }
        ArSessionDiagnostics.coordinateCount = maxOf(ArSessionDiagnostics.coordinateCount, items.size)
        ArSessionDiagnostics.linkedModelCount = maxOf(ArSessionDiagnostics.linkedModelCount, withModels.size)
        ArSessionDiagnostics.coordinatesVisible = maxOf(ArSessionDiagnostics.coordinatesVisible, visibleModels)
        // Skip rebuild if the incoming list is identical to what we already have.
        // Room emits on every observed change; without this guard a no-op emission
        // clears geoAnchorsCreated and forces a full anchor rebuild at 60fps.
        val newItems = items.map { item ->
            val c = item.coordinate
            // Only treat altitude as absent when it is NaN or infinite.
            // DO NOT exclude 0.0 — coordinates at sea level have a legitimate 0m ellipsoidal altitude.
            val altitude = if (c.altitude.isNaN() || !c.altitude.isFinite()) null else c.altitude
            GeoItem(
                lat = c.latitude,
                lng = c.longitude,
                alt = altitude,
                rgba = argbIntToRgba(c.color),
                modelFilePath = item.modelFilePath,
                modelId = item.modelId,
                coordWithModel = item
            )
        }
        if (newItems == geoItems) {
            // Anchors unchanged (GeoItem equality covers position, colour and model file only). A
            // placement edit (scale, rotation, origin, offsets) still has to reach the rendered model.
            items.forEach { item ->
                if (item.modelFilePath != null) filamentRenderer?.updatePlacement(item.coordinate.id, effectivePlacement(item))
            }
            return   // nothing else changed — avoid spurious anchor rebuild
        }

        // Logged only for real changes (after the no-change return), so Room's repeat emissions are silent.
        DiagnosticsLogger.i(DIAG,
            "AR coordinates updated total=${items.size} withModels=${withModels.size} visible=$visibleModels")
        geoItems = newItems
        // Invalidate anchors so they rebuild for the new coordinate set. Logged so the export shows
        // every reason anchors are cleared/rebuilt (here: the coordinate list changed, not user motion).
        if (geoAnchorsCreated) DiagnosticsLogger.i(DIAG,
            "Anchors invalidated reason=coordinates_changed newCount=${newItems.size} prevAnchors=${geoAnchors.size}")
        geoAnchorsCreated = false

        // Session moves and Move mode only for points that still have a model.
        val modelIds = items.filter { it.modelFilePath != null }.mapTo(HashSet()) { it.coordinate.id }
        sessionMoves.keys.retainAll(modelIds)
        moveTargetId?.let { if (it !in modelIds) endModelMove(save = false, restore = false, reason = "point removed") }

        // Free the cached GLBs of coordinates that were deleted or lost their model link.
        filamentRenderer?.retainModels(items.filter { it.modelFilePath != null }.mapTo(HashSet()) { it.coordinate.id })

        // Kick off Filament GLB loading for every model-linked coordinate. preload() must run on the
        // main thread (Filament requirement), but the per-file existence/size stat is filesystem I/O —
        // batch those off the main thread first, then dispatch the preloads back on main.
        val scope = viewLifecycleOwner.lifecycleScope
        logModelReadinessOnce(items.mapNotNull { it.modelId }.filter { it != TEST_CUBE_ID }.distinct(), scope)
        scope.launch {
            val stats: Map<String, Pair<Boolean, Long>> = withContext(Dispatchers.IO) {
                items.mapNotNull { it.modelFilePath }.distinct().associateWith { p ->
                    val f = File(p); val e = f.exists(); e to (if (e) f.length() else -1L)
                }
            }
            items.forEach { item ->
                val coord = item.coordinate
                val path  = item.modelFilePath ?: return@forEach   // no linked model — pin/label only
                // Per-model context so the export can pinpoint why a model did or did not render
                // (missing file, disabled visibility, altitude, scale, placement).
                val (exists, sizeBytes) = stats[path] ?: (false to -1L)
                val altState = if (coord.altitude.isNaN() || !coord.altitude.isFinite()) "missing" else "stored"
                val basename = path.substringAfterLast('/')
                DiagnosticsLogger.d(DIAG) {
                    "Model preload dispatched coordinateId=${coord.id} modelId=${item.modelId} " +
                    "file=$basename exists=$exists " +
                    "sizeBytes=$sizeBytes renderEnabled=${coord.renderEnabled} " +
                    "altitude=$altState coordScale=$arModelScale placement=${item.placement}" }
                when {
                    !exists -> {
                        // Relinked to a file that is missing: don't keep showing the previous model.
                        filamentRenderer?.forgetModel(coord.id)
                        ArSessionDiagnostics.recordModel(coord.id, coord.name, item.modelId, basename,
                            exists, sizeBytes, ArSessionDiagnostics.ModelStatus.SKIPPED, "missing_file")
                        DiagnosticsLogger.w(DIAG,
                            "Model skipped: file missing coordinateId=${coord.id} modelId=${item.modelId} file=$basename")
                    }
                    else -> {
                        // Preload every model that exists on disk regardless of visibility — the AR
                        // model-visibility control decides at render time which are shown, so preloading
                        // all makes toggling a model on instant (no reload) with no anchor rebuild.
                        ArSessionDiagnostics.recordModel(coord.id, coord.name, item.modelId, basename,
                            exists, sizeBytes, ArSessionDiagnostics.ModelStatus.QUEUED)
                        filamentRenderer?.preload(coord.id, path, scope, effectivePlacement(item))
                    }
                }
            }
        }
    }

    /**
     * Logs one MODEL_READINESS line per linked model, once per AR session. Runs on the repository's IO
     * dispatcher (never the GL thread); read-only, so AR rendering is unaffected.
     */
    private fun logModelReadinessOnce(modelIds: List<String>, scope: kotlinx.coroutines.CoroutineScope) {
        val pending = modelIds.filter { readinessLoggedModelIds.add(it) }
        if (pending.isEmpty()) return
        scope.launch {
            for (id in pending) {
                try {
                    val facts = readinessRepository.facts(id) ?: continue
                    val report = withContext(Dispatchers.Default) { readinessRepository.evaluate(facts) }
                    val line = app.surrealar.ui.models.readiness.ReadinessStyle.logLine(facts.fileName, report)
                    if (report.verdict == app.surrealar.ui.models.readiness.Verdict.READY) {
                        DiagnosticsLogger.i(DIAG, line)
                    } else {
                        DiagnosticsLogger.w(DIAG, line)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    DiagnosticsLogger.w(DIAG, "MODEL_READINESS modelId=$id result=failed", e)
                }
            }
        }
    }

    /** Updates the compact AR GNSS status chip with live source, solution, and accuracy. */
    private fun updateArGnssChip(fix: Fix) {
        val b = _binding ?: return
        val src = when (fix.provider) {
            io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL -> "Device GPS"
            io.github.pennstatecs.geokit.gnss.model.Provider.EXTERNAL_TCP, io.github.pennstatecs.geokit.gnss.model.Provider.EXTERNAL_BT ->
                app.surrealar.settings.model.ExternalReceiverProfile.fromLabel(fix.receiverLabel)?.shortLabel ?: "EXT"
            else -> fix.provider.label
        }
        val (solLabel, solColor) = when (fix.rtkStatus) {
            io.github.pennstatecs.geokit.gnss.model.RtkStatus.FIX    -> "RTK Fix"    to requireContext().getColor(R.color.app_success)
            io.github.pennstatecs.geokit.gnss.model.RtkStatus.FLOAT  -> "RTK Float"  to requireContext().getColor(R.color.app_warning)
            io.github.pennstatecs.geokit.gnss.model.RtkStatus.DGPS   -> "DGPS"       to requireContext().getColor(R.color.app_info)
            io.github.pennstatecs.geokit.gnss.model.RtkStatus.SINGLE -> "Single"     to requireContext().getColor(android.R.color.white)
            else                                                  -> "No Fix"     to requireContext().getColor(R.color.app_error)
        }
        val acc = fix.hAccM?.let { " · H ±${"%.2f".format(it)} m" } ?: ""
        b.chipArGnssStatus.text = "$src · $solLabel$acc"
        b.chipArGnssStatus.setTextColor(solColor)
    }

    /** Configure GL surface view and queue taps for processing on GL thread. */
    private fun setupGlSurface() {
        val gl = binding.glSurfaceViewAr
        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(3)
        gl.setRenderer(this)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Ensure the view can receive touch events
        gl.isFocusable = true
        gl.isFocusableInTouchMode = true
        gl.isClickable = true
        gl.requestFocus()

        gl.setOnTouchListener { v, ev ->
            if (moveTargetId != null) return@setOnTouchListener handleMoveTouch(v, ev)
            if (ev.action == MotionEvent.ACTION_UP) {
                val tapX = ev.x
                val tapY = ev.y

                // Check whether the tap lands on (or near) a geospatial pin.
                // Hit radius = 60 dp — generous enough for finger-sized targets.
                val threshPx = 60f * resources.displayMetrics.density
                val threshSq = threshPx * threshPx
                val cache    = pinScreenCache         // snapshot (volatile read)
                val hit      = cache.minByOrNull {
                    val dx = it.x - tapX; val dy = it.y - tapY; dx * dx + dy * dy
                }
                if (hit != null) {
                    val dx = hit.x - tapX; val dy = hit.y - tapY
                    if (dx * dx + dy * dy <= threshSq) {
                        // CoordWithModel is embedded in PinScreenEntry — no geoAnchors access needed
                        openPinSheet(hit.coordWithModel)
                        v.performClick()
                        return@setOnTouchListener true
                    }
                }

                // Not on a pin: a tap anywhere on a model's on-screen area selects it. The pin is a single
                // point near the anchor, which a large model covers or sits far from.
                modelAtScreen(tapX, tapY)?.let { openPinSheet(it) }

                // Consume the tap (performClick for accessibility).
                v.performClick()
                true
            } else {
                false
            }
        }

        DevLog.d(TAG) { "GLSurfaceView setup complete with touch listener" }
    }
}