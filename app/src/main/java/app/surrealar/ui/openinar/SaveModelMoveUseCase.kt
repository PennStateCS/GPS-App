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
 *
 * The read-add-write runs in one database transaction ([CoordinateRepository.updateById]), so two
 * saves can't lose each other's offset, and a failed save leaves the stored point unchanged.
 */
class SaveModelMoveUseCase @Inject constructor(private val repository: CoordinateRepository) {

    /**
     * @param fallbackYawDeg the yaw the model renders with when the point has no yaw of its own (the
     *   model's default), so a twist is saved relative to what the user saw. A point that already
     *   has a yaw gets the twist added to the stored value, read inside the transaction.
     * @return the saved coordinate.
     */
    suspend operator fun invoke(coordinateId: String, move: ModelMove, fallbackYawDeg: Float, nowMs: Long = System.currentTimeMillis()): Coordinate =
        repository.updateById(coordinateId) { c -> applyMove(c, move, fallbackYawDeg, nowMs) } ?: error("Point not found")

    internal companion object {
        /** [c] with [move] added to its model offsets; latitude, longitude and altitude are copied unchanged. */
        fun applyMove(c: Coordinate, move: ModelMove, fallbackYawDeg: Float, nowMs: Long): Coordinate = c.copy(
            modelMoveEastM = total(c.modelMoveEastM, move.eastM),
            modelMoveNorthM = total(c.modelMoveNorthM, move.northM),
            modelMoveUpM = total(c.modelMoveUpM, move.upM),
            modelYawDeg = if (move.yawDeg == 0f) c.modelYawDeg
                          else normalizeDeg((c.modelYawDeg?.toFloat() ?: fallbackYawDeg) + move.yawDeg),
            updatedAt = nowMs,
        )

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
