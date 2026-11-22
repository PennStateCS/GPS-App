package app.surrealar.ui.models.readiness

import app.surrealar.ui.openinar.ModelBounds
import app.surrealar.ui.openinar.ModelCorrectionMatrix
import app.surrealar.ui.openinar.ModelPlacement
import app.surrealar.ui.openinar.ModelPlacementOrigin
import app.surrealar.ui.openinar.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ArPlacementPreviewTest {

    private val bounds = ModelBounds.fromMinMax(2f, 1f, -3f, 6f, 5f, 1f) // center (4,3,-1), bottom (4,1,-1)

    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
        assertEquals(expected.z, actual.z, eps)
    }

    @Test
    fun centerPresetPutsBoundsCenterOnThePoint() {
        val p = ArPlacementPreview(bounds, ModelPlacement())
        assertVec(bounds.center, p.surveyedPointLocal)
    }

    @Test
    fun bottomCenterWithLiftPutsPointBelowTheBase() {
        val p = ArPlacementPreview(
            bounds, ModelPlacement(placementOrigin = ModelPlacementOrigin.BOTTOM_CENTER, verticalOffsetM = 1f),
        )
        assertVec(Vec3(4f, 0f, -1f), p.surveyedPointLocal)
    }

    @Test
    fun scaleShrinksDistancesInModelUnits() {
        val p = ArPlacementPreview(
            bounds, ModelPlacement(placementOrigin = ModelPlacementOrigin.ORIGIN, verticalOffsetM = 1f, scale = 2f),
        )
        // Raised 1 world metre = 0.5 model units at scale 2.
        assertVec(Vec3(0f, -0.5f, 0f), p.surveyedPointLocal)
    }

    /** Forward transform built independently, the way ArFilamentRenderer composes it. */
    private fun forward(p: ArPlacementPreview, local: Vec3): Vec3 {
        val pl = p.placement
        val ap = p.anchorPoint
        val cm = ModelCorrectionMatrix.base(p.effScale, ap.x, ap.y, ap.z)
        val q = ModelCorrectionMatrix.transformPoint(cm, local.x, local.y, local.z)
        val r = mul(mul(ry(pl.yawDeg), rx(pl.pitchDeg)), rz(pl.rollDeg))
        val w = apply(r, q)
        val nudge = bounds.resolveOffsets(pl.placementOrigin,
            Vec3(pl.originOffsetXM, pl.originOffsetYM, pl.originOffsetZM), pl.verticalOffsetM).nudge
        return Vec3(w[0] + nudge.x, w[1] + nudge.y, w[2] + nudge.z)
    }

    @Test
    fun toLocalInvertsTheRendererTransform() {
        val p = ArPlacementPreview(
            bounds,
            ModelPlacement(
                scale = 1.5f, yawDeg = 30f, pitchDeg = -90f, rollDeg = 10f, verticalOffsetM = 0.4f,
                originOffsetXM = 0.3f, originOffsetYM = -0.2f, originOffsetZM = 0.7f,
                placementOrigin = ModelPlacementOrigin.BOTTOM_CENTER,
            ),
        )
        for (w in listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, -2f), Vec3(-3f, 2f, 5f))) {
            assertVec(w, forward(p, p.toLocal(w)), 1e-3f)
        }
    }

    @Test
    fun placementForFallsBackToModelDefaultsAndCenter() {
        assertEquals(ModelPlacementOrigin.CENTER, ArPlacementPreview.placementFor(null, null).placementOrigin)
        val link = LinkFacts("c", "Point 5", "BOTTOM_CENTER", null, 15.0, null, null, 0.5, null, null, null,
            null, null, 0.0, 0.0)
        val pl = ArPlacementPreview.placementFor(link, null)
        assertEquals(ModelPlacementOrigin.BOTTOM_CENTER, pl.placementOrigin)
        assertEquals(1f, pl.scale, 0f)
        assertEquals(15f, pl.yawDeg, 0f)
        assertEquals(0.5f, pl.verticalOffsetM, 0f)
    }

    // ── tiny 3×3 helpers (row-major) ──
    private fun rad(d: Float) = Math.toRadians(d.toDouble())
    private fun ry(d: Float): Array<DoubleArray> { val c = cos(rad(d)); val s = sin(rad(d))
        return arrayOf(doubleArrayOf(c, 0.0, s), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-s, 0.0, c)) }
    private fun rx(d: Float): Array<DoubleArray> { val c = cos(rad(d)); val s = sin(rad(d))
        return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, -s), doubleArrayOf(0.0, s, c)) }
    private fun rz(d: Float): Array<DoubleArray> { val c = cos(rad(d)); val s = sin(rad(d))
        return arrayOf(doubleArrayOf(c, -s, 0.0), doubleArrayOf(s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0)) }
    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * b[k][j] } } }
    private fun apply(m: Array<DoubleArray>, v: FloatArray) =
        FloatArray(3) { i -> (m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]).toFloat() }
}
