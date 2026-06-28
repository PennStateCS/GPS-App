package app.surrealar.domain.usecase

import app.surrealar.domain.coordinates.CoordinateValidator
import app.surrealar.domain.coordinates.ValidationResult
import app.surrealar.domain.model.Coordinate
import javax.inject.Inject

/**
 * The save gate for a [Coordinate]: the single place that decides whether a coordinate may be
 * persisted. It composes the canonical [CoordinateValidator] (lat/lon bounds, the `0,0` null-island
 * default, finite altitude, non-blank name, quality warnings) with the AR model-placement checks
 * (positive scale, finite angles/offsets) so capture, manual entry, and import all apply the same
 * rules.
 *
 * Set `allowNullIsland` only on an explicit debug/test path that intentionally needs the `0,0`
 * placeholder; production callers leave it false. The returned [ValidationResult] is advisory —
 * callers decide whether to block on `errors` and surface `warnings`.
 */
class ValidateCoordinateForSaveUseCase @Inject constructor() {

    operator fun invoke(coordinate: Coordinate, allowNullIsland: Boolean = false): ValidationResult {
        val base = CoordinateValidator.validate(coordinate)
        val errors = base.errors.toMutableList()
        val warnings = base.warnings.toMutableList()

        // Re-permit the 0,0 null-island default only when a debug/test path asked for it.
        if (allowNullIsland) errors.removeAll { it.contains("0,0") }

        // Model placement fields are nullable and only set when a model is linked or overridden.
        // A non-positive scale hides the model; non-finite placement breaks the AR transform.
        coordinate.modelScale?.let {
            if (!it.isFinite() || it <= 0.0) errors += "Model scale must be a positive number"
        }
        val placement = listOf(
            "model yaw" to coordinate.modelYawDeg,
            "model pitch" to coordinate.modelPitchDeg,
            "model roll" to coordinate.modelRollDeg,
            "model vertical offset" to coordinate.modelVerticalOffsetM,
            "model origin offset X" to coordinate.modelOriginOffsetXM,
            "model origin offset Y" to coordinate.modelOriginOffsetYM,
            "model origin offset Z" to coordinate.modelOriginOffsetZM,
        )
        for ((label, value) in placement) {
            if (value != null && !value.isFinite()) errors += "$label must be a finite number"
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors, warnings = warnings)
    }
}
