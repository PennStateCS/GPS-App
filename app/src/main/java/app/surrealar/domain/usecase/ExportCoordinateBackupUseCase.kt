package app.surrealar.domain.usecase

import app.surrealar.data.export.CoordinateBackup
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Builds the official full JSON backup. Loads every saved coordinate and model from the repositories
 * and serializes them through [CoordinateBackup] (full-fidelity coordinate data plus model metadata —
 * not the model files). Returns the content and counts; writing it to a `Uri`/file stays in the UI
 * layer, which owns the Storage Access Framework.
 */
class ExportCoordinateBackupUseCase @Inject constructor(
    private val coordinateRepository: CoordinateRepository,
    private val modelRepository: ModelRepository,
) {

    /** The serialized backup plus a short summary for the UI message. */
    data class Result(val json: String, val coordinateCount: Int, val modelCount: Int)

    suspend operator fun invoke(appVersion: String?): Result = withContext(Dispatchers.IO) {
        val coordinates = coordinateRepository.getAllCoordinatesList()
        val models = modelRepository.getAllModels().first()
        val json = CoordinateBackup.export(coordinates, models, appVersion)
        Result(json = json, coordinateCount = coordinates.size, modelCount = models.size)
    }
}
