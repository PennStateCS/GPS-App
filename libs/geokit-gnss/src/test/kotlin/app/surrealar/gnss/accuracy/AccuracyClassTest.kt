package app.surrealar.gnss.accuracy

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.receiver.AccuracyClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AccuracyClassTest {

    private val table = UereTable()
    private val modes = listOf(RtkStatus.SINGLE, RtkStatus.DGPS, RtkStatus.FLOAT, RtkStatus.FIX, RtkStatus.DEAD_RECKONING)

    private fun fix(provider: Provider, mode: RtkStatus, cls: AccuracyClass? = null) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.UNKNOWN,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = null, altMslM = null, geoidSeparationM = null,
        hDop = 1.2, vDop = 1.8, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = mode,
        satsUsed = null, satsVisible = null, diffAgeS = null, speedMps = null, courseDeg = null,
        accuracyClass = cls,
    )

    @Test fun `providers default to the class they always used`() {
        assertEquals(AccuracyClass.SURVEY_MULTIBAND, AccuracyClass.defaultFor(Provider.EXTERNAL_TCP))
        assertEquals(AccuracyClass.SURVEY_MULTIBAND, AccuracyClass.defaultFor(Provider.EXTERNAL_BT))
        listOf(Provider.INTERNAL, Provider.REPLAY, Provider.MODEL, Provider.OTHER)
            .forEach { assertEquals(it.name, AccuracyClass.DEVICE, AccuracyClass.defaultFor(it)) }
    }

    @Test fun `survey multiband and device rows are the previous RS2 and internal values`() {
        assertEquals(listOf(2.5, 0.5, 0.25, 0.02, 10.0), modes.map { table.uere(AccuracyClass.SURVEY_MULTIBAND, it) })
        assertEquals(listOf(4.0, 2.0, 2.0, 2.0, 10.0), modes.map { table.uere(AccuracyClass.DEVICE, it) })
    }

    @Test fun `single frequency is conservative`() {
        assertEquals(listOf(5.0, 2.0, 1.0, 0.10, 10.0), modes.map { table.uere(AccuracyClass.SINGLE_FREQUENCY, it) })
        val device = table.uere(AccuracyClass.DEVICE, RtkStatus.SINGLE)!!
        assertTrue(table.uere(AccuracyClass.SINGLE_FREQUENCY, RtkStatus.SINGLE)!! >= device)
        modes.forEach { m ->
            assertTrue(m.name, table.uere(AccuracyClass.SINGLE_FREQUENCY, m)!! >= table.uere(AccuracyClass.SURVEY_MULTIBAND, m)!!)
        }
    }

    @Test fun `stamped survey and device fixes estimate exactly like unstamped external and internal ones`() {
        modes.forEach { m ->
            assertEquals(
                AccuracyEstimator.estimate1SigmaMeters(fix(Provider.EXTERNAL_TCP, m), table),
                AccuracyEstimator.estimate1SigmaMeters(fix(Provider.EXTERNAL_TCP, m, AccuracyClass.SURVEY_MULTIBAND), table),
            )
            assertEquals(
                AccuracyEstimator.estimate1SigmaMeters(fix(Provider.INTERNAL, m), table),
                AccuracyEstimator.estimate1SigmaMeters(fix(Provider.INTERNAL, m, AccuracyClass.DEVICE), table),
            )
        }
    }

    @Test fun `the fix's class wins over its provider (generic NMEA over TCP)`() {
        val generic = AccuracyEstimator.estimate1SigmaMeters(fix(Provider.EXTERNAL_TCP, RtkStatus.SINGLE, AccuracyClass.SINGLE_FREQUENCY), table)
        // HDOP 1.2 × 5.0 m × 1.515/√2, VDOP 1.8 × 5.0 m.
        assertEquals(1.2 * 5.0 * 1.515 / kotlin.math.sqrt(2.0), generic.horizontalMeters!!, 1e-9)
        assertEquals(9.0, generic.verticalMeters!!, 1e-9)
    }

    @Test fun `receiver-reported accuracy is used whatever the class`() {
        val f = fix(Provider.EXTERNAL_TCP, RtkStatus.FIX, AccuracyClass.SINGLE_FREQUENCY).copy(hAccM = 0.014, vAccM = 0.02)
        assertEquals(AccuracyEstimator.Accuracy1Sigma(0.014, 0.02), AccuracyEstimator.estimate1SigmaMeters(f, table))
    }
}
