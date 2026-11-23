package app.surrealar.util.diagnostics

import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EmlidNmea
import io.github.pennstatecs.geokit.receiver.emlid.nmea.ETC
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Golden for the full "NMEA Stream Diagnostics" text: representative Emlid EBP/ETC activity (none,
 * EBP only, timestamp-only ETC, ETC with an orientation payload, IMU seen earlier but latest ETC
 * timestamp-only). Recorded before EBP/ETC moved into geokit-receiver-emlid; the text must not change.
 * Regenerate (only after an intended change) with `GEOKIT_WRITE_GOLDEN=1`.
 */
class NmeaStreamDiagnosticsFormatterGoldenTest {

    private val full = NmeaFuser.FuserTimingStats(
        250, 50, 50, 250, 248, TimestampSource.NMEA_ZDA, RtkStatus.FIX, 22, 30, 395.539, -33.145, 0.0152,
        NmeaFuser.AccuracySource.RECEIVER_GST, mapOf("GP" to 250, "GL" to 250), mapOf("GA" to 500, "GP" to 750),
    )
    private val empty = NmeaFuser.FuserTimingStats(
        0, 0, 0, 0, 0, null, null, null, null, null, null, null,
        NmeaFuser.AccuracySource.UNKNOWN, emptyMap(), emptyMap(),
    )
    private val etcImu = ETC("GN", "173025.40", listOf("30", "00", "268.660", "116.146", "17.651", "6.280", "6.280", "6.991"))
    private val etcTimeOnly = ETC("GN", "172424.80", listOf("", "", "", "", "", "", "", ""))

    private fun case(name: String, timing: NmeaFuser.FuserTimingStats, ebp: Int, etc: Int, latest: ETC?, imu: Boolean) =
        "##### $name\n" + NmeaStreamDiagnosticsFormatter.format(
            "Emlid Reach RS4", "TCP", "192.168.42.1", 9001, timing,
            emlidStats(ebp, etc, latest, imu),
        )

    /** Extra-sentence stats as the fuser reports them with the Emlid parsers registered. */
    private fun emlidStats(ebp: Int, etc: Int, latestEtc: ETC?, imu: Boolean) = NmeaFuser.ExtraSentenceStats(
        countByTag = buildMap { if (ebp > 0) put(EmlidNmea.TAG_EBP, ebp); if (etc > 0) put(EmlidNmea.TAG_ETC, etc) },
        latestByTag = buildMap { latestEtc?.let { put(EmlidNmea.TAG_ETC, it) } },
        flagsByTag = if (imu) mapOf(EmlidNmea.TAG_ETC to setOf(EmlidNmea.FLAG_ORIENTATION)) else emptyMap(),
    )

    @Test
    fun `formatter output matches golden`() {
        val actual = listOf(
            case("no data", empty, 0, 0, null, false),
            case("generic stream, no Emlid sentences", full, 0, 0, null, false),
            case("EBP only (RS2+)", full, 181, 0, null, false),
            case("ETC timestamp only", full, 177, 177, etcTimeOnly, false),
            case("ETC with orientation payload", full, 180, 180, etcImu, true),
            case("IMU seen earlier, latest ETC timestamp only", full, 12, 40, etcTimeOnly, true),
        ).joinToString("\n")
        val path = "/golden/nmea_stream_diagnostics.txt"
        if (System.getenv("GEOKIT_WRITE_GOLDEN") == "1") {
            File("src/test/resources$path").apply { parentFile.mkdirs() }.writeText(actual)
            return
        }
        val expected = javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()?.replace("\r\n", "\n") ?: error("Missing $path")
        assertEquals(expected, actual)
    }
}
