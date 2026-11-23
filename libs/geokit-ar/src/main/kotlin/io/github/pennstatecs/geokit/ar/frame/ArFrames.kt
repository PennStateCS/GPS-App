package io.github.pennstatecs.geokit.ar.frame

/**
 * The frame conventions the AR maths relies on, in one place.
 *
 * - **ARCore world frame**: metres, right-handed, +Y up (against gravity). Its horizontal axes are
 *   arbitrary per session.
 * - **Geospatial anchor frame**: an anchor created with an identity rotation (as the app does) has
 *   East-Up-South axes: east = +X, up = +Y, **north = −Z**. A model's world matrix is
 *   `anchorWorld · correction`, so everything in the correction is expressed in this frame.
 * - **glTF model frame**: metres, +Y up. The app treats glTF +Z as north; the base correction turns it
 *   with R(180° about Y) so model +Z lands on anchor −Z (north) and model −X on anchor +X (east).
 * - **Matrices**: column-major `FloatArray(16)` as ARCore and OpenGL give them; element (row r,
 *   column c) is `m[c * 4 + r]`, translation is `m[12..14]`.
 * - **Screen**: pixels, origin top-left, y down. NDC: x right, y up, both −1..1.
 *
 * Every helper keeps the exact float expression of the code it replaced, so results are bit-identical.
 */
public object ArFrames {

    /** Anchor-frame (East-Up-South) vector → metres east / north / up. */
    public fun anchorToEnu(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(x, -z, y)

    /** Metres east / north / up → anchor-frame (East-Up-South) x, y, z. */
    public fun enuToAnchor(eastM: Float, northM: Float, upM: Float): FloatArray = floatArrayOf(eastM, upM, -northM)

    /**
     * A world-frame direction expressed in the anchor frame: `Rᵀ · d`, where R is the rotation part of
     * [anchorWorld] (column-major; its columns are the anchor's X, Y, Z axes in world).
     */
    public fun worldDirToAnchor(anchorWorld: FloatArray, dx: Float, dy: Float, dz: Float): FloatArray = floatArrayOf(
        anchorWorld[0] * dx + anchorWorld[1] * dy + anchorWorld[2] * dz,
        anchorWorld[4] * dx + anchorWorld[5] * dy + anchorWorld[6] * dz,
        anchorWorld[8] * dx + anchorWorld[9] * dy + anchorWorld[10] * dz,
    )

    /** A world-frame displacement as metres east / north / up through [anchorWorld] (see [worldDirToAnchor]). */
    public fun worldDeltaToEnu(anchorWorld: FloatArray, dx: Float, dy: Float, dz: Float): FloatArray {
        val a = worldDirToAnchor(anchorWorld, dx, dy, dz)
        return anchorToEnu(a[0], a[1], a[2])
    }

    /** Column-major 4×4 matrix applied to a point (w = 1); returns x, y, z. */
    public fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    /**
     * Column-major 4×4 matrix times a 4-vector, summed in the same order as
     * `android.opengl.Matrix.multiplyMV`.
     */
    public fun multiplyMV(m: FloatArray, v: FloatArray): FloatArray {
        val x = v[0]; val y = v[1]; val z = v[2]; val w = v[3]
        return floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12] * w,
            m[1] * x + m[5] * y + m[9] * z + m[13] * w,
            m[2] * x + m[6] * y + m[10] * z + m[14] * w,
            m[3] * x + m[7] * y + m[11] * z + m[15] * w,
        )
    }

    /**
     * Column-major `lhs · rhs`, accumulated in the same order as Android's native `Matrix.multiplyMM`
     * (r[i][k] = Σⱼ lhs[j][k]·rhs[i][j], j = 0..3, indices as column, row).
     */
    public fun multiplyMM(lhs: FloatArray, rhs: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 4) {
            val rhsI0 = rhs[i * 4]
            var r0 = lhs[0] * rhsI0
            var r1 = lhs[1] * rhsI0
            var r2 = lhs[2] * rhsI0
            var r3 = lhs[3] * rhsI0
            for (j in 1 until 4) {
                val rhsIj = rhs[i * 4 + j]
                r0 += lhs[j * 4] * rhsIj
                r1 += lhs[j * 4 + 1] * rhsIj
                r2 += lhs[j * 4 + 2] * rhsIj
                r3 += lhs[j * 4 + 3] * rhsIj
            }
            r[i * 4] = r0; r[i * 4 + 1] = r1; r[i * 4 + 2] = r2; r[i * 4 + 3] = r3
        }
        return r
    }

    /** A new column-major identity matrix. */
    public fun identity(): FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** NDC x → screen pixels for a view [widthPx] wide. */
    public fun ndcToScreenX(ndcX: Float, widthPx: Int): Float = (ndcX + 1f) * 0.5f * widthPx

    /** NDC y (up) → screen pixels (down) for a view [heightPx] high. */
    public fun ndcToScreenY(ndcY: Float, heightPx: Int): Float = (1f - ndcY) * 0.5f * heightPx

    /** Screen x pixels → NDC x for a view [widthPx] wide. */
    public fun screenToNdcX(xPx: Float, widthPx: Int): Float = 2f * xPx / widthPx - 1f

    /** Screen y pixels (down) → NDC y (up) for a view [heightPx] high. */
    public fun screenToNdcY(yPx: Float, heightPx: Int): Float = 1f - 2f * yPx / heightPx
}
