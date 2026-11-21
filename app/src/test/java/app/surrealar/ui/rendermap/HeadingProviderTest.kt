package app.surrealar.ui.rendermap

import android.hardware.SensorManager
import android.view.Surface
import app.surrealar.geo.heading.HeadingMath
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Robolectric runs Android's real GeomagneticField / SensorManager math. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadingProviderTest {

    private val nov20 = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun fix(lat: Double, lon: Double) = Fix(
        provider = Provider.RS2_EXTERNAL, timeUtc = Instant.ofEpochMilli(nov20), timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = lat, lonDeg = lon, altEllipsoidalM = 330.0, altMslM = 363.0, geoidSeparationM = -33.0,
        hDop = null, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = null,
    )

    @Test fun `pure constants match Android's`() {
        assertEquals(SensorManager.AXIS_X, HeadingMath.AXIS_X)
        assertEquals(SensorManager.AXIS_Y, HeadingMath.AXIS_Y)
        assertEquals(SensorManager.AXIS_Z, HeadingMath.AXIS_Z)
        assertEquals(SensorManager.AXIS_MINUS_X, HeadingMath.AXIS_MINUS_X)
        assertEquals(SensorManager.AXIS_MINUS_Y, HeadingMath.AXIS_MINUS_Y)
        assertEquals(SensorManager.AXIS_MINUS_Z, HeadingMath.AXIS_MINUS_Z)
        assertEquals(Surface.ROTATION_90, HeadingMath.ROTATION_90)
        assertEquals(Surface.ROTATION_270, HeadingMath.ROTATION_270)
    }

    @Test fun `declination near Hazleton PA is about minus 11 to 12 degrees`() {
        val decl = HeadingProvider.declinationAt(40.95, -75.97, 330.0, nov20)
        println("Declination at 40.95, -75.97 on 2026-11-20: $decl°")
        assertTrue("got $decl", decl in -12.5..-10.5)
    }

    /** Rotation vector (x, y, z, w) for a rotation of [deg] about the world-up axis (device flat). */
    private fun flatYaw(deg: Double): FloatArray {
        val h = Math.toRadians(deg) / 2
        return floatArrayOf(0f, 0f, Math.sin(h).toFloat(), Math.cos(h).toFloat())
    }

    @Test fun `flat device - true heading = magnetic + declination`() {
        val provider = HeadingProvider(latestFix = { fix(40.95, -75.97) })
        // Device flat, top edge rotated 90° CCW from magnetic north (z-rotation +90°) → magnetic heading 270°.
        val h = provider.trueHeadingDeg(flatYaw(90.0), Surface.ROTATION_0, nov20)
        val decl = provider.lastDeclinationDeg!!
        assertEquals(HeadingMath.normalize360(270.0 + decl), h, 0.5)
        assertTrue(!provider.isUpright)
    }

    @Test fun `display rotation changes which edge is "forward"`() {
        val provider = HeadingProvider(latestFix = { null })   // no fix → magnetic only
        val portrait = provider.trueHeadingDeg(flatYaw(0.0), Surface.ROTATION_0, nov20)
        val landscape = provider.trueHeadingDeg(flatYaw(0.0), Surface.ROTATION_90, nov20)
        assertEquals(0.0, portrait, 0.5)
        // Rotated display: forward is the device's +X edge, 90° clockwise of the top edge.
        assertEquals(90.0, landscape, 0.5)
    }

    @Test fun `fallback order - live fix, then last known position, then magnetic (flagged)`() {
        val lastKnown = app.surrealar.gnss.lastknown.LastKnownPosition(40.95, -75.97, nov20 - 86_400_000L)
        var live: Fix? = null
        val provider = HeadingProvider(latestFix = { live }, lastKnownPosition = { lastKnown })

        // No live fix: declination from the last known position, not magnetic.
        val h1 = provider.trueHeadingDeg(flatYaw(0.0), Surface.ROTATION_0, nov20)
        assertEquals(HeadingProvider.DeclinationSource.LAST_KNOWN_POSITION, provider.declinationSource)
        assertTrue(!provider.isMagneticOnly)
        assertEquals(HeadingMath.normalize360(provider.lastDeclinationDeg!!), h1, 0.5)

        // Live fix takes over (forced refresh after > 10 km move).
        live = fix(42.0, -75.97)
        provider.trueHeadingDeg(flatYaw(0.0), Surface.ROTATION_0, nov20 + 1_000)
        assertEquals(HeadingProvider.DeclinationSource.LIVE_FIX, provider.declinationSource)

        // Nothing at all: magnetic, flagged.
        val none = HeadingProvider(latestFix = { null }, lastKnownPosition = { null })
        assertEquals(0.0, none.trueHeadingDeg(flatYaw(0.0), Surface.ROTATION_0, nov20), 0.5)
        assertTrue(none.isMagneticOnly)
        assertEquals(HeadingProvider.DeclinationSource.NONE, none.declinationSource)
    }

    @Test fun `upright device - heading is where the back camera faces`() {
        val provider = HeadingProvider(latestFix = { null })
        // Pitch the device up 90° about its X axis from flat-north: screen faces south, back camera faces north.
        val h = Math.toRadians(90.0) / 2
        val upright = floatArrayOf(Math.sin(h).toFloat(), 0f, 0f, Math.cos(h).toFloat())
        val heading = provider.trueHeadingDeg(upright, Surface.ROTATION_0, nov20)
        assertTrue(provider.isUpright)
        val offNorth = Math.abs(((heading + 180.0) % 360.0) - 180.0)   // circular distance from 0°
        assertTrue("back camera faces north, got $heading", offNorth < 1.0)
    }
}
