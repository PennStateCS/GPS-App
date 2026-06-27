package com.example.surveyingapp.data.health

import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.data.local.entity.ModelEntity
import com.example.surveyingapp.gnss.model.Provider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Exercises the pure [DataHealthChecker.analyze] over constructed records. The checker is
 * read-only; these tests only assert which issues it reports.
 */
class DataHealthCheckerTest {

    private val checker = DataHealthChecker(mock(CoordinateDao::class.java), mock(ModelDao::class.java))

    private fun coord(
        id: String = "c1",
        lat: Double = 41.0,
        lon: Double = -76.0,
        provider: Provider = Provider.INTERNAL,
        captureMethod: String? = "INTERNAL_GPS",
        timestamp: Long = 1_000L,
        icon: String = "ic_pin",
        modelId: String? = null,
        horizontalAccuracyM: Double? = null,
        modelScale: Double? = null
    ) = CoordinateEntity(
        id = id, name = "P-$id", latitude = lat, longitude = lon, altitude = 0.0,
        timestamp = timestamp, icon = icon, color = 0, provider = provider,
        captureMethod = captureMethod, horizontalAccuracyM = horizontalAccuracyM, modelId = modelId,
        modelScale = modelScale
    )

    private fun model(id: String = "m1", filePath: String = "/no/such/file.glb", thumb: String? = null) =
        ModelEntity(id = id, name = "M-$id", fileName = "m.glb", filePath = filePath,
            fileSize = 1L, dateAdded = 0L, thumbnailFilePath = thumb)

    private fun analyze(coords: List<CoordinateEntity>, models: List<ModelEntity> = emptyList()) =
        checker.analyze(coords, models)

    private fun DataHealthReport.has(severity: HealthSeverity, substr: String) =
        issues.any { it.severity == severity && it.message.contains(substr) }

    @Test fun flagsNullIsland() =
        assertTrue(analyze(listOf(coord(lat = 0.0, lon = 0.0))).has(HealthSeverity.ERROR, "0,0"))

    @Test fun flagsOutOfRange() =
        assertTrue(analyze(listOf(coord(lat = 200.0))).has(HealthSeverity.ERROR, "out-of-range"))

    @Test fun flagsMissingCaptureMethod() =
        assertTrue(analyze(listOf(coord(captureMethod = null))).has(HealthSeverity.WARNING, "captureMethod"))

    @Test fun flagsUnclassifiedProvider() =
        assertTrue(analyze(listOf(coord(provider = Provider.OTHER))).has(HealthSeverity.WARNING, "unclassified provider"))

    @Test fun flagsZeroTimestamp() =
        assertTrue(analyze(listOf(coord(timestamp = 0L))).has(HealthSeverity.WARNING, "missing/zero timestamp"))

    @Test fun flagsFutureTimestamp() {
        val future = System.currentTimeMillis() + 10L * 24 * 60 * 60 * 1000
        assertTrue(analyze(listOf(coord(timestamp = future))).has(HealthSeverity.WARNING, "future timestamp"))
    }

    @Test fun flagsSuspiciousAccuracy() =
        assertTrue(analyze(listOf(coord(horizontalAccuracyM = 100.0))).has(HealthSeverity.WARNING, "suspicious horizontal accuracy"))

    @Test fun flagsLegacyModelIcon() =
        assertTrue(analyze(listOf(coord(icon = "model:legacy", modelId = null))).has(HealthSeverity.WARNING, "legacy"))

    @Test fun flagsModelIdPointingToMissingModel() =
        assertTrue(analyze(listOf(coord(modelId = "ghost"))).has(HealthSeverity.ERROR, "links missing model"))

    @Test fun doesNotFlagModelIdWhenModelExists() {
        val report = analyze(listOf(coord(modelId = "m1")), listOf(model(id = "m1")))
        assertTrue(report.issues.none { it.message.contains("links missing model") })
    }

    @Test fun flagsInvalidModelScale() =
        assertTrue(analyze(listOf(coord(modelScale = 0.0))).has(HealthSeverity.WARNING, "invalid modelScale"))

    @Test fun flagsModelWithMissingFile() =
        assertTrue(analyze(emptyList(), listOf(model(filePath = "/no/such/file.glb"))).has(HealthSeverity.ERROR, "file is missing on disk"))

    @Test fun flagsModelWithBlankFilePath() =
        assertTrue(analyze(emptyList(), listOf(model(filePath = ""))).has(HealthSeverity.ERROR, "blank file path"))

    @Test fun flagsModelWithMissingThumbnail() =
        assertTrue(analyze(emptyList(), listOf(model(thumb = null))).has(HealthSeverity.WARNING, "no thumbnail"))

    @Test fun cleanData_hasNoIssues() {
        val report = analyze(listOf(coord()))
        assertTrue("expected no issues, got ${report.issues}", report.issues.isEmpty())
    }
}
