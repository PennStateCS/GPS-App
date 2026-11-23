package app.surrealar.ui.openinar

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.usecase.FakeCoordinateRepository
import app.surrealar.ui.openinar.MoveSessionController.End
import app.surrealar.ui.openinar.MoveSessionController.SaveBlock
import io.github.pennstatecs.geokit.ar.model.ModelMove
import io.github.pennstatecs.geokit.ar.model.ModelPlacement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveSessionControllerTest {

    private val good = MoveSessionController.Conditions(qualityGood = true, placedByTimeout = false)
    private val fair = MoveSessionController.Conditions(qualityGood = false, placedByTimeout = false)
    private val timeout = MoveSessionController.Conditions(qualityGood = true, placedByTimeout = true)
    private val c = MoveSessionController(testCubeId = "cube")

    private fun MoveSessionController.nudge(id: String, m: ModelMove) = setSessionMove(id, sessionMove(id) + m)

    private fun saveRequest(end: End): MoveSessionController.SaveRequest {
        assertTrue("expected Save, got $end", end is End.Save)
        return (end as End.Save).request
    }

    // ── Cancel / Done / Reset ────────────────────────────────────────────────────────────────────

    @Test fun `cancel restores the session move from entry`() {
        c.setSessionMove("p", ModelMove(eastM = 1f))
        c.start("p")
        c.nudge("p", ModelMove(northM = 2f, yawDeg = 10f))
        assertEquals("p", c.cancel())
        assertEquals(ModelMove(eastM = 1f), c.sessionMove("p"))
        assertNull(c.targetId)
    }

    @Test fun `done without save keeps the move for this session only`() {
        c.start("p")
        c.nudge("p", ModelMove(eastM = 0.5f))
        assertEquals(End.SessionOnly("p", ModelMove(eastM = 0.5f)), c.done(saveChecked = false, good))
        assertEquals(ModelMove(eastM = 0.5f), c.sessionMove("p"))
        assertNull(c.targetId)
    }

    @Test fun `done with nothing moved`() {
        c.start("p")
        assertEquals(End.NothingMoved("p"), c.done(saveChecked = true, good))
    }

    @Test fun `reset returns to the surveyed point and undoes this session's turn`() {
        val saved = ModelPlacement(yawDeg = 30f, moveEastM = 1f, moveNorthM = -2f, moveUpM = 0.5f)
        c.start("p")
        c.nudge("p", ModelMove(eastM = 3f, yawDeg = 45f))
        c.reset(saved)
        val shown = c.effectivePlacement("p", saved, dbUpdatedAtMs = 0L)
        assertEquals(0f, shown.moveEastM, 1e-6f); assertEquals(0f, shown.moveNorthM, 1e-6f); assertEquals(0f, shown.moveUpM, 1e-6f)
        assertEquals("the saved turn stays, this session's is undone", 30f, shown.yawDeg, 1e-6f)
        assertEquals("p", c.targetId)
    }

    @Test fun `reset with no saved offset clears the session move`() {
        c.start("p")
        c.nudge("p", ModelMove(eastM = 3f))
        c.reset(ModelPlacement())
        assertTrue(c.sessionMove("p").isZero)
        assertFalse(c.sessionMoves.containsKey("p"))
    }

    // ── Save gate ────────────────────────────────────────────────────────────────────────────────

    @Test fun `save needs a confirmation first`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        assertEquals(End.ConfirmSave("p", ModelMove(eastM = 1f)), c.done(saveChecked = true, good))
        assertEquals("still in Move mode while asking", "p", c.targetId)
        assertTrue(c.isConfirming)
    }

    @Test fun `backing out of the confirmation stays in Move mode and saves nothing`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        c.done(saveChecked = true, good)
        c.confirmDismissed()
        assertEquals("p", c.targetId)
        assertEquals(End.Ignored, c.confirmSave(good, nowMs = 1L))
        assertEquals(ModelMove(eastM = 1f), c.sessionMove("p"))
    }

    @Test fun `confirmed save moves the session move to a pending save`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        c.done(saveChecked = true, good)
        val req = saveRequest(c.confirmSave(good, nowMs = 100L))
        assertEquals("p", req.id); assertEquals(ModelMove(eastM = 1f), req.move); assertEquals(100L, req.startedAtMs)
        assertTrue(c.sessionMove("p").isZero)
        assertNull(c.targetId)
        // The model doesn't jump: the pending save is shown on top of the old database placement.
        assertEquals(1f, c.effectivePlacement("p", ModelPlacement(), dbUpdatedAtMs = 50L).moveEastM, 0f)
    }

    @Test fun `quality not Good blocks saving and keeps the move session-only`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        assertEquals(SaveBlock.QUALITY_NOT_GOOD, c.saveBlock(fair))
        assertEquals(End.NotSaved("p", ModelMove(eastM = 1f), SaveBlock.QUALITY_NOT_GOOD), c.done(saveChecked = true, fair))
        assertEquals(ModelMove(eastM = 1f), c.sessionMove("p"))
    }

    @Test fun `quality dropping while adjusting disables save, recovering enables it`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        assertNull(c.saveBlock(good))
        c.nudge("p", ModelMove(eastM = 1f))
        assertEquals(SaveBlock.QUALITY_NOT_GOOD, c.saveBlock(fair))
        assertNull(c.saveBlock(good))
    }

    @Test fun `quality dropping while the confirmation shows saves nothing`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        c.done(saveChecked = true, good)
        assertEquals(End.NotSaved("p", ModelMove(eastM = 1f), SaveBlock.QUALITY_NOT_GOOD), c.confirmSave(fair, nowMs = 1L))
        assertEquals(ModelMove(eastM = 1f), c.sessionMove("p"))
    }

    @Test fun `placement by timeout blocks saving even at quality Good`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        assertEquals(SaveBlock.PLACED_BY_TIMEOUT, c.saveBlock(timeout))
        assertTrue(c.done(saveChecked = true, timeout) is End.NotSaved)
    }

    @Test fun `the test cube can never be saved`() {
        c.start("cube"); c.nudge("cube", ModelMove(eastM = 1f))
        assertEquals(SaveBlock.TEST_CUBE, c.saveBlock(good))
        assertEquals(End.NotSaved("cube", ModelMove(eastM = 1f), SaveBlock.TEST_CUBE), c.done(saveChecked = true, good))
    }

    // ── No double apply ──────────────────────────────────────────────────────────────────────────

    @Test fun `double tap on Done asks once`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        assertTrue(c.done(saveChecked = true, good) is End.ConfirmSave)
        assertEquals(End.Ignored, c.done(saveChecked = true, good))
    }

    @Test fun `double tap on the confirmation saves once`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f))
        c.done(saveChecked = true, good)
        saveRequest(c.confirmSave(good, nowMs = 1L))
        assertEquals(End.Ignored, c.confirmSave(good, nowMs = 2L))
        assertEquals(End.Ignored, c.done(saveChecked = true, good))
    }

    @Test fun `a second save while the first is in flight is blocked`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f)); c.done(true, good)
        saveRequest(c.confirmSave(good, nowMs = 10L))
        c.start("p"); c.nudge("p", ModelMove(eastM = 0.5f))
        assertEquals(SaveBlock.SAVE_IN_PROGRESS, c.saveBlock(good))
        assertTrue(c.done(true, good) is End.NotSaved)
        assertEquals("kept for the session", ModelMove(eastM = 0.5f), c.sessionMove("p"))
    }

    @Test fun `failed save goes back to the session, once`() {
        c.setSessionMove("p", ModelMove())
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f)); c.done(true, good)
        val req = saveRequest(c.confirmSave(good, nowMs = 10L))
        assertEquals(ModelMove(eastM = 1f), c.saveFailed("p", req.token))
        assertNull("a repeated failure callback can't add it twice", c.saveFailed("p", req.token))
        assertEquals(ModelMove(eastM = 1f), c.sessionMove("p"))
        assertEquals("shown once", 1f, c.effectivePlacement("p", ModelPlacement(), 5L).moveEastM, 0f)
        c.start("p")
        assertNull("nothing in flight any more", c.saveBlock(good))
    }

    @Test fun `a failure reported after an unrelated database change still returns the move`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f)); c.done(true, good)
        val req = saveRequest(c.confirmSave(good, nowMs = 10L))
        // Some other write bumps updatedAt before the save reports back: the pending save is hidden...
        assertEquals(0f, c.effectivePlacement("p", ModelPlacement(), dbUpdatedAtMs = 11L).moveEastM, 0f)
        // ...but a failure still puts it back in the session.
        c.saveFailed("p", req.token)
        assertEquals(1f, c.effectivePlacement("p", ModelPlacement(), dbUpdatedAtMs = 11L).moveEastM, 0f)
    }

    @Test fun `database emitting before the save reports back is not double counted`() {
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f)); c.done(true, good)
        val req = saveRequest(c.confirmSave(good, nowMs = 10L))
        val fromDb = ModelPlacement(moveEastM = 1f)
        assertEquals(1f, c.effectivePlacement("p", fromDb, dbUpdatedAtMs = 12L).moveEastM, 0f)
        c.saveSucceeded("p", req.token, savedUpdatedAtMs = 12L)
        assertEquals(1f, c.effectivePlacement("p", fromDb, dbUpdatedAtMs = 12L).moveEastM, 0f)
    }

    // ── Save → Room emission → reopen, with the real use case ───────────────────────────────────

    private fun point() = Coordinate(
        id = "p", name = "P", latitude = 41.25, longitude = -75.88, altitude = 210.5,
        timestamp = 0L, icon = "", color = 0, modelId = "m", updatedAt = 1L,
    )

    /** What the AR screen would get from Room for the stored point. */
    private fun FakeCoordinateRepository.db(): Pair<ModelPlacement, Long> {
        val s = store.single()
        return ModelPlacement(
            moveEastM = (s.modelMoveEastM ?: 0.0).toFloat(), moveNorthM = (s.modelMoveNorthM ?: 0.0).toFloat(),
            yawDeg = (s.modelYawDeg ?: 0.0).toFloat(),
        ) to s.updatedAt
    }

    private suspend fun save(repo: FakeCoordinateRepository, req: MoveSessionController.SaveRequest): Long =
        SaveModelMoveUseCase(repo)(req.id, req.move, fallbackYawDeg = 0f, nowMs = req.startedAtMs).updatedAt

    @Test fun `save, Room emission, reopen and save again stores each move once`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point()))
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f, yawDeg = 10f)); c.done(true, good)
        val r1 = saveRequest(c.confirmSave(good, nowMs = 100L))

        // Reopened before the save landed: shows the saved offset once.
        var (dbP, dbT) = repo.db()
        c.start("p")
        assertEquals(1f, c.effectivePlacement("p", dbP, dbT).moveEastM, 0f)
        assertEquals(10f, c.effectivePlacement("p", dbP, dbT).yawDeg, 0f)

        c.saveSucceeded("p", r1.token, save(repo, r1))
        repo.db().let { dbP = it.first; dbT = it.second }
        assertEquals("Room emitted: still once", 1f, c.effectivePlacement("p", dbP, dbT).moveEastM, 0f)
        assertEquals(10f, c.effectivePlacement("p", dbP, dbT).yawDeg, 0f)

        // Quick second adjustment and save.
        c.nudge("p", ModelMove(eastM = 0.5f)); c.done(true, good)
        val r2 = saveRequest(c.confirmSave(good, nowMs = 200L))
        assertEquals(1.5f, c.effectivePlacement("p", dbP, dbT).moveEastM, 1e-6f)
        c.saveSucceeded("p", r2.token, save(repo, r2))
        repo.db().let { dbP = it.first; dbT = it.second }
        assertEquals(1.5f, c.effectivePlacement("p", dbP, dbT).moveEastM, 1e-6f)
        assertEquals(1.5, repo.store.single().modelMoveEastM!!, 1e-9)

        // The point never moved.
        val s = repo.store.single()
        assertEquals(41.25, s.latitude, 0.0); assertEquals(-75.88, s.longitude, 0.0); assertEquals(210.5, s.altitude, 0.0)
    }

    @Test fun `failed save leaves the stored point unchanged and the move session-only`() = runTest {
        val repo = FakeCoordinateRepository(listOf(point().copy(modelMoveEastM = 0.2)))
        val before = repo.store.single()
        c.start("p"); c.nudge("p", ModelMove(eastM = 1f)); c.done(true, good)
        val r = saveRequest(c.confirmSave(good, nowMs = 100L))
        repo.failUpdate = IllegalStateException("write failed")
        assertTrue(runCatching { save(repo, r) }.isFailure)
        c.saveFailed("p", r.token)
        assertSame(before, repo.store.single())
        val (dbP, dbT) = repo.db()
        assertEquals(1.2f, c.effectivePlacement("p", dbP, dbT).moveEastM, 1e-6f)
        assertEquals(ModelMove(eastM = 1f), c.sessionMove("p"))
    }
}
