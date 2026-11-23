package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.ar.model.ModelMove
import io.github.pennstatecs.geokit.ar.model.ModelPlacement

/**
 * State of Move model in AR, without Android: session-only moves, the Move mode target, Cancel /
 * Done / Reset / Save, the save gate and saves still on their way to the database. The fragment is
 * the glue (panel, dialog, renderer); everything here runs on the main thread except [targetId],
 * which the GL thread reads.
 *
 * A move never touches the point's surveyed latitude / longitude / altitude: saving only adds to the
 * point's model offsets (see [SaveModelMoveUseCase]).
 */
class MoveSessionController(private val testCubeId: String) {

    /** Why "Save to point" is not available right now. */
    enum class SaveBlock {
        /** The test cube is not a stored point. */
        TEST_CUBE,
        /** ARCore's localization is not at quality Good. */
        QUALITY_NOT_GOOD,
        /** The anchors were placed by timeout (no visual localization), so ARCore's position is a guess. */
        PLACED_BY_TIMEOUT,
        /** The previous save of this point has not finished yet. */
        SAVE_IN_PROGRESS,
    }

    /** What the AR screen knows about ARCore's localization when the user saves. */
    data class Conditions(val qualityGood: Boolean, val placedByTimeout: Boolean)

    /** A save to hand to the database. [token] identifies it in [saveSucceeded] / [saveFailed]. */
    data class SaveRequest(val id: String, val move: ModelMove, val startedAtMs: Long, val token: Long)

    /** Result of [done] and [confirmSave]. */
    sealed interface End {
        /** Nothing to do (not in Move mode, or a confirmation is already showing / was already answered). */
        data object Ignored : End
        /** Move mode ended with no move on the point. */
        data class NothingMoved(val id: String) : End
        /** Move mode ended; the move stays for this session only (the user did not ask to save). */
        data class SessionOnly(val id: String, val move: ModelMove) : End
        /** Move mode ended; the user asked to save but [block] prevents it, so the move stays session-only. */
        data class NotSaved(val id: String, val move: ModelMove, val block: SaveBlock) : End
        /** Still in Move mode: ask the user to confirm before saving [move]. */
        data class ConfirmSave(val id: String, val move: ModelMove) : End
        /** Move mode ended; [request] has moved from the session to a pending save. */
        data class Save(val request: SaveRequest) : End
    }

    private class PendingSave(val token: Long, val move: ModelMove, val startedAtMs: Long) {
        /** The point's updatedAt written by this save, once it has succeeded. */
        var savedUpdatedAtMs: Long? = null
    }

    /** Session-only moves per coordinate id, on top of the saved placement; gone when AR closes. */
    val sessionMoves = HashMap<String, ModelMove>()

    /** Coordinate id whose model is being moved, or null. Written on main, read on the GL thread. */
    @Volatile var targetId: String? = null
        private set

    /** The target's session move when Move mode started (Cancel restores it). */
    private var startSession = ModelMove()
    /** True while the save confirmation is showing. */
    private var confirming = false
    private val pending = HashMap<String, MutableList<PendingSave>>()
    private var nextToken = 1L

    val isConfirming: Boolean get() = confirming

    fun sessionMove(id: String): ModelMove = sessionMoves[id] ?: ModelMove()

    fun setSessionMove(id: String, move: ModelMove) {
        if (move.isZero) sessionMoves.remove(id) else sessionMoves[id] = move
    }

    /** Enters Move mode for [id] (end the previous target first). */
    fun start(id: String) {
        targetId = id
        startSession = sessionMove(id)
        confirming = false
    }

    /** Leaves Move mode keeping the session move as it is (switched point, point removed, AR closed). */
    fun end(): String? {
        val id = targetId ?: return null
        finish()
        return id
    }

    /** Cancel: puts back the session move from when Move mode started and leaves Move mode. */
    fun cancel(): String? {
        val id = targetId ?: return null
        setSessionMove(id, startSession)
        finish()
        return id
    }

    /**
     * Reset: back to the surveyed point and undoes this session's turn. [base] is the point's saved
     * placement (see [basePlacement]). Saved only if the user then saves.
     */
    fun reset(base: ModelPlacement): ModelMove? {
        val id = targetId ?: return null
        val move = ModelMove(eastM = -base.moveEastM, northM = -base.moveNorthM, upM = -base.moveUpM)
        setSessionMove(id, move)
        return move
    }

