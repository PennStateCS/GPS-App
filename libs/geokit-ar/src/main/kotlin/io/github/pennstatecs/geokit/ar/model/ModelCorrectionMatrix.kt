package io.github.pennstatecs.geokit.ar.model

import io.github.pennstatecs.geokit.ar.frame.ArFrames

/**
 * The renderer's base model correction: R(180°Y) · S(scale) · T(−anchorPoint), column-major.
 * R(180°Y) turns a model whose glTF +Z is north so that north lands on the anchor frame's
 * East-Up-South −Z (and model −X lands on +X = east). See [ArFrames].
 */
public object ModelCorrectionMatrix {

    public fun base(effScale: Float, apX: Float, apY: Float, apZ: Float): FloatArray = FloatArray(16).also { cm ->
        cm[0] = -effScale; cm[5] = effScale; cm[10] = -effScale; cm[15] = 1.0f
        cm[12] = effScale * apX
        cm[13] = -effScale * apY
        cm[14] = effScale * apZ
    }

    /** Applies a column-major 4×4 matrix to a point (w = 1). Same as [ArFrames.transformPoint]. */
    public fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        ArFrames.transformPoint(m, x, y, z)
}
