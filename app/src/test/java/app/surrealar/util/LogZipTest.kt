package app.surrealar.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Tests [LogZip] file creation, retention/cleanup, and export so the diagnostic safety net does not
 * silently drop logs. Each test uses a unique log name (isolated temp subdir); no real NMEA or
 * network is involved. Suspend APIs run deterministically under [runTest] (no timing assertions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogZipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun uniqueName() = "test_${System.nanoTime()}"
    private fun logDir(name: String) = File(context.getExternalFilesDir("logs"), name)

    @Test fun `writeLine creates a log file with the written content`() = runTest {
        val name = uniqueName()
        val zip = LogZip(context, logName = name)
        zip.writeLine("hello-nmea-line")
        zip.close()

        val files = zip.getLogFiles()
        assertTrue("expected a log file to be created", files.isNotEmpty())
        assertTrue(files.first().readText().contains("hello-nmea-line"))
    }

    @Test fun `getLogFiles on a fresh logger is empty and safe`() = runTest {
        val zip = LogZip(context, logName = uniqueName())
        assertTrue(zip.getLogFiles().isEmpty())   // no crash, no files
    }

    @Test fun `cleanup retains only the newest current plus maxRolledFiles`() = runTest {
        val name = uniqueName()
        val dir = logDir(name).apply { mkdirs() }
        // 6 files, oldest→newest by lastModified.
        val files = (0 until 6).map { i ->
            File(dir, "nmea_$i.log").apply { writeText("x"); setLastModified(1_000L + i * 1_000L) }
        }
        val zip = LogZip(context, logName = name, maxRolledFiles = 2)  // keep current + 2 = 3
        zip.cleanup()

        val remaining = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(3, remaining.size)
        assertTrue(remaining.containsAll(setOf("nmea_5.log", "nmea_4.log", "nmea_3.log")))   // newest kept
        assertFalse(remaining.contains("nmea_0.log"))                                        // oldest dropped
    }

    @Test fun `exportLogsToZip bundles existing log files into a valid zip`() = runTest {
        val name = uniqueName()
        val zip = LogZip(context, logName = name)
        zip.writeLine("line-a")
        zip.close()

        val out = zip.exportLogsToZip()
        assertTrue("export should produce a zip", out != null && out.exists())
        ZipFile(out!!).use { zf ->
            assertTrue("zip should contain at least one entry", zf.entries().hasMoreElements())
        }
    }

    @Test fun `exportLogsToZip with no logs still produces a safe (empty) zip`() = runTest {
        val zip = LogZip(context, logName = uniqueName())
        val out = zip.exportLogsToZip()
        assertTrue(out != null && out.exists())   // empty but valid, no crash
    }
}
