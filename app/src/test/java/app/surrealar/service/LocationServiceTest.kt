package app.surrealar.service

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `not started without location permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        while (shadowOf(app).nextStartedService != null) { /* drain services started during app startup */ }
        assertFalse(LocationService.start(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `started with permission`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        while (shadowOf(app).nextStartedService != null) { }
        assertTrue(LocationService.start(app))
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    }

    @Test fun `not sticky`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val svc = Robolectric.buildService(LocationService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(Intent("app.surrealar.action.START_LOCATION"), 0, 1))
    }
}