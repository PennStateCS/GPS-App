package app.surrealar.util.diagnostics.report

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.surrealar.data.local.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * [AppReportReaders] on the plain JVM with mocked framework services (the mockable android.jar
 * reports SDK_INT 0, so the API 29+/31+/33+ branches are skipped): battery maths, power/provider
 * flags, mock-location app, app-data size, and the table counts / integrity from a fake database.
 */
class AppReportReadersJvmTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = mock(Context::class.java)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.packageName).thenReturn("app.surrealar")
        val data = tmp.newFolder("data")
        val files = File(data, "files").apply { mkdirs() }
        File(files, "a.bin").writeBytes(ByteArray(1000))
        // Cache outside the data dir here, so the sum below is unambiguous (on a device cacheDir lives
        // under filesDir.parentFile and is counted twice; see report).
        val cache = tmp.newFolder("cache")
        File(cache, "c.bin").writeBytes(ByteArray(24))
        `when`(ctx.filesDir).thenReturn(files)
        `when`(ctx.cacheDir).thenReturn(cache)
    }

    private fun battery(level: Int, scale: Int, plugged: Int) {
        val intent = mock(Intent::class.java)
        `when`(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).thenReturn(level)
        `when`(intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)).thenReturn(scale)
        `when`(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)).thenReturn(plugged)
        `when`(ctx.registerReceiver(isNull(), any(IntentFilter::class.java))).thenReturn(intent)
    }

    @Test fun `battery percent uses level over scale and plugged means charging`() {
        battery(level = 30, scale = 60, plugged = 2)
        val h = AppReportReaders.deviceHealth(ctx)
        assertEquals(50, h.batteryPercent)
        assertEquals(true, h.charging)
    }

    @Test fun `zero scale falls back to 100 and negative level means unknown`() {
        battery(level = 42, scale = 0, plugged = 0)
        assertEquals(42, AppReportReaders.deviceHealth(ctx).batteryPercent)
        assertEquals(false, AppReportReaders.deviceHealth(ctx).charging)
        battery(level = -1, scale = 100, plugged = 0)
        assertNull(AppReportReaders.deviceHealth(ctx).batteryPercent)
    }

    @Test fun `no battery broadcast leaves battery fields null`() {
        val h = AppReportReaders.deviceHealth(ctx)
        assertNull(h.batteryPercent)
        assertNull(h.charging)
    }

    @Test fun `power, providers, network, permissions, app data and mock app are read`() {
        val pm = mock(PowerManager::class.java)
        `when`(pm.isPowerSaveMode).thenReturn(true)
        `when`(pm.isIgnoringBatteryOptimizations(anyString())).thenReturn(true)
        `when`(ctx.getSystemService(PowerManager::class.java)).thenReturn(pm)
        val lm = mock(LocationManager::class.java)
        `when`(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(true)
        `when`(lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)).thenReturn(false)
        `when`(ctx.getSystemService(LocationManager::class.java)).thenReturn(lm)
        `when`(ctx.getSystemService(ConnectivityManager::class.java)).thenReturn(mock(ConnectivityManager::class.java))
        val ops = mock(AppOpsManager::class.java)
        `when`(ops.checkOpNoThrow(anyString(), anyInt(), anyString())).thenReturn(AppOpsManager.MODE_IGNORED)
        `when`(ctx.getSystemService(AppOpsManager::class.java)).thenReturn(ops)
        // Fine location granted, camera denied.
        `when`(ctx.checkPermission(anyString(), anyInt(), anyInt())).thenAnswer {
            if (it.arguments[0] == android.Manifest.permission.CAMERA) -1 else 0
        }

        val h = AppReportReaders.deviceHealth(ctx)
        assertEquals(true, h.powerSave)
        assertNull(h.thermal) // SDK_INT 0 < 29
        assertEquals(true, h.batteryOptimizationIgnored)
        assertEquals(true, h.gpsProvider)
        assertEquals(false, h.networkProvider)
        assertEquals("none", h.network)
        assertEquals("not this app", h.mockLocationApp)
        assertEquals(1024L, h.appDataBytes) // filesDir.parentFile (1000 B) + cacheDir (24 B)
        assertEquals(mapOf("Fine location" to true, "Coarse location" to true, "Camera" to false), h.permissions)
    }

    @Test fun `missing system services read as unknown`() {
        val h = AppReportReaders.deviceHealth(ctx)
        assertNull(h.powerSave)
        assertNull(h.gpsProvider)
        assertEquals("unknown", h.network)
        assertEquals("unknown", h.mockLocationApp)
    }

    @Test fun `mock location app allowed reads as this app`() {
        val ops = mock(AppOpsManager::class.java)
        `when`(ops.checkOpNoThrow(anyString(), anyInt(), anyString())).thenReturn(AppOpsManager.MODE_ALLOWED)
        `when`(ctx.getSystemService(AppOpsManager::class.java)).thenReturn(ops)
        assertEquals("this app (SurReal AR)", AppReportReaders.deviceHealth(ctx).mockLocationApp)
    }

    // ── dataHealth ──────────────────────────────────────────────────────────────

    private fun cursor(rows: List<Any>): Cursor {
        val c = mock(Cursor::class.java)
        var i = -1
        `when`(c.moveToNext()).thenAnswer { i++; i < rows.size }
        `when`(c.moveToFirst()).thenAnswer { i = 0; rows.isNotEmpty() }
        `when`(c.getString(0)).thenAnswer { rows[i].toString() }
        `when`(c.getLong(0)).thenAnswer { (rows[i] as Number).toLong() }
        return c
    }

    private fun database(integrity: (() -> Cursor)?): AppDatabase {
        val sql = mock(SupportSQLiteDatabase::class.java)
        `when`(sql.version).thenReturn(13)
        `when`(sql.query(anyString())).thenAnswer {
            when (val q = it.arguments[0] as String) {
                "SELECT name FROM sqlite_master WHERE type='table'" ->
                    cursor(listOf("coordinates", "models", "sqlite_sequence", "android_metadata", "room_master_table", "empty_t"))
                "SELECT COUNT(*) FROM `coordinates`" -> cursor(listOf(5L))
                "SELECT COUNT(*) FROM `models`" -> cursor(listOf(2L))
                "SELECT COUNT(*) FROM `empty_t`" -> cursor(emptyList())
                "PRAGMA quick_check" -> integrity?.invoke() ?: throw IllegalStateException("locked")
                else -> error("unexpected $q")
            }
        }
        val helper = mock(SupportSQLiteOpenHelper::class.java)
        `when`(helper.readableDatabase).thenReturn(sql)
        val db = mock(AppDatabase::class.java)
        `when`(db.openHelper).thenReturn(helper)
        return db
    }

    @Test fun `data health counts app tables, skips internal ones and joins integrity rows`() {
        val h = AppReportReaders.dataHealth(database { cursor(listOf("ok", "page 3 bad")) })
        assertEquals(13, h.version)
        assertEquals(mapOf("coordinates" to 5L, "models" to 2L, "empty_t" to -1L), h.rowCounts)
        assertEquals("ok; page 3 bad", h.integrity)
    }

    @Test fun `a failing integrity check is reported not thrown`() {
        val h = AppReportReaders.dataHealth(database(null))
        assertTrue(h.integrity, h.integrity == "failed: locked")
    }
}
