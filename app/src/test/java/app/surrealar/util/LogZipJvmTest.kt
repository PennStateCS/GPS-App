package app.surrealar.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.util.zip.ZipFile

/**
 * [LogZip] on the plain JVM with a mocked [Context] whose directories point into a temp folder:
 * write/close/list, export, quick-share ZIP contents, total size and retention cleanup.
 * Files stay far below the size limit, so no rollover happens.
 */
class LogZipJvmTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: Context
    private lateinit var root: File

    @Before fun setUp() {
        root = tmp.newFolder("app")
        ctx = mock(Context::class.java)
        `when`(ctx.getExternalFilesDir(anyString())).thenAnswer { File(root, it.arguments[0] as String) }
        `when`(ctx.cacheDir).thenReturn(File(root, "cache"))
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.packageName).thenReturn("app.surrealar")
        val pm = mock(PackageManager::class.java)
        val info = PackageInfo().apply { versionName = "9.8.7"; packageName = "app.surrealar"; @Suppress("DEPRECATION") run { versionCode = 42 } }
        `when`(pm.getPackageInfo(anyString(), anyInt())).thenReturn(info)
        `when`(ctx.packageManager).thenReturn(pm)
    }

    private fun logDir(name: String) = File(File(root, "logs"), name)

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).reader().readText() }
    }

    @Test fun `writeLine appends lines to one log file named after the log`() = runBlocking {
        val zip = LogZip(ctx, logName = "nmea")
        zip.writeLine("a")
        zip.writeLine("b")
        zip.close()
        val files = zip.getLogFiles()
        assertEquals(1, files.size)
        assertTrue(files[0].name, files[0].name.matches(Regex("nmea_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.log")))
        assertEquals("a\nb\n", files[0].readText())
    }

    @Test fun `exportLogsToZip copies every log file into the exports folder`() = runBlocking {
        val dir = logDir("gnss").apply { mkdirs() }
        File(dir, "one.log").writeText("1\n")
        File(dir, "two.zip").writeText("zipped")
        File(dir, "sub").mkdirs()
        val out = LogZip(ctx, logName = "gnss").exportLogsToZip()
        assertNotNull(out)
        assertEquals(File(File(root, "exports"), "logs"), out!!.parentFile)
        assertTrue(out.name.startsWith("gnss_export_"))
        assertEquals(mapOf("one.log" to "1\n", "two.zip" to "zipped"), entries(out))
    }

    @Test fun `zipRecent includes the tail of the current file plus config with package info`() = runBlocking {
        val zip = LogZip(ctx, logName = "nmea", maxFileSizeMB = 3, maxRolledFiles = 2)
        (1..1200).forEach { zip.writeLine("L$it") }
        val e = entries(zip.zipRecent()!!)
        val tail = e.getValue("recent_nmea.log").split("\n")
        assertEquals(1000, tail.size)
        assertEquals("L201", tail.first())
        assertEquals("L1200", tail.last())

        val config = e.getValue("app_config.txt")
        assertTrue(config, config.contains("App Version: 9.8.7"))
        assertTrue(config, config.contains("Version Code: 42"))
        assertTrue(config, config.contains("Package: app.surrealar"))
        assertTrue(config, config.contains("Max File Size: 3MB"))
        assertTrue(config, config.contains("Max Rolled Files: 2"))
        assertTrue(config, config.contains("Total Files: 1"))
        assertTrue(config, config.contains("Available Storage: "))

        val device = e.getValue("device_info.txt")
        assertTrue(device, device.contains("Network Available: false"))
        assertTrue(device, device.contains("GPS Enabled: false"))
        assertTrue(device, device.contains("Last GNSS Fix: None"))
        assertTrue(device, device.contains("Bluetooth Available: false"))
        zip.close()
    }

    @Test fun `zipRecent reports when the package info cannot be read`() = runBlocking {
        `when`(ctx.packageManager).thenReturn(null)
        val e = entries(LogZip(ctx, logName = "x").zipRecent()!!)
        assertTrue(e.getValue("app_config.txt").contains("App Version: Unable to retrieve"))
        assertEquals(setOf("app_config.txt", "device_info.txt"), e.keys)
    }

    @Test fun `getTotalSizeMB and cleanup work on the log folder`() = runBlocking {
        val dir = logDir("ret").apply { mkdirs() }
        val files = (0 until 5).map { i ->
            File(dir, "f$i.log").apply { writeBytes(ByteArray(256 * 1024)); setLastModified(10_000L + i * 1_000L) }
        }
        val zip = LogZip(ctx, logName = "ret", maxRolledFiles = 1)
        assertEquals(1.25, zip.getTotalSizeMB(), 1e-9)
        zip.cleanup()
        assertEquals(listOf("f4.log", "f3.log"), zip.getLogFiles().map { it.name })
        assertTrue(files.take(3).none { it.exists() })
        assertEquals(0.5, zip.getTotalSizeMB(), 1e-9)
    }
}
