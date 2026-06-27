package app.surrealar.ui.openinar

import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity

/**
 * Resolved per-placement transform applied to a linked model in AR, on top of the renderer's
 * automatic centering/normalization.
 *
 * Values are already resolved (per-coordinate override → model default → hard default) so the
 * renderer can apply them directly. The default instance is the identity, for which the renderer
 * reproduces its previous behavior exactly.
 */
data class ModelPlacement(
    /** Multiplies the renderer's auto-normalized scale. */
    val scale: Float = 1f,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val verticalOffsetM: Float = 0f,
    val originOffsetXM: Float = 0f,
    val originOffsetYM: Float = 0f,
    val originOffsetZM: Float = 0f
) {
    /** True when this placement changes nothing — lets the renderer skip the extra matrix math. */
    val isIdentity: Boolean
        get() = scale == 1f && yawDeg == 0f && pitchDeg == 0f && rollDeg == 0f &&
            verticalOffsetM == 0f && originOffsetXM == 0f && originOffsetYM == 0f && originOffsetZM == 0f

    companion object {
        /**
         * Effective placement = per-coordinate override, else the model's default, else the hard
         * default (scale 1.0, no rotation/offset). Returns identity when no model is linked.
         */
        fun resolve(coordinate: CoordinateEntity, model: ModelEntity?): ModelPlacement {
            if (model == null) return ModelPlacement()
            return ModelPlacement(
                scale           = (coordinate.modelScale ?: model.defaultScale).toFloat(),
                yawDeg          = (coordinate.modelYawDeg ?: model.defaultYawDeg).toFloat(),
                pitchDeg        = (coordinate.modelPitchDeg ?: 0.0).toFloat(),
                rollDeg         = (coordinate.modelRollDeg ?: 0.0).toFloat(),
                verticalOffsetM = (coordinate.modelVerticalOffsetM ?: 0.0).toFloat(),
                originOffsetXM  = (coordinate.modelOriginOffsetXM ?: model.originOffsetXM).toFloat(),
                originOffsetYM  = (coordinate.modelOriginOffsetYM ?: model.originOffsetYM).toFloat(),
                originOffsetZM  = (coordinate.modelOriginOffsetZM ?: model.originOffsetZM).toFloat()
            )
        }
    }
}
