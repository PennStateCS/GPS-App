package io.github.pennstatecs.geokit.receiver.emlid.nmea

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Golden regression with the Emlid parsers registered, as the app's live registry has them. The fix /
 * GSV / sky part must equal geokit-gnss's `golden/<fixture>.golden.txt` (shared test resources: Emlid
 * sentences never change a fix), and the Emlid diagnostics (EBP/ETC counts, IMU flag, latest ETC) must
 * equal `golden/<fixture>.emlid.txt`. Both were recorded before EBP/ETC moved out of geokit-gnss.
 *
 * Regenerate the Emlid part (only after an intended change): set `GEOKIT_WRITE_GOLDEN=1`.
 */
class EmlidNmeaGoldenTest {

    @Test fun `reach RS4 capture matches golden`() = check("reach_nmea_capture")
    @Test fun `reach RS4 IMU capture matches golden`() = check("reach_nmea_withimu")
    @Test fun `reach RS2 capture matches golden`() = check("reach_nmea_rs2_capture")

    private fun check(fixture: String) {
        val (pipeline, emlid) = replay(fixture)
        val gnssGolden = read("/golden/$fixture.golden.txt")
        assertEquals("fix/GSV/sky pipeline with Emlid parsers", gnssGolden, pipeline)
        val emlidPath = "/golden/$fixture.emlid.txt"
        if (System.getenv("GEOKIT_WRITE_GOLDEN") == "1") {
            File("src/test/resources$emlidPath").apply { parentFile.mkdirs() }.writeText(emlid)
            return
        }
        assertEquals("Emlid diagnostics", read(emlidPath), emlid)
    }

    private fun read(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText()?.replace("\r\n", "\n") ?: error("Missing golden $path")

    /**
     * A fixture without ZDA/RMC dates gets its date from the device clock (TimestampSource.DEVICE:
     * `LocalDate.now`), so the recorded date would change every day. Pin such fixes to a fixed day,
     * keeping the GNSS time-of-day; fixes dated by the receiver are compared unchanged.
     */
    private fun pinDeviceDate(fix: Fix): Fix =
        if (fix.timestampSource != io.github.pennstatecs.geokit.gnss.model.TimestampSource.DEVICE) fix
        else fix.copy(timeUtc = java.time.LocalDate.of(2000, 1, 1)
            .atTime(fix.timeUtc.atOffset(java.time.ZoneOffset.UTC).toLocalTime())
            .toInstant(java.time.ZoneOffset.UTC))

    /** Same deterministic replay as geokit-gnss's NmeaFixtureGoldenTest (7 ms fake clock per line). */
    private fun replay(fixture: String): Pair<String, String> {
        var nowMs = 1_782_000_000_000L
        val fixes = mutableListOf<Fix>()
        val gsvs = mutableListOf<GsvMessage>()
        val skies = mutableListOf<SkySnapshot>()
        val inventory = SatelliteInventory(nowSeconds = { nowMs / 1000.0 }, debugName = "golden")
        val fuser = NmeaFuser(
            provider = Provider.EXTERNAL_TCP,
            registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true, extraParsers = EmlidNmea.parsers),
            onFix = { fixes += pinDeviceDate(it) },
            onGsv = { gsvs += it; skies += inventory.consume(it) },
            clock = { nowMs },
        )
        val stream = javaClass.getResourceAsStream("/nmea/$fixture.txt") ?: error("Missing fixture $fixture")
        stream.bufferedReader().useLines { seq -> seq.forEach { line -> nowMs += 7; fuser.accept(line) } }
        fuser.flush()
        val pipeline = buildString {
            appendLine("fixture=$fixture")
            appendLine("fixes=${fixes.size}")
            appendLine("fixesSha256=${sha(fixes.joinToString("\n"))}")
            appendLine("gsv=${gsvs.size}")
            appendLine("gsvSha256=${sha(gsvs.joinToString("\n"))}")
            appendLine("sky=${skies.size}")
            appendLine("skySha256=${sha(skies.joinToString("\n"))}")
            appendLine("firstFix=${fixes.firstOrNull()}")
            appendLine("lastFix=${fixes.lastOrNull()}")
            appendLine("lastSky=${skies.lastOrNull()}")
            appendLine("timingStats=${fuser.timingStats}")
        }
        val s = EmlidNmeaDiagnostics.from(fuser.extraSentenceStats)
        val emlid = buildString {
            appendLine("fixture=$fixture")
            appendLine("ebpSeen=${s.ebpSeen}")
            appendLine("etcSeen=${s.etcSeen}")
            appendLine("ebpCount=${s.ebpCount}")
            appendLine("etcCount=${s.etcCount}")
            appendLine("imuOrientationSeen=${s.imuOrientationSeen}")
            appendLine("latestEtc=${s.latestEtc}")
            appendLine("latestEtcHasOrientation=${s.latestEtc?.hasOrientationData}")
        }
        return pipeline to emlid
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
