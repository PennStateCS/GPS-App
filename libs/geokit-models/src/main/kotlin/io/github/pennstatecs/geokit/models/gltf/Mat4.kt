package io.github.pennstatecs.geokit.models.gltf

/** Column-major 4×4 matrices (glTF convention), just enough for node transforms. */
internal object Mat4 {
    fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (col in 0..3) for (row in 0..3) {
            var s = 0.0
            for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = s
        }
        return out
    }

    /** T · R · S with R from a unit quaternion (x, y, z, w). */
    fun trs(t: DoubleArray, q: DoubleArray, s: DoubleArray): DoubleArray {
        val (x, y, z, w) = q.toList()
        val m = DoubleArray(16)
        m[0] = (1 - 2 * (y * y + z * z)) * s[0]
        m[1] = (2 * (x * y + z * w)) * s[0]
        m[2] = (2 * (x * z - y * w)) * s[0]
        m[4] = (2 * (x * y - z * w)) * s[1]
        m[5] = (1 - 2 * (x * x + z * z)) * s[1]
        m[6] = (2 * (y * z + x * w)) * s[1]
        m[8] = (2 * (x * z + y * w)) * s[2]
        m[9] = (2 * (y * z - x * w)) * s[2]
        m[10] = (1 - 2 * (x * x + y * y)) * s[2]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1.0
        return m
    }

    fun transformPoint(m: DoubleArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )
}
