package app.surrealar.ui.models.readiness

import app.surrealar.domain.model.Model
import app.surrealar.ui.openinar.ModelBounds
import app.surrealar.ui.openinar.ModelPlacement
import app.surrealar.ui.openinar.ModelPlacementOrigin
import app.surrealar.ui.openinar.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where AR will put a model relative to its surveyed point, expressed in the model's own local frame
 * (the frame the viewer draws it in). Pure; mirrors ArFilamentRenderer's placement:
 *
 *   world = T(nudge) · Ry(yaw) · Rx(pitch) · Rz(roll) · R(180°Y) · S(effScale) · T(-anchorPoint) · local
 *
 * with anchorPoint/nudge from [ModelBounds.resolveOffsets] and effScale = unit normalisation × scale
 * (the global AR "model scale" setting is taken as 1). The surveyed point is world (0,0,0) and the
 * ground there is the world plane y = 0; [toLocal] maps world points back into the model frame.
 */
class ArPlacementPreview(
    val bounds: ModelBounds,
    val placement: ModelPlacement,
) {
    private val offsets = bounds.resolveOffsets(
        placement.placementOrigin,
        Vec3(placement.originOffsetXM, placement.originOffsetYM, placement.originOffsetZM),
        placement.verticalOffsetM,
    )

    /** Model-local point that AR puts on the anchor (before the nudge). */
    val anchorPoint: Vec3 get() = offsets.anchorPoint

    val maxHalfExtent: Float = maxOf(bounds.size.x, bounds.size.y, bounds.size.z) / 2f

    /** Same rule as the renderer: shrink only implausibly large (wrong-unit) models. */
    val effScale: Float = (if (maxHalfExtent > NORMALIZE_THRESHOLD_M) 1f / (maxHalfExtent * 2f) else 1f) * placement.scale

    /** The surveyed point, in model-local coordinates. */
    val surveyedPointLocal: Vec3 get() = toLocal(Vec3(0f, 0f, 0f))

    /** Maps a world point (metres, ARCore east-up-south around the surveyed point) into the model frame. */
    fun toLocal(world: Vec3): Vec3 {
        val n = offsets.nudge
        var x = world.x - n.x
        var y = world.y - n.y
        var z = world.z - n.z
        // R⁻¹ = Rz(-roll) · Rx(-pitch) · Ry(-yaw): undo yaw first, then pitch, then roll.
        rotY(-placement.yawDeg, x, y, z).let { x = it[0]; y = it[1]; z = it[2] }
        rotX(-placement.pitchDeg, x, y, z).let { x = it[0]; y = it[1]; z = it[2] }
        rotZ(-placement.rollDeg, x, y, z).let { x = it[0]; y = it[1]; z = it[2] }
        val s = if (effScale != 0f) effScale else 1f
        val ap = offsets.anchorPoint
        // Inverse of R(180°Y)·S·T(-ap): (x,y,z) -> (ap.x - x/s, ap.y + y/s, ap.z - z/s)
        return Vec3(ap.x - x / s, ap.y + y / s, ap.z - z / s)
    }

    /** Half-size (world metres) of the ground square drawn around the surveyed point. */
    fun groundHalfSizeWorldM(): Float = (maxHalfExtent * effScale * 1.25f).coerceAtLeast(0.5f)

    companion object {
        /** Matches ArFilamentRenderer.BB_NORMALIZE_THRESHOLD_M. */
        const val NORMALIZE_THRESHOLD_M = 2000f

        /**
         * The placement AR uses for [link] (per-point override → model default → hard default),
         * the same resolution as [ModelPlacement.resolve]. No link = the default CENTER preset.
         */
        fun placementFor(link: LinkFacts?, model: Model?): ModelPlacement {
            if (link == null) return ModelPlacement(scale = (model?.defaultScale ?: 1.0).toFloat())
            return ModelPlacement(
                scale = (link.scale ?: model?.defaultScale ?: 1.0).toFloat(),
                yawDeg = (link.yawDeg ?: model?.defaultYawDeg ?: 0.0).toFloat(),
                pitchDeg = (link.pitchDeg ?: 0.0).toFloat(),
                rollDeg = (link.rollDeg ?: 0.0).toFloat(),
                verticalOffsetM = (link.verticalOffsetM ?: 0.0).toFloat(),
                originOffsetXM = (link.originOffsetXM ?: model?.originOffsetXM ?: 0.0).toFloat(),
                originOffsetYM = (link.originOffsetYM ?: model?.originOffsetYM ?: 0.0).toFloat(),
                originOffsetZM = (link.originOffsetZM ?: model?.originOffsetZM ?: 0.0).toFloat(),
                placementOrigin = ModelPlacementOrigin.from(link.placementOrigin),
            )
        }

        /** "Center", "Bottom Center", "Origin", "Custom offset". */
        fun presetLabel(origin: ModelPlacementOrigin): String = when (origin) {
            ModelPlacementOrigin.ORIGIN -> "Origin"
            ModelPlacementOrigin.CENTER -> "Center"
            ModelPlacementOrigin.BOTTOM_CENTER -> "Bottom Center"
            ModelPlacementOrigin.CUSTOM -> "Custom offset"
        }

        private fun rad(deg: Float) = Math.toRadians(deg.toDouble())

        // Right-handed rotations, same convention as android.opengl.Matrix.rotateM.
        private fun rotY(deg: Float, x: Float, y: Float, z: Float): FloatArray {
            if (deg == 0f) return floatArrayOf(x, y, z)
            val c = cos(rad(deg)).toFloat(); val s = sin(rad(deg)).toFloat()
            return floatArrayOf(c * x + s * z, y, -s * x + c * z)
        }

        private fun rotX(deg: Float, x: Float, y: Float, z: Float): FloatArray {
            if (deg == 0f) return floatArrayOf(x, y, z)
            val c = cos(rad(deg)).toFloat(); val s = sin(rad(deg)).toFloat()
            return floatArrayOf(x, c * y - s * z, s * y + c * z)
        }

        private fun rotZ(deg: Float, x: Float, y: Float, z: Float): FloatArray {
            if (deg == 0f) return floatArrayOf(x, y, z)
            val c = cos(rad(deg)).toFloat(); val s = sin(rad(deg)).toFloat()
            return floatArrayOf(c * x - s * y, s * x + c * y, z)
        }
    }
}
