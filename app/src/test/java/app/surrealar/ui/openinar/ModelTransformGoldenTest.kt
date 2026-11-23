package app.surrealar.ui.openinar

import android.opengl.Matrix
import app.surrealar.ui.models.readiness.ArPlacementPreview
import io.github.pennstatecs.geokit.ar.model.ModelBounds
import io.github.pennstatecs.geokit.ar.model.ModelCorrectionMatrix
import io.github.pennstatecs.geokit.ar.model.ModelPlacement
import io.github.pennstatecs.geokit.ar.model.ModelPlacementOrigin
import io.github.pennstatecs.geokit.ar.model.ModelTransform
import io.github.pennstatecs.geokit.ar.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * GOLDEN test for the geokit-ar model transform: [ModelTransform.compose] (pure Kotlin) must reproduce
 * the exact android.opengl.Matrix sequence ArFilamentRenderer used before the move, for many
 * placements. Robolectric supplies the real android.opengl.Matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelTransformGoldenTest {

    /** The renderer's pre-geokit correction, copied verbatim (ArFilamentRenderer, model placement). */
    private fun oldRendererCorrection(
        bounds: ModelBounds, placement: ModelPlacement, coordScale: Float, maxHalfExtent: Float,
    ): FloatArray {
        val ns = if (maxHalfExtent > 2000.0f) 1.0f / (maxHalfExtent * 2f) else 1.0f
        val customOffset = Vec3(placement.originOffsetXM, placement.originOffsetYM, placement.originOffsetZM)
        val offsets = bounds.resolveOffsets(placement.placementOrigin, customOffset, placement.verticalOffsetM)
        val ap = offsets.anchorPoint
        val effScale = ns * placement.scale * coordScale
        val cm = ModelCorrectionMatrix.base(effScale, ap.x, ap.y, ap.z)
        val nudgeX = offsets.nudge.x + placement.moveEastM
        val nudgeY = offsets.nudge.y + placement.moveUpM
        val nudgeZ = offsets.nudge.z - placement.moveNorthM
        val hasRot = placement.yawDeg != 0f || placement.pitchDeg != 0f || placement.rollDeg != 0f
        val hasNudge = nudgeX != 0f || nudgeY != 0f || nudgeZ != 0f
        return if (!hasRot && !hasNudge) {
            cm
        } else {
            val rot = FloatArray(16); Matrix.setIdentityM(rot, 0)
            Matrix.rotateM(rot, 0, placement.yawDeg,   0f, 1f, 0f)
            Matrix.rotateM(rot, 0, placement.pitchDeg, 1f, 0f, 0f)
            Matrix.rotateM(rot, 0, placement.rollDeg,  0f, 0f, 1f)
            val rc = FloatArray(16); Matrix.multiplyMM(rc, 0, rot, 0, cm, 0)
            val trans = FloatArray(16); Matrix.setIdentityM(trans, 0)
            Matrix.translateM(trans, 0, nudgeX, nudgeY, nudgeZ)
            FloatArray(16).also { Matrix.multiplyMM(it, 0, trans, 0, rc, 0) }
        }
    }

    /** The renderer's new path (what ArFilamentRenderer now calls). */
    private fun newRendererCorrection(
        bounds: ModelBounds, placement: ModelPlacement, coordScale: Float, maxHalfExtent: Float,
    ): FloatArray {
        val ns = ModelTransform.normalizationScale(maxHalfExtent)
        val customOffset = Vec3(placement.originOffsetXM, placement.originOffsetYM, placement.originOffsetZM)
        val offsets = bounds.resolveOffsets(placement.placementOrigin, customOffset, placement.verticalOffsetM)
        val ap = offsets.anchorPoint
        val effScale = ns * placement.scale * coordScale
        val cm = ModelCorrectionMatrix.base(effScale, ap.x, ap.y, ap.z)
        val n = ModelTransform.nudge(offsets, placement)
        return ModelTransform.compose(cm, placement.yawDeg, placement.pitchDeg, placement.rollDeg, n.x, n.y, n.z)
    }

    private class Model(val bounds: ModelBounds) {
        val maxHalfExtent: Float = maxOf(bounds.size.x, bounds.size.y, bounds.size.z) / 2f
    }

    private val models = listOf(
        Model(ModelBounds.fromCenterHalfExtent(0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f)),                       // 1 m cube
        Model(ModelBounds.fromMinMax(43.56f, 132.13f, -72.29f, 52.74f, 137.16f, -64.15f)),            // Model1_local
        Model(ModelBounds.fromCenterHalfExtent(-1200f, 30f, 850f, 300f, 40f, 250f)),                   // large site
        Model(ModelBounds.fromCenterHalfExtent(5000f, 100f, -3000f, 2500f, 100f, 2400f)),              // normalised (mm units)
    )

    private val angles = listOf(0f, 90f, 180f, -90f, 270f, 37.25f, -127.9f)
    private val placements: List<Pair<ModelPlacement, Float>> = buildList {
        for (origin in ModelPlacementOrigin.entries) for (yaw in angles) for (pitch in listOf(0f, 90f, -12.5f))
            for (roll in listOf(0f, 180f, 4.75f)) {
                add(ModelPlacement(yawDeg = yaw, pitchDeg = pitch, rollDeg = roll, placementOrigin = origin) to 1f)
                add(ModelPlacement(
                    scale = 2.5f, yawDeg = yaw, pitchDeg = pitch, rollDeg = roll, verticalOffsetM = -0.35f,
                    originOffsetXM = 1.25f, originOffsetYM = -0.5f, originOffsetZM = 3f, placementOrigin = origin,
                    moveEastM = 0.8f, moveNorthM = -2.2f, moveUpM = 0.15f,
                ) to 0.5f)
                add(ModelPlacement(
                    scale = 0.001f, yawDeg = yaw, pitchDeg = pitch, rollDeg = roll,
                    originOffsetXM = -150f, originOffsetYM = 20f, originOffsetZM = 75f, placementOrigin = origin,
                    moveNorthM = 12f,
                ) to 3f)
            }
        // no rotation, nudge only / move only / nothing
        add(ModelPlacement(verticalOffsetM = 1f) to 1f)
        add(ModelPlacement(moveEastM = 5f, moveNorthM = 5f) to 1f)
        add(ModelPlacement(placementOrigin = ModelPlacementOrigin.BOTTOM_CENTER) to 1f)
        add(ModelPlacement() to 2f)
    }

    @Test
    fun libraryCompose_matchesAndroidMatrixSequence() {
        var compared = 0
        var maxErr = 0.0
        for (model in models) for ((placement, coordScale) in placements) {
            val old = oldRendererCorrection(model.bounds, placement, coordScale, model.maxHalfExtent)
            val new = newRendererCorrection(model.bounds, placement, coordScale, model.maxHalfExtent)
            for (i in 0 until 16) {
                val tol = 1e-5 * maxOf(1.0, abs(old[i].toDouble()))
                val err = abs(old[i].toDouble() - new[i].toDouble())
                maxErr = maxOf(maxErr, err / maxOf(1.0, abs(old[i].toDouble())))
                assertTrue("element $i of $placement on ${model.bounds}: old=${old[i]} new=${new[i]}", err <= tol)
            }
            compared++
        }
        assertTrue(compared > 1000)
        println("ModelTransformGoldenTest: $compared placements, max scaled error = $maxErr")
    }

    @Test
    fun noRotationNoNudge_returnsTheBaseCorrection() {
        val cm = ModelCorrectionMatrix.base(1f, 4f, 3f, -1f)
        assertSame(cm, ModelTransform.compose(cm, 0f, 0f, 0f, 0f, 0f, 0f))
    }

    @Test
    fun normalisationConstant_isTheRendererValue() {
        assertEquals(2000.0f, ModelTransform.NORMALIZE_HALF_EXTENT_M, 0f)
        assertEquals(ModelTransform.NORMALIZE_HALF_EXTENT_M, ArPlacementPreview.NORMALIZE_THRESHOLD_M, 0f)
    }

    @Test
    fun previewInverse_matchesLibraryToLocal() {
        val bounds = ModelBounds.fromMinMax(2f, 1f, -3f, 6f, 5f, 1f)
        for (origin in ModelPlacementOrigin.entries) for (yaw in angles) for (pitch in listOf(0f, 33f)) {
            val pl = ModelPlacement(scale = 1.5f, yawDeg = yaw, pitchDeg = pitch, rollDeg = -7f,
                verticalOffsetM = 0.5f, originOffsetXM = 1f, placementOrigin = origin)
            val preview = ArPlacementPreview(bounds, pl)
            val offsets = bounds.resolveOffsets(origin, Vec3(pl.originOffsetXM, pl.originOffsetYM, pl.originOffsetZM), pl.verticalOffsetM)
            for (w in listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, -2f), Vec3(-3f, 2f, 5f))) {
                val a = preview.toLocal(w)
                val b = ModelTransform.toLocal(w, offsets.anchorPoint, offsets.nudge, yaw, pitch, -7f, preview.effScale)
                assertEquals(a, b)   // bit-identical
            }
        }
    }
}
