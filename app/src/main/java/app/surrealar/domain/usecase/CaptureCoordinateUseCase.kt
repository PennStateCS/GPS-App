package app.surrealar.domain.usecase

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.CoordinateFactory
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.gnss.capture.CaptureResult
import app.surrealar.gnss.model.Provider
import java.util.UUID
import javax.inject.Inject

/**
 * Converts a completed [CaptureResult] into a saved [Coordinate]: builds it through
 * [CoordinateFactory], runs it through [ValidateCoordinateForSaveUseCase], and persists it via the
 * repository. The averaged result always carries a real position and altitude, so validation is a
 * defense-in-depth gate — an invalid coordinate (e.g. out-of-range or `0,0`) throws rather than being
 * written.
 *
 * The caller still resolves the GNSS [Provider], capture-method, and source-device labels from
 * settings; this use case owns only the build → validate → save step.
 */
class CaptureCoordinateUseCase @Inject constructor(
    private val coordinateRepository: CoordinateRepository,
    private val validate: ValidateCoordinateForSaveUseCase,
) {

    suspend operator fun invoke(
        name: String,
        note: String?,
        color: Int,
        iconId: String,
        provider: Provider,
        result: CaptureResult,
        captureMethod: String?,
        sourceDevice: String?,
        id: String = UUID.randomUUID().toString(),
    ): Coordinate {
        val coordinate = CoordinateFactory.fromCaptureResult(
            id = id,
            name = name,
            note = note,
            color = color,
            iconId = iconId,
            provider = provider,
            result = result,
            captureMethod = captureMethod,
            sourceDevice = sourceDevice,
        )
        val validation = validate(coordinate)
        require(validation.isValid) {
            "Refusing to save invalid captured coordinate: ${validation.errors.joinToString()}"
        }
        coordinateRepository.insert(coordinate)
        return coordinate
    }
}
