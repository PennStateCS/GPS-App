package app.surrealar.util.diagnostics.report

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.surrealar.data.local.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AppReportReaders] against Robolectric's framework services: battery from the sticky broadcast,
 * permission grants, and the Room table counts / integrity check on an in-memory database.
 * Read-only; nothing leaves the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppReportReadersTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `battery percent and charging come from the sticky battery broadcast`() {
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 40)
            .putExtra(BatteryManager.EXTRA_SCALE, 80)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB))
        val h = AppReportReaders.deviceHealth(app)
        assertEquals(50, h.batteryPercent)
        assertEquals(true, h.charging)
    }

    @Test fun `unplugged battery reads not charging`() {
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 7)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        val h = AppReportReaders.deviceHealth(app)
        assertEquals(7, h.batteryPercent)
        assertEquals(false, h.charging)
    }

    @Test fun `permission map reflects grants and includes the API 31 and 33 entries`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val perms = AppReportReaders.deviceHealth(app).permissions
        assertEquals(true, perms["Fine location"])
        assertEquals(false, perms["Camera"])
        assertTrue(perms.containsKey("Coarse location"))
        assertTrue(perms.containsKey("Bluetooth connect"))
        assertTrue(perms.containsKey("Notifications"))
    }

    @Test fun `device health fills storage memory and provider fields without throwing`() {
        val h = AppReportReaders.deviceHealth(app)
        assertNotNull(h.storageTotalBytes)
        assertNotNull(h.appDataBytes)
        assertNotNull(h.ramTotalBytes)
        assertNotNull(h.network)
        assertNotNull(h.powerSave)
        assertNotNull(h.thermal)
        assertNotNull(h.mockLocationApp)
    }

    @Test fun `data health lists app tables with row counts and an ok integrity check`() {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val h = AppReportReaders.dataHealth(db)
            assertEquals(0L, h.rowCounts["coordinates"])
            assertEquals(0L, h.rowCounts["models"])
            assertFalse(h.rowCounts.keys.any { it.startsWith("sqlite_") })
            assertNull(h.rowCounts["room_master_table"])
            assertNull(h.rowCounts["android_metadata"])
            assertEquals("ok", h.integrity)
            assertNotNull(h.version)
        } finally {
            db.close()
        }
    }
}
