package app.surrealar.ui.openinar

import app.surrealar.data.local.dao.CoordinateDao
import app.surrealar.data.local.dao.ModelDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The AR screen's data-loading boundary: the single place that reads persisted coordinates and model
 * metadata for AR and combines them into [CoordWithModel] values via [PrepareArCoordinateModelsUseCase].
 *
 * It exists so [OpenInARViewModel] depends on this boundary instead of the Room DAOs directly. It loads
 * and joins data only — it renders nothing; all AR rendering (and the draw-time `renderEnabled` gate)
 * stays in the AR layer.
 *
 * Behavior matches the previous in-ViewModel pipeline exactly: the stream re-emits whenever the
 * `coordinates` table changes, taking a fresh snapshot of models on each emission. A model-only change
 * does not by itself re-emit — unchanged from before.
 */
class ObserveArCoordinateModelsUseCase @Inject constructor(
    private val coordinateDao: CoordinateDao,
    private val modelDao: ModelDao,
    private val prepare: PrepareArCoordinateModelsUseCase,
) {
    operator fun invoke(): Flow<List<CoordWithModel>> =
        coordinateDao.observeAll().map { entities -> prepare(entities, modelDao.getAllModelsList()) }
}
