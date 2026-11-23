package app.surrealar.ui.openinar

import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity
import io.github.pennstatecs.geokit.ar.model.ModelPlacement
import io.github.pennstatecs.geokit.ar.model.ModelPlacementOrigin

/**
 * Effective placement = per-coordinate override, else the model's default, else the hard
 * default (scale 1.0, no rotation/offset). Returns identity when no model is linked.
 *
 * [ModelPlacement] itself lives in geokit-ar; this resolution stays in the app because it reads the
 * database entities.
 */
fun ModelPlacement.Companion.resolve(coordinate: CoordinateEntity, model: ModelEntity?): ModelPlacement {
    if (model == null) return ModelPlacement()
    return ModelPlacement(
        scale           = (coordinate.modelScale ?: model.defaultScale).toFloat(),
        yawDeg          = (coordinate.modelYawDeg ?: model.defaultYawDeg).toFloat(),
        pitchDeg        = (coordinate.modelPitchDeg ?: 0.0).toFloat(),
        rollDeg         = (coordinate.modelRollDeg ?: 0.0).toFloat(),
        verticalOffsetM = (coordinate.modelVerticalOffsetM ?: 0.0).toFloat(),
        originOffsetXM  = (coordinate.modelOriginOffsetXM ?: model.originOffsetXM).toFloat(),
        originOffsetYM  = (coordinate.modelOriginOffsetYM ?: model.originOffsetYM).toFloat(),
        originOffsetZM  = (coordinate.modelOriginOffsetZM ?: model.originOffsetZM).toFloat(),
        placementOrigin = ModelPlacementOrigin.from(coordinate.modelPlacementOrigin),
        moveEastM       = (coordinate.modelMoveEastM ?: 0.0).toFloat(),
        moveNorthM      = (coordinate.modelMoveNorthM ?: 0.0).toFloat(),
        moveUpM         = (coordinate.modelMoveUpM ?: 0.0).toFloat(),
    )
}
