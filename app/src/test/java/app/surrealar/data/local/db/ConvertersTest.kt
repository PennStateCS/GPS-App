package app.surrealar.data.local.db

import app.surrealar.domain.model.CorrectionSource
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class ConvertersTest {

    @Test
    fun provider_parsesAllLegacyAliases() {
        val internal = listOf("INTERNAL", "fused", "internal", "internal_gps")
        val external = listOf("RS2_EXTERNAL", "rs2-external", "rs2_external", "external", "external_gnss")
        val bt = listOf("RS2_BT", "rs2-bt", "rs2_bt")
        val tcp = listOf("RS2_TCP", "rs2-tcp", "rs2_tcp")
        val model = listOf("MODEL", "model", "model_embedded")
        val other = listOf("OTHER", "other")

        internal.forEach { assertEquals(it, Provider.INTERNAL, Converters.toProvider(it)) }
        external.forEach { assertEquals(it, Provider.RS2_EXTERNAL, Converters.toProvider(it)) }
        bt.forEach { assertEquals(it, Provider.RS2_BT, Converters.toProvider(it)) }
        tcp.forEach { assertEquals(it, Provider.RS2_TCP, Converters.toProvider(it)) }
        model.forEach { assertEquals(it, Provider.MODEL, Converters.toProvider(it)) }
        other.forEach { assertEquals(it, Provider.OTHER, Converters.toProvider(it)) }
    }

    @Test
    fun provider_unknownMapsToOther_blankToNull() {
        assertEquals(Provider.OTHER, Converters.toProvider("totally-unknown"))
        assertNull(Converters.toProvider(null))
        assertNull(Converters.toProvider(""))
        assertNull(Converters.toProvider("   "))
    }

    @Test
    fun rtk_parsesCanonicalAndLegacyAliases() {
        assertEquals(RtkStatus.FIX, Converters.toRtkStatus("FIX"))
        assertEquals(RtkStatus.FIX, Converters.toRtkStatus("RTK_FIXED"))
        assertEquals(RtkStatus.FIX, Converters.toRtkStatus("FIXED"))
        assertEquals(RtkStatus.FLOAT, Converters.toRtkStatus("FLOAT"))
        assertEquals(RtkStatus.FLOAT, Converters.toRtkStatus("RTK_FLOAT"))
        assertEquals(RtkStatus.DGPS, Converters.toRtkStatus("DGPS"))
        assertEquals(RtkStatus.SINGLE, Converters.toRtkStatus("SINGLE"))
        assertEquals(RtkStatus.INVALID, Converters.toRtkStatus("INVALID"))
        assertEquals(RtkStatus.NONE, Converters.toRtkStatus("NONE"))
        assertEquals(RtkStatus.DEAD_RECKONING, Converters.toRtkStatus("DEAD_RECKONING"))
    }

    @Test
    fun rtk_unknownOrBlankIsNull() {
        assertNull(Converters.toRtkStatus(null))
        assertNull(Converters.toRtkStatus(""))
        assertNull(Converters.toRtkStatus("nonsense"))
    }

    @Test
    fun instant_roundTrips() {
        val now = java.time.Instant.ofEpochMilli(1_700_000_000_000L)
        val millis = Converters.toEpochMillis(now)
        assertEquals(now, Converters.fromEpochMillis(millis))
        assertNull(Converters.fromEpochMillis(null))
        assertNull(Converters.toEpochMillis(null))
    }

    @Test
    fun duration_roundTrips() {
        val d = 12.5.toDuration(DurationUnit.SECONDS)
        val secs = Converters.toSeconds(d)
        assertEquals(12.5, secs!!, 1e-9)
        assertEquals(d, Converters.fromSeconds(secs))
        assertNull(Converters.fromSeconds(null))
        assertNull(Converters.toSeconds(null))
    }

    @Test
    fun correctionSource_roundTrips_andSafe() {
        assertEquals("NTRIP", Converters.fromCorrectionSource(CorrectionSource.NTRIP))
        assertEquals(CorrectionSource.NTRIP, Converters.toCorrectionSource("NTRIP"))
        assertNull(Converters.toCorrectionSource("garbage"))
        assertNull(Converters.toCorrectionSource(null))
        assertNull(Converters.fromCorrectionSource(null))
    }
}
