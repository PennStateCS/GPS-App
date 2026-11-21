package app.surrealar.ui.openinar

/**
 * The renderer's base model correction (pure, no Android): R(180°Y) · S(scale) · T(−anchorPoint),
 * column-major. R(180°Y) turns a model whose glTF +Z is north so that north lands on ARCore's
 * east-up-south world −Z (and model −X lands on world +X = east).
 */
object ModelCorrectionMatrix {

    fun base(effScale: Float, apX: Float, apY: Float, apZ: Float): FloatArray = FloatArray(16).also { cm ->
        cm[0] = -effScale; cm[5] = effScale; cm[10] = -effScale; cm[15] = 1.0f
        cm[12] = effScale * apX
        cm[13] = -effScale * apY
        cm[14] = effScale * apZ
    }

    /** Applies a column-major 4×4 matrix to a point (w = 1). */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )
}
