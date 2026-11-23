package io.github.pennstatecs.geokit.ar.model

import io.github.pennstatecs.geokit.ar.frame.ArFrames
import kotlin.math.cos
import kotlin.math.sin

/**
 * The full model correction the AR renderer applies on top of the anchor's world matrix:
 *
 *   correction = T(nudge) · Ry(yaw) · Rx(pitch) · Rz(roll) · cm
 *   cm         = R(180°Y) · S(effScale) · T(−anchorPoint)          ([ModelCorrectionMatrix.base])
 *   world      = anchorWorld · correction
 *
 * with anchorPoint / nudge from [ModelBounds.resolveOffsets] and the "move model" shift folded into the
 * nudge in the anchor's East-Up-South frame ([nudge]). All matrices are column-major float[16]; the
 * rotation and multiplication follow `android.opengl.Matrix` (`setRotateM` / `rotateM` / `translateM` /
 * `multiplyMM`) operation for operation, so the renderer's output does not change.
 */
public object ModelTransform {

    /**
     * Bounding-box half-extent (metres) above which a model is assumed to be in non-metric units
     * (mm/cm) or to carry absolute projected coordinates, and is normalised to a 1-metre reference
     * size. 2 km (a 4 km model) is far beyond any real site or building, so true-scale models are never
     * shrunk. Single source for the renderer and the readiness preview.
     */
    public const val NORMALIZE_HALF_EXTENT_M: Float = 2000.0f

    /** Unit normalisation factor for a model of [maxHalfExtent]: 1 unless it is implausibly large. */
    public fun normalizationScale(maxHalfExtent: Float): Float =
        if (maxHalfExtent > NORMALIZE_HALF_EXTENT_M) 1.0f / (maxHalfExtent * 2f) else 1.0f

    /**
     * The outermost translation in anchor-frame (East-Up-South) metres: the placement nudge plus the
     * "move model" shift (east = +X, up = +Y, north = −Z; see [ArFrames]).
     */
    public fun nudge(offsets: PlacementOffsets, placement: ModelPlacement): Vec3 = Vec3(
        offsets.nudge.x + placement.moveEastM,
        offsets.nudge.y + placement.moveUpM,
        offsets.nudge.z - placement.moveNorthM,
    )

    /**
     * `T(nudge) · Ry(yaw) · Rx(pitch) · Rz(roll) · cm`. Returns [cm] itself (same instance) when there
     * is no rotation and no nudge, exactly as the renderer did.
     */
    public fun compose(
        cm: FloatArray,
        yawDeg: Float, pitchDeg: Float, rollDeg: Float,
        nudgeX: Float, nudgeY: Float, nudgeZ: Float,
    ): FloatArray {
        val hasRot = yawDeg != 0f || pitchDeg != 0f || rollDeg != 0f
        val hasNudge = nudgeX != 0f || nudgeY != 0f || nudgeZ != 0f
        if (!hasRot && !hasNudge) return cm
        var rot = ArFrames.identity()
        rot = rotate(rot, yawDeg, 0f, 1f, 0f)   // yaw about Up (Y)
        rot = rotate(rot, pitchDeg, 1f, 0f, 0f) // pitch about East (X)
        rot = rotate(rot, rollDeg, 0f, 0f, 1f)  // roll about Z
        val rc = ArFrames.multiplyMM(rot, cm)
        val trans = ArFrames.identity()
        translate(trans, nudgeX, nudgeY, nudgeZ)
        return ArFrames.multiplyMM(trans, rc)
    }

    /**
     * Inverse of [compose] ∘ [ModelCorrectionMatrix.base] for a point: maps an anchor-frame point back
     * into model-local coordinates, given the same anchor point, nudge, angles and effective scale.
     * A zero [effScale] is treated as 1. Callers choose the nudge; the readiness preview passes the
     * placement nudge without the move shift (known gap: it previews the saved placement, not moves).
     */
    public fun toLocal(
        world: Vec3,
        anchorPoint: Vec3,
        nudge: Vec3,
        yawDeg: Float, pitchDeg: Float, rollDeg: Float,
        effScale: Float,
    ): Vec3 {
        var x = world.x - nudge.x
        var y = world.y - nudge.y
        var z = world.z - nudge.z
        // R⁻¹ = Rz(-roll) · Rx(-pitch) · Ry(-yaw): undo yaw first, then pitch, then roll.
        rotY(-yawDeg, x, y, z).let { x = it[0]; y = it[1]; z = it[2] }
        rotX(-pitchDeg, x, y, z).let { x = it[0]; y = it[1]; z = it[2] }
        rotZ(-rollDeg, x, y, z).let { x = it[0]; y = it[1]; z = it[2] }
        val s = if (effScale != 0f) effScale else 1f
        val ap = anchorPoint
        // Inverse of R(180°Y)·S·T(-ap): (x,y,z) -> (ap.x - x/s, ap.y + y/s, ap.z - z/s)
        return Vec3(ap.x - x / s, ap.y + y / s, ap.z - z / s)
    }

    // ── android.opengl.Matrix equivalents ───────────────────────────────────────────────────────

    /** `Matrix.setRotateM` for the three unit axes the renderer uses (others are not supported). */
    internal fun rotation(aDeg: Float, x: Float, y: Float, z: Float): FloatArray {
        val rm = FloatArray(16)
        rm[15] = 1f
        val a = aDeg * (Math.PI / 180.0f).toFloat()
        val s = sin(a.toDouble()).toFloat()
        val c = cos(a.toDouble()).toFloat()
        when {
            x == 1f && y == 0f && z == 0f -> {
                rm[5] = c; rm[10] = c
                rm[6] = s; rm[9] = -s
                rm[0] = 1f
            }
            x == 0f && y == 1f && z == 0f -> {
                rm[0] = c; rm[10] = c
                rm[8] = s; rm[2] = -s
                rm[5] = 1f
            }
            x == 0f && y == 0f && z == 1f -> {
                rm[0] = c; rm[5] = c
                rm[1] = s; rm[4] = -s
                rm[10] = 1f
            }
            else -> throw IllegalArgumentException("only unit X, Y or Z axes are supported")
        }
        return rm
    }

    /** `Matrix.rotateM`: m · R(a about axis). */
    private fun rotate(m: FloatArray, aDeg: Float, x: Float, y: Float, z: Float): FloatArray =
        ArFrames.multiplyMM(m, rotation(aDeg, x, y, z))

    /** `Matrix.translateM` in place: m · T(x, y, z). */
    private fun translate(m: FloatArray, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) {
            m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z
        }
    }

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble())

    // Right-handed rotations of a point, same convention as android.opengl.Matrix.rotateM.
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