    /** Why the target's move can't be saved right now, or null when it can. */
    fun saveBlock(c: Conditions): SaveBlock? {
        val id = targetId ?: return null
        return when {
            id == testCubeId -> SaveBlock.TEST_CUBE
            c.placedByTimeout -> SaveBlock.PLACED_BY_TIMEOUT
            !c.qualityGood -> SaveBlock.QUALITY_NOT_GOOD
            pending[id].orEmpty().any { it.savedUpdatedAtMs == null } -> SaveBlock.SAVE_IN_PROGRESS
            else -> null
        }
    }

    /**
     * Done. Without [saveChecked] the move stays for this session. With it, and the gate open, Move
     * mode stays on and the user is asked to confirm ([End.ConfirmSave] → [confirmSave] or
     * [confirmDismissed]). A second Done while the confirmation shows is [End.Ignored].
     */
    fun done(saveChecked: Boolean, c: Conditions): End {
        val id = targetId ?: return End.Ignored
        if (confirming) return End.Ignored
        val move = sessionMove(id)
        if (move.isZero) { finish(); return End.NothingMoved(id) }
        if (!saveChecked) { finish(); return End.SessionOnly(id, move) }
        saveBlock(c)?.let { finish(); return End.NotSaved(id, move, it) }
        confirming = true
        return End.ConfirmSave(id, move)
    }

    /** The user backed out of the confirmation: still in Move mode, nothing saved. */
    fun confirmDismissed() { confirming = false }

    /**
     * The user confirmed. The gate is checked again (quality may have dropped while the dialog was
     * up). On success the move leaves the session and becomes a pending save, so the model doesn't
     * move on screen and the same move can't be saved twice.
     */
    fun confirmSave(c: Conditions, nowMs: Long): End {
        val id = targetId ?: return End.Ignored
        if (!confirming) return End.Ignored
        confirming = false
        val move = sessionMove(id)
        if (move.isZero) { finish(); return End.NothingMoved(id) }
        saveBlock(c)?.let { finish(); return End.NotSaved(id, move, it) }
        sessionMoves.remove(id)
        val save = PendingSave(nextToken++, move, nowMs)
        pending.getOrPut(id) { mutableListOf() } += save
        finish()
        return End.Save(SaveRequest(id, move, nowMs, save.token))
    }

    /** The save [token] was written with updatedAt [savedUpdatedAtMs]; it stays pending until the database shows it. */
    fun saveSucceeded(id: String, token: Long, savedUpdatedAtMs: Long) {
        pending[id]?.firstOrNull { it.token == token }?.savedUpdatedAtMs = savedUpdatedAtMs
    }

    /**
     * The save [token] failed: nothing was stored, so the move goes back to the session (nothing moves
     * on screen). Returns the new session move, or null when the token is unknown (already handled).
     */
    fun saveFailed(id: String, token: Long): ModelMove? {
        val list = pending[id] ?: return null
        val save = list.firstOrNull { it.token == token } ?: return null
        list.remove(save)
        if (list.isEmpty()) pending.remove(id)
        val move = sessionMove(id) + save.move
        setSessionMove(id, move)
        return move
    }

    /**
     * The point's saved placement: the database's [dbPlacement] plus saves it doesn't show yet. A save
     * is shown once the row's [dbUpdatedAtMs] reaches the updatedAt it wrote (or its start time, if
     * the database emitted before the save reported back; one save per point is in flight at a time).
     * A save that hasn't reported back stays listed, so a late failure can still return it to the session.
     */
    fun basePlacement(id: String, dbPlacement: ModelPlacement, dbUpdatedAtMs: Long): ModelPlacement {
        val list = pending[id] ?: return dbPlacement
        list.removeAll { s -> s.savedUpdatedAtMs?.let { dbUpdatedAtMs >= it } == true }
        if (list.isEmpty()) { pending.remove(id); return dbPlacement }
        return list.filter { dbUpdatedAtMs < (it.savedUpdatedAtMs ?: it.startedAtMs) }
            .fold(dbPlacement) { p, s -> p + s.move }
    }

    /** What the renderer shows: the saved placement plus this session's move. */
    fun effectivePlacement(id: String, dbPlacement: ModelPlacement, dbUpdatedAtMs: Long): ModelPlacement =
        basePlacement(id, dbPlacement, dbUpdatedAtMs) + sessionMove(id)

    private fun finish() {
        targetId = null
        confirming = false
    }
}
