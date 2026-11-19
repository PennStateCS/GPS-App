package app.surrealar.ui.openinar

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import app.surrealar.util.DiagnosticsLogger
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encapsulates all debug test-anchor grid logic, keeping it out of [OpenInARFragment].
 *
 * The controller owns the test anchor list, the spawn/clear action flag, and the
 * GL-side rendering loop. The fragment interacts via [requestSpawn]/[requestClear] on
 * the main thread, and [processActionIfNeeded]/[render] on the GL thread.
 */
class TestAnchorController(private val context: Context) {

    // ── Public data types ─────────────────────────────────────────────────────

    data class TestAnchorEntry(
        val anchor: Anchor,
        val modelKey: String?,
        val label: String,
        val rgba: FloatArray
    )

    private enum class Action { NONE, SPAWN, RESPAWN, CLEAR }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "TestAnchorController"
        const val ASSET_PATH = "model_images/pin.glb"
        const val PIN_SCALE = 0.3048f
        const val PIN_GROUND_DROP_M = 0.1524f

        /** Half-width of the grid in cells. Total grid = (2*GRID_RADIUS+1)² points. */
        const val GRID_RADIUS = 2
        /** Distance between adjacent grid points in metres. */
        const val GRID_SPACING_M = 5.0

        fun gridKey(latOff: Int, lonOff: Int): String = "test_grid_${latOff}_${lonOff}"
    }

    // ── State (thread-access noted per field) ─────────────────────────────────

    /** All live test anchors. Written and read exclusively on the GL thread. */
    val anchors: MutableList<TestAnchorEntry> = mutableListOf()

    /** Written on main thread; consumed and reset on GL thread. */
    @Volatile private var action: Action = Action.NONE

    /** Written on GL thread; read on main thread for button label. */
    @Volatile var isActive: Boolean = false

    /** Written on main thread (IO coroutine result); read on GL thread. */
    @Volatile var pinModelFilePath: String? = null

    /**
     * Mirrors the AR screen's Stored/Terrain altitude toggle. Terrain: pins are resolved onto the
     * ground via `resolveAnchorOnTerrain`. Stored: WGS84 anchors at the spawn fix's ellipsoidal
     * altitude (test points have no surveyed altitude of their own). Written on main thread.
     */
    @Volatile var useTerrain: Boolean = true

    /** Grid centre captured at spawn so a mode switch re-creates the SAME grid. GL thread only. */
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var centerAltEllipsoidal: Double? = null

    /** Mode the current grid was built with ("TERRAIN"/"STORED"), for the debug panel. */
    @Volatile var activeMode: String? = null
        private set

    // ── Main-thread API ───────────────────────────────────────────────────────

    /**
     * Extract `pin.glb` from assets to internal storage so Filament can load it via [File].
     * Calls [onReady] on the main thread with the absolute path once the file is ready.
     */
    fun prepareAsset(scope: CoroutineScope, onReady: (String) -> Unit) {
        scope.launch {
            val outFile = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = File(context.filesDir, "debug_models/pin.glb")
                    dst.parentFile?.mkdirs()
                    if (!dst.exists() || dst.length() == 0L) {
                        context.assets.open(ASSET_PATH).use { input ->
                            dst.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    dst
                }.getOrNull()
            }
            if (outFile != null) {
                pinModelFilePath = outFile.absolutePath
                Log.d(TAG, "Test pin model ready: ${outFile.absolutePath}")
                onReady(outFile.absolutePath)
            } else {
                Log.e(TAG, "Failed to prepare $ASSET_PATH")
            }
        }
    }

    /**
     * Preload all grid cell keys into Filament so models are ready when spawned.
     * Call on the main thread after [pinModelFilePath] is set.
     */
    fun preloadModelKeys(filamentRenderer: ArFilamentRenderer, scope: CoroutineScope) {
        val filePath = pinModelFilePath ?: return
        for (latOff in -GRID_RADIUS..GRID_RADIUS) {
            for (lonOff in -GRID_RADIUS..GRID_RADIUS) {
                filamentRenderer.preload(gridKey(latOff, lonOff), filePath, scope)
            }
        }
    }

    /** True while a spawn request is waiting for Earth TRACKING + a valid GNSS fix. */
    val isSpawnPending: Boolean get() = action == Action.SPAWN || action == Action.RESPAWN

    /**
     * Snapshot for the debug panel: how many test anchors track, and their terrain-anchor states
     * (the only signal that says WHY a terrain anchor never resolves). GL thread only.
     */
    @Suppress("DEPRECATION")
    internal fun debugInfo(): ArDebugReport.TestGridInfo? {
        if (anchors.isEmpty()) return null
        val states = anchors
            .groupingBy { runCatching { it.anchor.terrainAnchorState.name }.getOrDefault("UNKNOWN") }
            .eachCount()
        return ArDebugReport.TestGridInfo(
            total = anchors.size,
            tracking = anchors.count { it.anchor.trackingState == TrackingState.TRACKING },
            terrainStates = states,
            mode = activeMode,
        )
    }

    /**
     * Model keys of every test anchor that currently EXISTS, tracking or not. A freshly respawned
     * grid (e.g. after the Stored/Terrain toggle) has no tracking anchors for a while — terrain
     * anchors resolve asynchronously — so "has a pose this frame" must not be used to decide that a
     * test model is gone. GL thread only.
     */
    fun liveModelKeys(): Set<String> = anchors.mapNotNullTo(HashSet()) { it.modelKey }

    /** Signal that the test grid should be spawned on the next GL frame. */
    fun requestSpawn() { action = Action.SPAWN }

    /** Signal that the test grid should be cleared on the next GL frame. */
    fun requestClear() { action = Action.CLEAR }

    /**
     * Rebuild the existing grid (same centre) in the current [useTerrain] mode — call after the
     * altitude toggle changes. No-op when no grid is active.
     */
    fun requestRespawn() { if (isActive && action == Action.NONE) action = Action.RESPAWN }

    // ── GL-thread API ─────────────────────────────────────────────────────────

    /**
     * Must be called from the GL thread (inside `onDrawFrame`).
     * Processes any pending spawn/clear request using [earth] and the current GNSS position.
     *
     * @param gnssAltEllipsoidal the fix's WGS84 ellipsoidal altitude (HAE), used by Stored mode.
     */
    fun processActionIfNeeded(earth: Earth, gnssLat: Double, gnssLon: Double, gnssAltEllipsoidal: Double?) {
        when (action) {
            Action.SPAWN -> {
                action = Action.NONE
                centerLat = gnssLat
                centerLon = gnssLon
                centerAltEllipsoidal = gnssAltEllipsoidal
                spawnGrid(earth)
            }
            Action.RESPAWN -> {
                action = Action.NONE
                spawnGrid(earth)
            }
            Action.CLEAR -> {
                action = Action.NONE
                clearGrid()
            }
            Action.NONE -> Unit
        }
    }


    /**
     * Renders all live test anchors and fills [labelEntries], [arrowEntries], and [newModelPoses].
     * Must be called from the GL thread.
     *
     * @param model       Scratch 4×4 matrix (written by this call).
     * @param modelScaled Scratch 4×4 matrix (written by this call).
     * @param mvp         Scratch 4×4 matrix (written by this call).
     */
    internal fun render(
        vp: FloatArray,
        model: FloatArray,
        modelScaled: FloatArray,
        mvp: FloatArray,
        cubeRenderer: SimpleObjectRenderer?,
        surfaceWidth: Int,
        surfaceHeight: Int,
        edgeMargin: Float,
        labelEntries: MutableList<CoordinateLabelOverlay.LabelEntry>,
        arrowEntries: MutableList<OffScreenPinIndicatorOverlay.ArrowEntry>,
        newModelPoses: MutableList<ArFilamentRenderer.ModelPose>
    ) {
        val testPinPath = pinModelFilePath
        var rendered = 0

        for (te in anchors) {
            if (te.anchor.trackingState != TrackingState.TRACKING) continue
            rendered++
            te.anchor.pose.toMatrix(model, 0)

            if (te.modelKey != null && testPinPath != null) {
                // Pass the raw anchor world matrix (no pre-scaling) and let ArFilamentRenderer
                // fold PIN_SCALE into effScale via coordScale. The ground-drop Y offset is a
                // direct ARCore-world-metre translation (unaffected by the basis-column scaling
                // that Matrix.scaleM used to apply) so it stays correct here.
                val rawWorld = model.copyOf()
                rawWorld[13] -= PIN_GROUND_DROP_M   // lower pin tip to ground (ARCore world metres)
                newModelPoses += ArFilamentRenderer.ModelPose(
                    key         = te.modelKey,
                    worldMatrix = rawWorld,
                    filePath    = testPinPath,
                    coordScale  = PIN_SCALE
                )
            } else {
                System.arraycopy(model, 0, modelScaled, 0, 16)
                Matrix.scaleM(modelScaled, 0, 0.1f, 0.22f, 0.1f)
                Matrix.multiplyMM(mvp, 0, vp, 0, modelScaled, 0)
                cubeRenderer?.draw(mvp, te.rgba[0], te.rgba[1], te.rgba[2], te.rgba[3])
            }

            val worldPos = floatArrayOf(model[12], model[13] + 0.5f, model[14], 1f)
            val proj: ScreenProjection = projectToScreen(worldPos, vp, surfaceWidth, surfaceHeight)
                ?: continue
            if (proj.onScreen) {
                labelEntries += CoordinateLabelOverlay.LabelEntry(te.label, proj.sx, proj.sy)
            } else {
                val arrow: Pair<android.graphics.PointF, Float> =
                    computeEdgeArrow(proj.sx, proj.sy, surfaceWidth, surfaceHeight, edgeMargin)
                    ?: continue
                arrowEntries += OffScreenPinIndicatorOverlay.ArrowEntry(
                    edgeX    = arrow.first.x,
                    edgeY    = arrow.first.y,
                    angleDeg = arrow.second,
                    name     = te.label,
                    distStr  = "",
                    isModel  = te.modelKey != null
                )
            }
        }

        if (isActive && rendered == 0) {
            Log.w(TAG, "⚠️ Test anchors active but NONE are TRACKING — check altitude & GNSS accuracy.")
        }
    }

    /** Detach and clear all test anchors. Safe to call after the GL thread stops. */
    fun cleanup() {
        for (te in anchors) try { te.anchor.detach() } catch (_: Exception) {}
        anchors.clear()
        isActive = false
        action   = Action.NONE
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun spawnGrid(earth: Earth) {
        clearGrid()
        val lat0 = centerLat
        val lon0 = centerLon

        // Stored mode needs an altitude. Prefer the spawn fix's ellipsoidal altitude; otherwise
        // fall back to ARCore's own camera altitude so the grid still appears (logged below).
        val terrain = useTerrain
        val storedAlt: Double? = if (terrain) null else
            centerAltEllipsoidal ?: runCatching { earth.cameraGeospatialPose.altitude }.getOrNull()
        val altSource = when {
            terrain -> "terrain"
            centerAltEllipsoidal != null -> "gnss_fix"
            storedAlt != null -> "arcore_camera"
            else -> "none"
        }
        if (!terrain && storedAlt == null) {
            DiagnosticsLogger.w(ArFilamentRenderer.DIAG,
                "Test grid: Stored mode has no altitude (no fix HAE, no camera pose) — using terrain")
        }
        val useTerrainNow = terrain || storedAlt == null

        val dLatPerM = 1.0 / 111_111.0
        val dLonPerM = 1.0 / (111_111.0 * kotlin.math.cos(Math.toRadians(lat0)))

        val pinPath = pinModelFilePath
        var created = 0
        val total   = (2 * GRID_RADIUS + 1) * (2 * GRID_RADIUS + 1)

        for (latOff in -GRID_RADIUS..GRID_RADIUS) {
            for (lonOff in -GRID_RADIUS..GRID_RADIUS) {
                val lat    = lat0 + latOff * GRID_SPACING_M * dLatPerM
                val lon    = lon0 + lonOff * GRID_SPACING_M * dLonPerM
                val label  = if (latOff == 0 && lonOff == 0) "●" else "($latOff,$lonOff)"
                val dist   = kotlin.math.sqrt((latOff * latOff + lonOff * lonOff).toDouble())
                val bright = (1.0 - dist / (GRID_RADIUS * 2.0)).coerceIn(0.4, 1.0).toFloat()
                val rgba   = floatArrayOf(bright, 0.85f * bright, 0.15f, 1f)

                try {
                    // Terrain: resolveAnchorOnTerrain places the anchor at altitudeAboveTerrain = 0.0
                    // (exactly on the terrain surface); it starts PAUSED and becomes TRACKING once
                    // ARCore resolves the terrain elevation. Stored: a WGS84 anchor at the spawn
                    // fix's altitude — tracks immediately; the gap to the ground shows GNSS vs
                    // terrain height difference.
                    val anchor = if (useTerrainNow)
                        earth.resolveAnchorOnTerrain(lat, lon, 0.0, 0f, 0f, 0f, 1f)
                    else
                        earth.createAnchor(lat, lon, storedAlt!!, 0f, 0f, 0f, 1f)
                    anchors += TestAnchorEntry(
                        anchor   = anchor,
                        modelKey = if (pinPath != null) gridKey(latOff, lonOff) else null,
                        label    = label,
                        rgba     = rgba
                    )
                    created++
                } catch (e: Exception) {
                    Log.e(TAG, "Test anchor creation failed for $label (terrain=$useTerrainNow)", e)
                }
            }
        }

        isActive = true
        activeMode = if (useTerrainNow) "TERRAIN" else "STORED"
        DiagnosticsLogger.i(ArFilamentRenderer.DIAG,
            "Test grid spawned: $created/$total ${activeMode} anchors spacing=${GRID_SPACING_M}m " +
            "at ${"%.6f".format(lat0)}, ${"%.6f".format(lon0)} " +
            "altM=${storedAlt?.let { "%.2f".format(it) } ?: "terrain"} altSource=$altSource")
    }

    private fun clearGrid() {
        for (te in anchors) try { te.anchor.detach() } catch (_: Exception) {}
        anchors.clear()
        isActive = false
        activeMode = null
    }
}

