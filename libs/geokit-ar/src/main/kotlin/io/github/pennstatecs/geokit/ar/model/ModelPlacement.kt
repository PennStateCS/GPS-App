package io.github.pennstatecs.geokit.ar.model

/**
 * Resolved per-placement transform applied to a linked model in AR, on top of the renderer's
 * automatic centering/normalization.
 *
 * Values are already resolved (per-coordinate override → model default → hard default) so the
 * renderer can apply them directly. The default instance is the identity, for which the renderer
 * reproduces its previous behavior exactly. The app adds `ModelPlacement.resolve(coordinate, model)`
 * as an extension on [Companion] (it needs the app's database entities).
 */
public data class ModelPlacement(
    /** Multiplies the renderer's auto-normalized scale. */
    val scale: Float = 1f,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val verticalOffsetM: Float = 0f,
    val originOffsetXM: Float = 0f,
    val originOffsetYM: Float = 0f,
    val originOffsetZM: Float = 0f,
    /**
     * Which model-local point aligns to the AR anchor. Defaults to [ModelPlacementOrigin.CENTER] —
     * the renderer's historical behavior (bounding-box center at the anchor) — so existing models do
     * not shift. Large structure GLBs whose geometry is far from the origin should use BOTTOM_CENTER.
     */
    val placementOrigin: ModelPlacementOrigin = ModelPlacementOrigin.CENTER,
    /**
     * "Move model" shift of the whole rendered model, metres east / north / up of the surveyed point,
     * in the world frame (applied last, so it is unaffected by scale, rotation and origin). Saved moves
     * come from the coordinate; session-only moves are added in AR and never stored.
     */
    val moveEastM: Float = 0f,
    val moveNorthM: Float = 0f,
    val moveUpM: Float = 0f,
) {
    /** True when this placement changes nothing — lets the renderer skip the extra matrix math. */
    val isIdentity: Boolean
        get() = scale == 1f && yawDeg == 0f && pitchDeg == 0f && rollDeg == 0f &&
            verticalOffsetM == 0f && originOffsetXM == 0f && originOffsetYM == 0f && originOffsetZM == 0f &&
            moveEastM == 0f && moveNorthM == 0f && moveUpM == 0f

    /** This placement with [m] added on top (a session-only move over the saved placement). */
    public operator fun plus(m: ModelMove): ModelPlacement = if (m.isZero) this else copy(
        moveEastM = moveEastM + m.eastM, moveNorthM = moveNorthM + m.northM, moveUpM = moveUpM + m.upM,
        yawDeg = yawDeg + m.yawDeg,
    )

    /** Hook for extensions such as the app's `resolve(coordinate, model)`. */
    public companion object
}

/**
 * A move made in AR: metres east / north / up and a yaw change (degrees, positive = counter-clockwise
 * seen from above, the renderer's yaw convention). Session-only moves live as one of these in the AR
 * screen; saving folds it into the coordinate's modelMove* fields and modelYawDeg.
 */
public data class ModelMove(
    val eastM: Float = 0f,
    val northM: Float = 0f,
    val upM: Float = 0f,
    val yawDeg: Float = 0f,
) {
    val isZero: Boolean get() = eastM == 0f && northM == 0f && upM == 0f && yawDeg == 0f
    public operator fun plus(o: ModelMove): ModelMove = ModelMove(eastM + o.eastM, northM + o.northM, upM + o.upM, yawDeg + o.yawDeg)
}
