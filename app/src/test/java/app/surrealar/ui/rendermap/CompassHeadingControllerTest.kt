package app.surrealar.ui.rendermap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

/** The map's single compass listener: demand policy and idempotent register/unregister. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CompassHeadingControllerTest {

    private val sm = ApplicationProvider.getApplicationContext<Context>()
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun controller(provider: () -> SensorManager? = { sm }) = CompassHeadingController(
        sensorManagerProvider = provider,
        displayRotation = { android.view.Surface.ROTATION_0 },
        headingProvider = HeadingProvider(latestFix = { null }),
        onHeadingChanged = {},
    )

    private fun addRotationSensor() {
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR))
    }

    @Test fun `needed only while resumed and wanted by guidance or My Location`() {
        // resumed, guidanceActive, guidanceUsesCompass, showMyLocation
        assertFalse(CompassHeadingController.isNeeded(false, true, true, true))
        assertTrue(CompassHeadingController.isNeeded(true, false, false, true))
        assertTrue(CompassHeadingController.isNeeded(true, true, true, false))
        assertFalse(CompassHeadingController.isNeeded(true, true, false, false))
        assertFalse(CompassHeadingController.isNeeded(true, false, true, false))
        assertFalse(CompassHeadingController.isNeeded(true, false, false, false))
    }

    @Test fun `register is idempotent and unregister releases the listener`() {
        addRotationSensor()
        val c = controller()
        c.sync(true)
        c.sync(true)   // e.g. onResume then a settings change: still one listener
        assertTrue(c.isRegistered)
        assertTrue(shadowOf(sm).hasListener(c.rotationListener))
        assertEquals(1, shadowOf(sm).getListeners().count { it === c.rotationListener })

        c.sync(false)
        assertFalse(c.isRegistered)
        assertFalse(shadowOf(sm).hasListener(c.rotationListener))
        assertNull(c.compassHeadingDeg)
        c.unregister()   // already released: harmless
        assertFalse(c.isRegistered)
    }

    @Test fun `pause-resume cycles never accumulate listeners`() {
        addRotationSensor()
        val c = controller()
        repeat(5) {
            c.sync(CompassHeadingController.isNeeded(true, false, false, true))    // onResume
            assertEquals(1, shadowOf(sm).getListeners().count { it === c.rotationListener })
            c.sync(CompassHeadingController.isNeeded(false, false, false, true))   // onPause
            assertFalse(shadowOf(sm).hasListener(c.rotationListener))
        }
    }

    @Test fun `no rotation sensor - nothing registered, heading unknown`() {
        val c = controller()
        c.register()
        assertFalse(c.isRegistered)
        assertNull(c.compassHeadingDeg)
    }

    @Test fun `a detached host is logged, not thrown`() {
        val c = controller(provider = { throw IllegalStateException("not attached") })
        c.register()
        assertFalse(c.isRegistered)
        assertNull(c.compassHeadingDeg)
    }
}
