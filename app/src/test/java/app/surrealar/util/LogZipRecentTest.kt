package app.surrealar.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * [LogZip.zipRecent] (the quick-share ZIP: NMEA tail + app config + device info), [LogZip.getTotalSizeMB]
 * and reopen-after-close. Each test uses its own log name; the files stay small so no rollover happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LogZipRecentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun uniqueName() = "recent_${System.nanoTime()}"
    private fun logDir(name: String) = File(context.getExternalFilesDir("logs"), name)

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).reader().readText() }
    }

    @Test fun `zipRecent holds only the last 1000 lines of the current log plus config and device info`() = runBlocking {
        val name = uniqueName()
        val zip = LogZip(context, logName = name, maxFileSizeMB = 2, maxRolledFiles = 4)
        (1..1005).forEach { zip.writeLine("\$GPGGA,line-$it") }

        val out = zip.zipRecent()
        assertNotNull(out)
        assertEquals(File(context.cacheDir, "temp_logs"), out!!.parentFile)
        assertTrue(out.name.startsWith("nmea_recent_") && out.name.endsWith(".zip"))

        val e = entries(out)
        assertEquals(setOf("recent_nmea.log", "app_config.txt", "device_info.txt"), e.keys)
        val lines = e.getValue("recent_nmea.log").split("\n")
        assertEquals(1000, lines.size)
        assertEquals("\$GPGGA,line-6", lines.first())
        assertEquals("\$GPGGA,line-1005", lines.last())

        val config = e.getValue("app_config.txt")
        assertTrue(config, config.contains("=== App Configuration Report ==="))
        assertTrue(config, config.contains("Log Name: $name"))
        assertTrue(config, config.contains("Max File Size: 2MB"))
        assertTrue(config, config.contains("Max Rolled Files: 4"))
        assertTrue(config, config.contains("Current File: ${name}_"))
        assertTrue(config, config.contains("Total Files: 1"))

        val device = e.getValue("device_info.txt")
        assertTrue(device, device.contains("=== Network Status ==="))
        assertTrue(device, device.contains("=== Location Services ==="))
        // Plain Application (no Hilt graph): the feed lookup fails soft.
        assertTrue(device, device.contains("Last GNSS Fix: None"))
        assertTrue(device, device.contains("=== Bluetooth Status ==="))
        assertTrue(device, device.trimEnd().endsWith("=== Log Generation Complete ==="))
        zip.close()
    }

    @Test fun `zipRecent with no logs omits the NMEA entry and reports no current file`() = runBlocking {
        val zip = LogZip(context, logName = uniqueName())
        val e = entries(zip.zipRecent()!!)
        assertEquals(setOf("app_config.txt", "device_info.txt"), e.keys)
        val config = e.getValue("app_config.txt")
        assertTrue(config, config.contains("Current File: None"))
        assertTrue(config, config.contains("Total Files: 0"))
    }

    @Test fun `zipRecent falls back to the newest log on disk and lists at most ten files`() = runBlocking {
        val name = uniqueName()
        val dir = logDir(name).apply { mkdirs() }
        (0 until 12).forEach { i ->
            File(dir, "old_$i.log").apply { writeText("old-$i-a\nold-$i-b\n"); setLastModified(1_000_000L + i * 1_000L) }
        }
        File(dir, "not_a_log.zip").apply { writeText("zip"); setLastModified(9_000_000L) }

        val e = entries(LogZip(context, logName = name).zipRecent()!!)
        assertEquals("old-11-a\nold-11-b", e.getValue("recent_nmea.log"))
        val config = e.getValue("app_config.txt")
        assertTrue(config, config.contains("Total Files: 13"))
        assertTrue(config, config.contains("... and 3 more files"))
    }

    @Test fun `getTotalSizeMB sums only files in the log dir`() = runBlocking {
        val name = uniqueName()
        val dir = logDir(name).apply { mkdirs() }
        File(dir, "a.log").writeBytes(ByteArray(512 * 1024))
        File(dir, "b.zip").writeBytes(ByteArray(512 * 1024))
        File(dir, "sub").mkdirs()
        assertEquals(1.0, LogZip(context, logName = name).getTotalSizeMB(), 1e-9)
    }

    @Test fun `getTotalSizeMB is zero for an empty log dir`() = runBlocking {
        assertEquals(0.0, LogZip(context, logName = uniqueName()).getTotalSizeMB(), 0.0)
    }

    @Test fun `writing after close reopens a log file and keeps earlier content`() = runBlocking {
        val name = uniqueName()
        val zip = LogZip(context, logName = name)
        zip.writeLine("first")
        zip.close()
        zip.close() // second close is a no-op
        zip.writeLine("second")
        zip.close()
        val all = zip.getLogFiles().joinToString("\n") { it.readText() }
        assertTrue(all, all.contains("first"))
        assertTrue(all, all.contains("second"))
        assertFalse(all, all.contains("third"))
    }
}
