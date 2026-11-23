package app.surrealar.ui.openinar

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.CoordinateRepository
import io.github.pennstatecs.geokit.ar.model.ModelMove
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Saves a move made in AR onto the point: the session [ModelMove] is added to the offset already
 * stored (modelMoveEastM / NorthM / UpM) and a yaw change sets modelYawDeg. The point's latitude,
 * longitude and altitude are never touched. Offsets back at zero are stored as null (not moved).
 */
class SaveModelMoveUseCase @Inject constructor(private val repository: CoordinateRepository) {

    /**
     * @param resolvedYawDeg the yaw the model was rendered with before this move (point override or
     *   the model's default), so a twist is saved relative to what the user saw.
     * @return the saved coordinate.
     */
    suspend operator fun invoke(coordinateId: String, move: ModelMove, resolvedYawDeg: Float, nowMs: Long = System.currentTimeMillis()): Coordinate {
        val c = repository.getById(coordinateId) ?: error("Point not found")
        val updated = c.copy(
            modelMoveEastM = total(c.modelMoveEastM, move.eastM),
            modelMoveNorthM = total(c.modelMoveNorthM, move.northM),
            modelMoveUpM = total(c.modelMoveUpM, move.upM),
            modelYawDeg = if (move.yawDeg == 0f) c.modelYawDeg else normalizeDeg(resolvedYawDeg + move.yawDeg),
            updatedAt = nowMs,
        )
        repository.update(updated)
        return updated
    }

    internal companion object {
        /** Saved + session, to the millimetre; null when it comes back to zero. */
        fun total(saved: Double?, sessionM: Float): Double? {
            val mm = (((saved ?: 0.0) + sessionM) * 1000.0).roundToLong()
            return if (mm == 0L) null else mm / 1000.0
        }

        /** Degrees in (-180, 180], to 0.1°. */
        fun normalizeDeg(deg: Float): Double {
            var d = deg.toDouble() % 360.0
            if (d > 180.0) d -= 360.0
            if (d <= -180.0) d += 360.0
            val r = (d * 10.0).roundToLong() / 10.0
            return if (abs(r) == 0.0) 0.0 else r
        }
    }
}
