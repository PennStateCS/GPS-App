package io.github.pennstatecs.geokit.gnss.golden

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.nmea.parse.NmeaRegistry
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Golden regression for the whole NMEA pipeline: every fixture in `src/test/resources/nmea/` is fed
 * through [NmeaFuser] (live-style registry: checksums verified and required) with a deterministic
 * clock, and the complete sequence of fused [Fix] values, GSV epochs and [SkySnapshot]s plus the final
 * timing stats must match `src/test/resources/golden/<fixture>.golden.txt` exactly.
 *
 * The goldens were recorded before the NMEA extension refactor (when EBP/ETC were still built in) and
 * this test runs with the plain [DefaultNmeaRegistry] - no vendor parsers - so it also proves that
 * vendor sentences never influence fixes. geokit-receiver-emlid's golden test runs the same fixtures
 * with the Emlid parsers registered against the same files.
 *
 * Regenerate (only after an intended behaviour change): set `GEOKIT_WRITE_GOLDEN=1` and run the test.
 */
class NmeaFixtureGoldenTest {

    @Test fun `reach RS4 capture matches golden`() = check("reach_nmea_capture")
    @Test fun `reach RS4 IMU capture matches golden`() = check("reach_nmea_withimu")
    @Test fun `reach RS2 capture matches golden`() = check("reach_nmea_rs2_capture")

    private fun check(fixture: String) {
        val actual = goldenText(fixture, DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true))
        val goldenPath = "/golden/$fixture.golden.txt"
        if (System.getenv("GEOKIT_WRITE_GOLDEN") == "1") {
            File("src/test/resources$goldenPath").apply { parentFile.mkdirs() }.writeText(actual)
            return
        }
        val expected = javaClass.getResourceAsStream(goldenPath)?.bufferedReader()?.readText()?.replace("\r\n", "\n")
            ?: error("Missing golden $goldenPath")
        assertEquals(expected, actual)
    }

    companion object {
        /** Deterministic replay of a fixture: 7 ms of fake wall clock per line. */
        fun goldenText(fixture: String, registry: NmeaRegistry): String {
            var nowMs = 1_782_000_000_000L
            val fixes = mutableListOf<Fix>()
            val gsvs = mutableListOf<GsvMessage>()
            val skies = mutableListOf<SkySnapshot>()
            val inventory = SatelliteInventory(nowSeconds = { nowMs / 1000.0 }, debugName = "golden")
            val fuser = NmeaFuser(
                provider = Provider.EXTERNAL_TCP,
                registry = registry,
                onFix = { fixes += it },
                onGsv = { gsvs += it; skies += inventory.consume(it) },
                clock = { nowMs },
            )
            val stream = NmeaFixtureGoldenTest::class.java.getResourceAsStream("/nmea/$fixture.txt")
                ?: error("Missing fixture $fixture")
            stream.bufferedReader().useLines { seq ->
                seq.forEach { line -> nowMs += 7; fuser.accept(line) }
            }
            fuser.flush()
            return buildString {
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
        }

        private fun sha(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
