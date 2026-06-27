package com.example.surveyingapp.data.backup

import com.example.surveyingapp.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportPlannerTest {

    private fun coord(id: String, modelId: String? = null) = Coordinate(
        id = id, name = "P-$id", latitude = 41.0, longitude = -76.0, altitude = 0.0,
        timestamp = 0L, icon = "", color = 0, modelId = modelId
    )

    @Test
    fun noDuplicates_noMissingModels() {
        val plan = BackupImportPlanner.plan(
            listOf(coord("a"), coord("b")), emptyList(), emptySet(), emptySet(), ImportMode.MERGE
        )
        assertEquals(2, plan.insertCount)
        assertEquals(0, plan.duplicateCount)
        assertEquals(0, plan.missingModelCount)
        assertFalse(plan.isNoOp)
        assertEquals("Imported 2 · merged", plan.summaryMessage())
    }

    @Test
    fun duplicateIds_reportedOnMerge() {
        val plan = BackupImportPlanner.plan(
            listOf(coord("a"), coord("b")), emptyList(), setOf("a"), emptySet(), ImportMode.MERGE
        )
        assertEquals(listOf("a"), plan.duplicateIds)
        assertTrue(plan.summaryMessage().contains("1 overwritten"))
    }

    @Test
    fun replaceMode_ignoresDuplicates() {
        val plan = BackupImportPlanner.plan(
            listOf(coord("a")), emptyList(), setOf("a"), emptySet(), ImportMode.REPLACE
        )
        assertEquals(0, plan.duplicateCount)
        assertTrue(plan.summaryMessage().endsWith("replaced"))
    }

    @Test
    fun missingModelReferences_flagged() {
        val plan = BackupImportPlanner.plan(
            listOf(coord("a", modelId = "m1"), coord("b", modelId = "m2")),
            emptyList(), emptySet(), setOf("m1"), ImportMode.MERGE
        )
        assertEquals(1, plan.missingModelCount)            // only m2 is absent
        assertTrue(plan.missingModelRefs.single().contains("m2"))
    }

    @Test
    fun bothDuplicatesAndMissingModels() {
        val plan = BackupImportPlanner.plan(
            listOf(coord("a", modelId = "m2")), emptyList(), setOf("a"), setOf("m1"), ImportMode.MERGE
        )
        assertEquals(1, plan.duplicateCount)
        assertEquals(1, plan.missingModelCount)
        val msg = plan.summaryMessage()
        assertTrue(msg.contains("1 missing model(s)"))
        assertTrue(msg.contains("1 overwritten"))
    }

    @Test
    fun coordinatesWithoutModelId_neverMissing() {
        val plan = BackupImportPlanner.plan(
            listOf(coord("a"), coord("b")), emptyList(), emptySet(), emptySet(), ImportMode.MERGE
        )
        assertEquals(0, plan.missingModelCount)
    }

    @Test
    fun blankModelId_neverMissing() {
        val plan = BackupImportPlanner.plan(
            listOf(coord("a", modelId = "")), emptyList(), emptySet(), emptySet(), ImportMode.MERGE
        )
        assertEquals(0, plan.missingModelCount)
    }

    @Test
    fun emptyList_isNoOp_withClearMessage() {
        val plan = BackupImportPlanner.plan(
            emptyList(), listOf("'x' (1): bad"), emptySet(), emptySet(), ImportMode.MERGE
        )
        assertTrue(plan.isNoOp)
        assertEquals("No coordinates to import", plan.summaryMessage())
    }

    @Test
    fun counts_andWarnings_areAccurate() {
        val plan = BackupImportPlanner.plan(
            parsedCoordinates = listOf(coord("a", modelId = "m2"), coord("b")),
            skippedInvalid = listOf("'bad' (z)"),
            existingCoordinateIds = setOf("a"),
            existingModelIds = setOf("m1"),
            mode = ImportMode.MERGE
        )
        assertEquals(2, plan.insertCount)
        assertEquals(1, plan.skippedCount)
        assertEquals(1, plan.missingModelCount)
        assertEquals(1, plan.duplicateCount)
        assertEquals(
            "Imported 2 · 1 skipped · 1 missing model(s) · 1 overwritten · merged",
            plan.summaryMessage()
        )
    }
}
