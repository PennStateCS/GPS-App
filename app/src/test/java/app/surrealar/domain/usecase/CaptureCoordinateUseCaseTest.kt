package app.surrealar.domain.usecase

import app.surrealar.gnss.capture.CaptureResult
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CaptureCoordinateUseCaseTest {

    private fun result(lat: Double = 41.0, lon: Double = -76.0, alt: Double = 123.4) = CaptureResult(
        startedAt = Instant.ofEpochMilli(0), endedAt = Instant.ofEpochMilli(1_000),
        samples = 30, latDeg = lat, lonDeg = lon, altEllipsoidalM = alt,
        ecefStd = Triple(0.01, 0.01, 0.02),
        rtkStatus = RtkStatus.FIX, satsUsed = 22, satsVisible = 28,
        hdop = 0.7, vDop = 0.9, pDop = 1.1, hAccM = 0.02, vAccM = 0.03,
        diffAgeS = 1.0, correctionStationId = "0123",
    )

    private fun useCase(repo: FakeCoordinateRepository) =
        CaptureCoordinateUseCase(repo, ValidateCoordinateForSaveUseCase())

    @Test fun validCapture_buildsValidatesAndSaves() = runTest {
        val repo = FakeCoordinateRepository()
        val saved = useCase(repo)(
            name = "Pt1", note = null, color = 0, iconId = "ic_pin",
            provider = Provider.RS2_EXTERNAL, result = result(),
            captureMethod = "external_gnss", sourceDevice = "RS2+",
        )
        assertEquals(1, repo.store.size)
        assertEquals("Pt1", saved.name)
        assertEquals(123.4, saved.altitude, 0.0)   // real averaged altitude, never a fake 0.0
        assertTrue(saved.latitude in 40.0..42.0)
    }

    @Test fun invalidCapture_isRejected_notSaved() = runTest {
        val repo = FakeCoordinateRepository()
        // A 0,0 result is invalid; the use case must refuse to persist it.
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                useCase(repo)(
                    name = "Bad", note = null, color = 0, iconId = "ic_pin",
                    provider = Provider.OTHER, result = result(lat = 0.0, lon = 0.0),
                    captureMethod = "averaged", sourceDevice = null,
                )
            }
        }
        assertEquals(0, repo.store.size)
    }
}
