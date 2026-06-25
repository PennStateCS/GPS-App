package com.example.surveyingapp.gnss.bus.adapters

import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.nmea.parse.DefaultNmeaRegistry
import com.example.surveyingapp.gnss.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards that a 5 Hz GGA stream paired with 1 Hz ZDA/RMC emits five fresh fixes per second — i.e.
 * the fuser keys the epoch off the GGA time-of-day, not the slower RMC/ZDA timestamp.
 */
class NmeaFuser5HzTest {

    private fun fuser(onFix: (Fix) -> Unit) = NmeaFuser(
        provider = Provider.RS2_EXTERNAL,
        registry = DefaultNmeaRegistry.create(verifyChecksum = false),
        onFix = onFix,
        onGsv = { }
    )

    @Test
    fun `five distinct GGA epochs with 1Hz ZDA emit five fixes`() {
        val fixes = mutableListOf<Fix>()
        val f = fuser { fixes.add(it) }

        // 1 Hz date/time context.
        f.accept("\$GNZDA,120000.00,15,08,2025,00,00")

        // 5 Hz GGA: five distinct sub-second times within the same UTC second.
        listOf("120000.00", "120000.20", "120000.40", "120000.60", "120000.80").forEach { t ->
            f.accept("\$GNGGA,$t,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,,")
        }

        assertEquals("Each distinct GGA epoch should emit a fresh fix", 5, fixes.size)
    }

    @Test
    fun `repeated identical GGA epoch does not emit duplicates`() {
        val fixes = mutableListOf<Fix>()
        val f = fuser { fixes.add(it) }
        f.accept("\$GNZDA,120000.00,15,08,2025,00,00")

        f.accept("\$GNGGA,120000.20,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,,")
        f.accept("\$GNGGA,120000.20,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,,") // identical epoch

        assertEquals("Identical GGA time-of-day must not duplicate", 1, fixes.size)
    }

    @Test
    fun `timingStats counts sentences and emitted fixes`() {
        val f = fuser { }
        f.accept("\$GNZDA,120000.00,15,08,2025,00,00")
        listOf("120000.00", "120000.20", "120000.40").forEach { t ->
            f.accept("\$GNGGA,$t,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,,")
        }
        val s = f.timingStats
        assertEquals(3, s.ggaCount)
        assertEquals(1, s.zdaCount)
        assertEquals(0, s.rmcCount)
        assertEquals(3, s.emittedFixCount)
        org.junit.Assert.assertNotNull("a date source should be recorded", s.lastTimestampSource)
    }

    @Test
    fun `fix timestamps advance with GGA time-of-day`() {
        val fixes = mutableListOf<Fix>()
        val f = fuser { fixes.add(it) }
        f.accept("\$GNZDA,120000.00,15,08,2025,00,00")
        f.accept("\$GNGGA,120000.00,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,,")
        f.accept("\$GNGGA,120000.20,4807.038,N,01131.000,E,4,08,0.9,545.4,M,46.9,M,,")

        assertEquals(2, fixes.size)
        val deltaMs = fixes[1].timeUtc.toEpochMilli() - fixes[0].timeUtc.toEpochMilli()
        assertEquals("Timestamps should be 200 ms apart (5 Hz)", 200L, deltaMs)
    }
}
