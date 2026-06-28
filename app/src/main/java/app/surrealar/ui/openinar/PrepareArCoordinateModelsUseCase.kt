package app.surrealar.ui.openinar

import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity
import app.surrealar.domain.model.CoordinateModelLink
import javax.inject.Inject

/**
 * Pure preparation of AR-ready coordinate/model data, extracted from [OpenInARViewModel] so it can be
 * unit-tested without a database.
 *
 * For each coordinate it resolves the linked model id (new `modelId` column or the legacy
 * `icon = "model:<id>"` convention via [CoordinateModelLink]), looks up that model's file path, and
 * resolves the effective [ModelPlacement] (per-coordinate override → model default → identity). Every
 * coordinate is returned — `renderEnabled` is intentionally **not** filtered here; the renderer
 * consults `coordinate.renderEnabled` at draw time so disabling render keeps the pin but hides the
 * model. A linked model that is missing locally yields a null `modelFilePath` rather than being dropped.
 */
class PrepareArCoordinateModelsUseCase @Inject constructor() {

    operator fun invoke(
        coordinates: List<CoordinateEntity>,
        models: List<ModelEntity>,
    ): List<CoordWithModel> {
        val modelIndex: Map<String, ModelEntity> = models.associateBy { it.id }
        return coordinates.map { entity ->
            val modelId = CoordinateModelLink.resolveModelId(entity.modelId, entity.icon)
            val model = modelId?.let { modelIndex[it] }
            CoordWithModel(
                coordinate = entity,
                modelId = modelId,
                modelFilePath = model?.filePath,
                placement = ModelPlacement.resolve(entity, model),
            )
        }
    }
}
