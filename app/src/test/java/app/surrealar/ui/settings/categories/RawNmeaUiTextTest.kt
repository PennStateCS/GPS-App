package app.surrealar.ui.settings.categories

import app.surrealar.gnss.diagnostics.raw.RawNmeaMetadataJson
import app.surrealar.gnss.diagnostics.raw.RawNmeaFiles
import app.surrealar.gnss.diagnostics.raw.RawNmeaOrigin
import app.surrealar.gnss.diagnostics.raw.RawNmeaResult
import app.surrealar.gnss.diagnostics.raw.RawNmeaState
import app.surrealar.gnss.diagnostics.raw.RawNmeaStatsSnapshot
import app.surrealar.gnss.diagnostics.raw.RawNmeaTermination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawNmeaUiTextTest {

    private fun finished(t: RawNmeaTermination, complete: Boolean, dropped: Long = 0, writerError: String? = null) =
        RawNmeaState.Finished(RawNmeaResult(File("a.nmea"), File("a.json"), t, complete,
            RawNmeaStatsSnapshot(sentences = 10, bytes = 800, droppedLines = dropped), 30_000, writerError))

    @Test fun `status shows elapsed time against the limit`() {
        val rec = RawNmeaState.Recording(RawNmeaOrigin.INTERNAL, File("a.nmea"), 1_000, 30_000, RawNmeaStatsSnapshot())
        assertEquals("Recording… 00:12 / 00:30", RawNmeaUiText.status(rec, 13_500))
        assertEquals("Recording… 01:05 (manual)", RawNmeaUiText.status(rec.copy(durationLimitMs = null), 66_000))
        assertEquals("Finished — duration reached after 00:30", RawNmeaUiText.status(finished(RawNmeaTermination.DURATION, true), 0))
    }

    @Test fun `an incomplete recording is never shown as complete and data loss is red`() {
        assertTrue(RawNmeaUiText.status(finished(RawNmeaTermination.BUFFER_OVERFLOW, false, 3), 0).startsWith("Finished: INCOMPLETE"))
        assertTrue(RawNmeaUiText.warning(finished(RawNmeaTermination.BUFFER_OVERFLOW, false, 3))!!.startsWith("Data lost: 3 line(s)"))
        assertTrue(RawNmeaUiText.warning(finished(RawNmeaTermination.WRITER_ERROR, false, writerError = "IOException"))!!.contains("could not be written"))
        assertTrue(RawNmeaUiText.warning(finished(RawNmeaTermination.DISCONNECTED, false))!!.contains("incomplete"))
        assertNull(RawNmeaUiText.warning(finished(RawNmeaTermination.USER_STOP, true)))
    }

    @Test fun `recording list detail reflects the companion file`() {
        val f = File("GNSS_x.nmea")
        fun rec(s: RawNmeaMetadataJson.Summary?) = RawNmeaFiles.Recording(f, File("GNSS_x.json"), 2048, 0, s)
        assertEquals("2.0 KB · TCP · complete", RawNmeaUiText.recordingDetail(rec(RawNmeaMetadataJson.Summary(true, "USER_STOP", 0, "EXTERNAL_TCP")), false))
        assertEquals("2.0 KB · tablet GNSS · incomplete (interrupted)", RawNmeaUiText.recordingDetail(rec(RawNmeaMetadataJson.Summary(false, null, 0, "INTERNAL")), false))
        assertEquals("2.0 KB · TCP · INCOMPLETE (receiver disconnected)", RawNmeaUiText.recordingDetail(rec(RawNmeaMetadataJson.Summary(false, "DISCONNECTED", 0, "EXTERNAL_TCP")), false))
        assertEquals("2.0 KB · no metadata (incomplete)", RawNmeaUiText.recordingDetail(rec(null), false))
    }

    @Test fun `file label sanitizing`() {
        assertEquals("RS4Pro", RawNmeaFiles.sanitizeLabel("RS4 Pro"))
        assertEquals("RS2Plus", RawNmeaFiles.sanitizeLabel("RS2+"))
        assertEquals("Receiver", RawNmeaFiles.sanitizeLabel("  /\\ "))
    }
}
