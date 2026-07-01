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

    private enum class Action { NONE, SPAWN, CLEAR }

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

    /** Signal that the test grid should be spawned on the next GL frame. */
    fun requestSpawn() { action = Action.SPAWN }

    /** Signal that the test grid should be cleared on the next GL frame. */
    fun requestClear() { action = Action.CLEAR }

    // ── GL-thread API ─────────────────────────────────────────────────────────

    /**
     * Must be called from the GL thread (inside `onDrawFrame`).
     * Processes any pending spawn/clear request using [earth] and the current GNSS position.
     */
    fun processActionIfNeeded(earth: Earth, gnssLat: Double, gnssLon: Double) {
        when (action) {
            Action.SPAWN -> {
                action = Action.NONE
                spawnGrid(earth, gnssLat, gnssLon)
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
                val scaledWorld = model.copyOf()
                Matrix.scaleM(scaledWorld, 0, PIN_SCALE, PIN_SCALE, PIN_SCALE)
                scaledWorld[13] -= PIN_GROUND_DROP_M   // lower tip to ground
                newModelPoses += ArFilamentRenderer.ModelPose(
                    key         = te.modelKey,
                    worldMatrix = scaledWorld,
                    filePath    = testPinPath
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

    private fun spawnGrid(earth: Earth, lat0: Double, lon0: Double) {
        clearGrid()

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
                    // resolveAnchorOnTerrain places the anchor at altitudeAboveTerrain = 0.0
                    // (exactly on the terrain surface). The anchor starts as TrackingState.PAUSED
                    // and transitions to TRACKING once ARCore resolves the terrain elevation.
                    val anchor = earth.resolveAnchorOnTerrain(lat, lon, 0.0, 0f, 0f, 0f, 1f)
                    anchors += TestAnchorEntry(
                        anchor   = anchor,
                        modelKey = if (pinPath != null) gridKey(latOff, lonOff) else null,
                        label    = label,
                        rgba     = rgba
                    )
                    created++
                } catch (e: Exception) {
                    Log.e(TAG, "resolveAnchorOnTerrain failed for $label", e)
                }
            }
        }

        isActive = true
        DiagnosticsLogger.i(ArFilamentRenderer.DIAG,
            "Test grid spawned: $created/$total terrain anchors spacing=${GRID_SPACING_M}m " +
            "at ${"%.6f".format(lat0)}, ${"%.6f".format(lon0)}")
    }

    private fun clearGrid() {
        for (te in anchors) try { te.anchor.detach() } catch (_: Exception) {}
        anchors.clear()
        isActive = false
    }
}

