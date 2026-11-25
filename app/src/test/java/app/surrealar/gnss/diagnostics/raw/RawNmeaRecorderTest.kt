package app.surrealar.gnss.diagnostics.raw

import app.surrealar.gnss.diagnostics.raw.NmeaLineClassifier.SentenceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId

/**
 * RawNmeaRecorder with virtual time. All sentences here are SYNTHETIC (built in the test with a computed
 * checksum, or deliberately wrong); none is receiver output.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RawNmeaRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val provider = MutableStateFlow(ProviderChoice.INTERNAL)
    private val link = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val profileKey = MutableStateFlow<String?>("reach_rs4_pro")

    private val externalMeta = RawNmeaSourceMetadata(
        profileKey = "reach_rs4_pro", profileLabel = "Emlid Reach RS4 Pro", fileLabel = "RS4 Pro",
        compensatedOutput = "DISABLED", appVersion = "test",
    )

    private fun TestScope.recorder(
        capacity: Int = 4096,
        maxBytes: Long = RawNmeaRecorder.MAX_FILE_BYTES,
        scope: CoroutineScope = detachedScope(),
        openOutput: (File) -> OutputStream = { java.io.FileOutputStream(it) },
        meta: (RawNmeaOrigin) -> RawNmeaSourceMetadata = { o ->
            if (o == RawNmeaOrigin.INTERNAL) RawNmeaSourceMetadata(fileLabel = "Internal") else externalMeta
        },
    ): RawNmeaRecorder = RawNmeaRecorder(
        scope = scope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        files = RawNmeaFiles { File(tmp.root, RawNmeaFiles.DIR_NAME) },
        metadata = meta,
        wallClockMs = { 1_795_617_012_000L + testScheduler.currentTime },   // 2026-11-25T14:30:12Z + virtual time
        monoClockMs = { testScheduler.currentTime },
        zone = { ZoneId.of("America/New_York") },
        queueCapacity = capacity,
        maxFileBytes = maxBytes,
        openOutput = openOutput,
        statsPublishIntervalMs = 0,
    ).also { it.watchSource(provider, link, profileKey); runCurrent() }

    /**
     * Not backgroundScope (advanceUntilIdle skips background work) and not a child of the test (runTest would wait
     * for the never-ending watchSource collectors): a foreground scope on the test scheduler.
     */
    private fun TestScope.detachedScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    private fun finished(r: RawNmeaRecorder): RawNmeaResult =
        (r.state.value as? RawNmeaState.Finished)?.result ?: error("not finished: ${r.state.value}")

    private fun text(f: File) = String(f.readBytes(), Charsets.UTF_8)

    // ── start / stop ─────────────────────────────────────────────────────────────

    @Test fun `start and user stop write each line verbatim with CRLF and a complete companion file`() = runTest {
        val r = recorder()
        val lines = listOf(gga("120000.00", "4118.4442077"), rmc("120000.00", "4118.4442077"), nmea("GNGST,120000.00,2.7,,,,0.010,0.010,0.016"))
        assertTrue(r.start(RawNmeaDuration.MANUAL) is RawNmeaRecorder.StartResult.Started)
        lines.forEach { r.offer(RawNmeaOrigin.INTERNAL, it) }
        runCurrent()
        r.stop()
        advanceUntilIdle()

        val res = finished(r)
        assertEquals(RawNmeaTermination.USER_STOP, res.termination)
        assertTrue(res.complete)
        assertEquals(lines.joinToString("") { "$it\r\n" }, text(res.nmeaFile))
        assertEquals(3L, res.stats.sentences)
        assertEquals(res.nmeaFile.length(), res.stats.bytes)
        assertEquals(1L, res.stats.count(SentenceType.GGA))
        assertEquals(1L, res.stats.count(SentenceType.RMC))
        assertEquals(1L, res.stats.count(SentenceType.GST))
        assertEquals(3L, res.stats.checksumValid)
        val json = res.metadataFile.readText()
        assertTrue(json, json.contains("\"terminationReason\": \"USER_STOP\""))
        assertTrue(json, json.contains("\"complete\": true"))
        assertTrue(json, json.contains("\"provider\": \"INTERNAL\""))
        assertTrue(json, json.contains("\"compensatedOutput\": null"))
        assertTrue(res.nmeaFile.name, res.nmeaFile.name.matches(Regex("GNSS_2026-11-25_093012_Internal\\.nmea")))
    }

    @Test fun `companion file is written at start as incomplete (process death keeps complete=false)`() = runTest {
        val r = recorder()
        val started = r.start(RawNmeaDuration.MANUAL) as RawNmeaRecorder.StartResult.Started
        val json = r.files.metadataFor(started.nmeaFile).readText()
        assertTrue(json.contains("\"complete\": false"))
        assertTrue(json.contains("\"terminationReason\": null"))
        assertTrue(json.contains("\"endedAtUtc\": null"))
        assertTrue(json.contains("\"startedAtUtc\": \"2026-11-25T14:30:12Z\""))
        assertTrue(json.contains("\"deviceTimeZone\": \"America/New_York\""))
        r.stop(); advanceUntilIdle()
    }

    @Test fun `timed recording stops at its duration in virtual time`() = runTest {
        val r = recorder()
        r.start(RawNmeaDuration.S10)
        r.offer(RawNmeaOrigin.INTERNAL, gga("120000.00", "4118.0"))
        advanceTimeBy(9_999); runCurrent()
        assertTrue(r.state.value is RawNmeaState.Recording)
        advanceTimeBy(2); runCurrent()
        advanceUntilIdle()
        val res = finished(r)
        assertEquals(RawNmeaTermination.DURATION, res.termination)
        assertTrue(res.complete)
        assertEquals(10_000L, res.durationMs)
        assertTrue(res.metadataFile.readText().contains("\"requestedDurationS\": 10"))
        // Lines after the stop are not recorded.
        r.offer(RawNmeaOrigin.INTERNAL, gga("120011.00", "4118.0"))
        advanceUntilIdle()
        assertEquals(1L, finished(r).stats.sentences)
    }

    @Test fun `size limit stops before a line that would exceed it and keeps only whole lines`() = runTest {
        val line = gga("120000.00", "4118.4442077")          // ~80 bytes with CRLF
        val r = recorder(maxBytes = (line.length + 2) * 3L + 10)
        r.start(RawNmeaDuration.MANUAL)
        repeat(6) { r.offer(RawNmeaOrigin.INTERNAL, line) }
        advanceUntilIdle()
        val res = finished(r)
        assertEquals(RawNmeaTermination.SIZE_LIMIT, res.termination)
        assertTrue(res.complete)
        assertEquals(3L, res.stats.sentences)
        assertEquals(List(3) { "$line\r\n" }.joinToString(""), text(res.nmeaFile))
    }

    @Test fun `manual mode is bounded by the default 10 MB limit`() = runTest {
        assertEquals(10L * 1024 * 1024, RawNmeaRecorder.MAX_FILE_BYTES)
    }

    // ── preservation ─────────────────────────────────────────────────────────────

    @Test fun `invalid checksums, unknown proprietary and malformed lines are preserved verbatim and counted`() = runTest {
        val good = gga("120000.00", "4118.0")
        val badChecksum = good.dropLast(2) + (if (good.endsWith("00")) "01" else "00")
        val proprietary = nmea("PXYZQ,1,2,3")                       // SYNTHETIC unknown proprietary sentence
        val noChecksum = "\$PEMLID,SYNTHETIC,no,checksum"
        val unknownType = nmea("GNQQQ,1,2")
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        listOf(good, badChecksum, proprietary, noChecksum, unknownType).forEach { r.offer(RawNmeaOrigin.INTERNAL, it) }
        r.stop(); advanceUntilIdle()
        val res = finished(r)
        assertEquals(listOf(good, badChecksum, proprietary, noChecksum, unknownType).joinToString("") { "$it\r\n" }, text(res.nmeaFile))
        assertEquals(3L, res.stats.checksumValid)
        assertEquals(1L, res.stats.checksumInvalid)
        assertEquals(1L, res.stats.malformed)
        assertEquals(3L, res.stats.count(SentenceType.OTHER))
        val json = res.metadataFile.readText()
        assertTrue(json.contains("\"invalid\": 1"))
        assertTrue(json.contains("\"malformed\": 1"))
    }

    @Test fun `GGA and RMC that disagree are kept exactly as received, in order`() = runTest {
        val lines = listOf(gga("120000.00", "4118.4442077"), rmc("120000.00", "4118.9999999"), gga("120000.20", "4118.4442001"))
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        lines.forEach { r.offer(RawNmeaOrigin.INTERNAL, it) }
        r.stop(); advanceUntilIdle()
        assertEquals(lines.joinToString("") { "$it\r\n" }, text(finished(r).nmeaFile))
    }

    @Test fun `ETC transitions (tilt on, off, notification) are preserved and counted`() = runTest {
        provider.value = ProviderChoice.EXTERNAL_TCP; link.value = ConnectionState.CONNECTED
        val lines = listOf(
            nmea("GNETC,120000.00,30,00,268.660,116.146,17.651,6.280,6.280,6.991"),   // SYNTHETIC: tilt data
            nmea("GNETC,120000.20"),                                                  // SYNTHETIC: time only
            nmea("GNETC,120000.40,30,01,268.660,116.146,17.651,6.280,6.280,6.991"),   // SYNTHETIC: other state
            nmea("GNEBP,4118.3990546,N,07601.0359618,W,374.038,M"),
        )
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        lines.forEach { r.offer(RawNmeaOrigin.EXTERNAL_TCP, it) }
        r.stop(); advanceUntilIdle()
        val res = finished(r)
        assertEquals(lines.joinToString("") { "$it\r\n" }, text(res.nmeaFile))
        assertEquals(3L, res.stats.count(SentenceType.ETC))
        assertEquals(1L, res.stats.count(SentenceType.EBP))
        assertTrue(res.nmeaFile.name.endsWith("_RS4Pro.nmea"))
        val json = res.metadataFile.readText()
        assertTrue(json.contains("\"connectionType\": \"TCP\""))
        assertTrue(json.contains("\"profile\": \"reach_rs4_pro\""))
        assertTrue(json.contains("\"reportedModel\": null"))
        assertTrue(json.contains("\"reportedSerial\": null"))
        assertTrue(json.contains("\"setting\": \"DISABLED\""))
        assertTrue(json.contains("\"confirmed\": true"))
        assertFalse(json.contains("host"))
    }

    @Test fun `internal messages lose only their trailing CR LF`() = runTest {
        val s = gga("120000.00", "4118.0")
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        r.offer(RawNmeaOrigin.INTERNAL, "$s\r\n")
        r.stop(); advanceUntilIdle()
        assertEquals("$s\r\n", text(finished(r).nmeaFile))
    }

    @Test fun `lines from the other source are ignored`() = runTest {
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        r.offer(RawNmeaOrigin.EXTERNAL_TCP, gga("120000.00", "4118.0"))
        r.stop(); advanceUntilIdle()
        assertEquals(0L, finished(r).stats.sentences)
    }

    // ── availability and stop triggers ───────────────────────────────────────────

    @Test fun `replay and a disconnected receiver are not recordable`() = runTest {
        val r = recorder()
        provider.value = ProviderChoice.REPLAY; runCurrent()
        val res = r.start(RawNmeaDuration.MANUAL)
        assertTrue(res is RawNmeaRecorder.StartResult.Unavailable)
        assertTrue((res as RawNmeaRecorder.StartResult.Unavailable).reason.contains("not a live source"))
        provider.value = ProviderChoice.EXTERNAL_TCP; runCurrent()
        assertTrue(r.availability.value is RawNmeaAvailability.Unavailable)
        link.value = ConnectionState.CONNECTED; runCurrent()
        assertEquals(RawNmeaAvailability.Available(RawNmeaOrigin.EXTERNAL_TCP), r.availability.value)
        assertTrue(r.files.list().isEmpty())
    }

    @Test fun `receiver disconnect finalizes as DISCONNECTED and incomplete`() = runTest {
        provider.value = ProviderChoice.EXTERNAL_TCP; link.value = ConnectionState.CONNECTED
        val r = recorder()
        r.start(RawNmeaDuration.S60)
        r.offer(RawNmeaOrigin.EXTERNAL_TCP, gga("120000.00", "4118.0"))
        runCurrent()
        link.value = ConnectionState.RECONNECTING
        advanceUntilIdle()
        val res = finished(r)
        assertEquals(RawNmeaTermination.DISCONNECTED, res.termination)
        assertFalse(res.complete)
        assertEquals(1L, res.stats.sentences)
        assertTrue(res.metadataFile.readText().contains("\"complete\": false"))
    }

    @Test fun `source change and receiver profile change finalize as SOURCE_CHANGED`() = runTest {
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        provider.value = ProviderChoice.EXTERNAL_TCP
        advanceUntilIdle()
        assertEquals(RawNmeaTermination.SOURCE_CHANGED, finished(r).termination)
        assertFalse(finished(r).complete)

        link.value = ConnectionState.CONNECTED; runCurrent()
        r.start(RawNmeaDuration.MANUAL)
        profileKey.value = "reach_rs2_plus"
        advanceUntilIdle()
        assertEquals(RawNmeaTermination.SOURCE_CHANGED, finished(r).termination)
    }

    // ── failures ─────────────────────────────────────────────────────────────────

    @Test fun `writer failure marks the recording incomplete and counts unwritten lines as dropped`() = runTest {
        val r = recorder(openOutput = { throw IOException("SYNTHETIC disk full") })
        r.start(RawNmeaDuration.MANUAL)
        repeat(4) { r.offer(RawNmeaOrigin.INTERNAL, gga("120000.00", "4118.0")) }
        advanceUntilIdle()
        val res = finished(r)
        assertEquals(RawNmeaTermination.WRITER_ERROR, res.termination)
        assertFalse(res.complete)
        assertEquals("IOException", res.writerError)
        assertEquals(4L, res.stats.droppedLines)
        val json = res.metadataFile.readText()
        assertTrue(json.contains("\"writerError\": \"IOException\""))
        assertTrue(json.contains("\"droppedLines\": 4"))
    }

    @Test fun `a failure while flushing still ends as WRITER_ERROR, even after a user stop`() = runTest {
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("SYNTHETIC")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("SYNTHETIC")
        }
        val r = recorder(openOutput = { failing })
        r.start(RawNmeaDuration.MANUAL)
        r.offer(RawNmeaOrigin.INTERNAL, gga("120000.00", "4118.0"))
        r.stop()
        advanceUntilIdle()
        val res = finished(r)
        assertEquals(RawNmeaTermination.WRITER_ERROR, res.termination)
        assertFalse(res.complete)
    }

    @Test fun `queue overflow drops and counts, ends as BUFFER_OVERFLOW, never complete`() = runTest {
        val r = recorder(capacity = 2)
        r.start(RawNmeaDuration.MANUAL)
        // The writer has not run yet (no runCurrent): the third offer finds the queue full.
        repeat(5) { r.offer(RawNmeaOrigin.INTERNAL, gga("12000$it.00", "4118.0")) }
        advanceUntilIdle()
        val res = finished(r)
        assertEquals(RawNmeaTermination.BUFFER_OVERFLOW, res.termination)
        assertFalse(res.complete)
        assertEquals(1L, res.stats.droppedLines)
        assertEquals(2L, res.stats.sentences)
        assertTrue(res.metadataFile.readText().contains("\"droppedLines\": 1"))
    }

    @Test fun `lifecycle stop and scope cancellation finalize safely as LIFECYCLE`() = runTest {
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        r.offer(RawNmeaOrigin.INTERNAL, gga("120000.00", "4118.0"))
        r.stop(RawNmeaTermination.LIFECYCLE)
        advanceUntilIdle()
        assertEquals(RawNmeaTermination.LIFECYCLE, finished(r).termination)
        assertFalse(finished(r).complete)

        val job = Job()
        val r2 = recorder(scope = CoroutineScope(coroutineContext + job))
        r2.start(RawNmeaDuration.MANUAL)
        r2.offer(RawNmeaOrigin.INTERNAL, gga("120000.00", "4118.0"))
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        val res = finished(r2)
        assertEquals(RawNmeaTermination.LIFECYCLE, res.termination)
        assertFalse(res.complete)
        assertTrue(res.metadataFile.readText().contains("\"terminationReason\": \"LIFECYCLE\""))
        assertFalse(r2.isRecording)
    }

    @Test fun `only one recording at a time and the list shows both files`() = runTest {
        val r = recorder()
        r.start(RawNmeaDuration.MANUAL)
        assertEquals(RawNmeaRecorder.StartResult.AlreadyRecording, r.start(RawNmeaDuration.MANUAL))
        r.stop(); advanceUntilIdle()
        val list = r.files.list()
        assertEquals(1, list.size)
        assertTrue(list[0].summary!!.complete)
        assertEquals("USER_STOP", list[0].summary!!.termination)
        assertTrue(r.files.delete(list[0].nmeaFile))
        assertTrue(r.files.list().isEmpty())
    }

    @Test fun `receiver-reported identity is recorded only when reported`() = runTest {
        provider.value = ProviderChoice.EXTERNAL_TCP; link.value = ConnectionState.CONNECTED
        var reported: String? = null
        val r = recorder(meta = { externalMeta.copy(reportedFirmware = reported) })
        r.start(RawNmeaDuration.MANUAL)
        reported = "SYNTHETIC-fw-1"          // the device-info poll answers during the recording
        r.stop(); advanceUntilIdle()
        val json = finished(r).metadataFile.readText()
        assertTrue(json.contains("\"reportedFirmware\": \"SYNTHETIC-fw-1\""))
        assertTrue(json.contains("\"reportedModel\": null"))
    }

    companion object {
        /** SYNTHETIC sentence: `$body*HH` with the correct checksum. */
        fun nmea(body: String): String {
            var cs = 0
            for (c in body) cs = cs xor c.code
            return "\$$body*" + String.format("%02X", cs)
        }
        fun gga(time: String, lat: String) = nmea("GNGGA,$time,$lat,N,07601.0311556,W,4,25,1.0,396.456,M,-33.145,M,1.4,1116")
        fun rmc(time: String, lat: String) = nmea("GNRMC,$time,A,$lat,N,07601.0311842,W,0.87,72.59,251126,,,D")
    }
}
