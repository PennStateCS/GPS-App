package app.surrealar.gnss.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

/**
 * Plain-JVM tests for [NmeaDiagnostics] with a mocked Context / LocationManager and virtual time
 * (the 30 s wait is skipped by runTest). On the stub android.jar Build.VERSION.SDK_INT reads 0, so the
 * GnssStatus callback (API 24+) is never registered and the satellite count stays 0 here: these tests
 * cover the permission / GPS-disabled failures, the NMEA-only partial result and the "nothing" failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NmeaDiagnosticsJvmTest {

    private val lm: LocationManager = Mockito.mock(LocationManager::class.java)

    private fun context(permission: Int = PackageManager.PERMISSION_GRANTED): Context =
        Mockito.mock(Context::class.java).also {
            Mockito.`when`(it.getSystemService(Context.LOCATION_SERVICE)).thenReturn(lm)
            Mockito.`when`(it.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(permission)
        }

    @Test fun initialState_isNotStarted() {
        assertEquals(NmeaDiagnostics.DiagnosticResult.NotStarted, NmeaDiagnostics(context()).diagnosticResult.value)
    }

    @Test fun noPermission_fails() = runTest {
        val d = NmeaDiagnostics(context(PackageManager.PERMISSION_DENIED))
        val r = d.runDiagnostic(1_000)
        assertEquals(NmeaDiagnostics.DiagnosticResult.Failure("Location permission not granted"), r)
        assertEquals(r, d.diagnosticResult.value)
        @Suppress("DEPRECATION")
        Mockito.verify(lm, Mockito.never()).addNmeaListener(Mockito.any(OnNmeaMessageListener::class.java))
    }

    @Test fun gpsDisabled_fails() = runTest {
        Mockito.`when`(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(false)
        val d = NmeaDiagnostics(context())
        val r = d.runDiagnostic(1_000)
        assertEquals(NmeaDiagnostics.DiagnosticResult.Failure("GPS is disabled in device settings"), r)
        assertEquals(r, d.diagnosticResult.value)
    }

    @Test fun nmeaWithoutSatellites_isPartialSuccess_andListenerIsRemoved() = runTest {
        Mockito.`when`(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(true)
        var registered: OnNmeaMessageListener? = null
        @Suppress("DEPRECATION")
        Mockito.doAnswer { inv ->
            val l = inv.getArgument<OnNmeaMessageListener>(0)
            registered = l
            // Deliver lines as the platform would: an empty one first, then seven sentences.
            l.onNmeaMessage("", 0L)
            repeat(7) { i -> l.onNmeaMessage("\$GPGGA,$i*00\r\n", i.toLong()) }
            true
        }.`when`(lm).addNmeaListener(Mockito.any(OnNmeaMessageListener::class.java))

        val d = NmeaDiagnostics(context())
        val r = d.runDiagnostic(5_000)
        assertTrue("was $r", r is NmeaDiagnostics.DiagnosticResult.PartialSuccess)
        r as NmeaDiagnostics.DiagnosticResult.PartialSuccess
        assertEquals(8, r.nmeaMessagesReceived)
        assertEquals(0, r.satellitesVisible)
        assertEquals("NMEA data received but no satellite count. Check GPS signal.", r.message)
        assertEquals(r, d.diagnosticResult.value)
        Mockito.verify(lm).removeNmeaListener(registered!!)
    }

    @Test fun nothingReceived_failsWithDuration() = runTest {
        Mockito.`when`(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(true)
        val d = NmeaDiagnostics(context())
        val r = d.runDiagnostic(2_000)
        assertEquals(
            NmeaDiagnostics.DiagnosticResult.Failure(
                "No NMEA data and no satellites detected after 2000ms. Ensure clear sky view and wait longer."
            ), r
        )
    }

    @Test fun removeListenerThrowing_isSwallowed() = runTest {
        Mockito.`when`(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(true)
        Mockito.doThrow(IllegalStateException("gone")).`when`(lm)
            .removeNmeaListener(Mockito.any(OnNmeaMessageListener::class.java))
        val r = NmeaDiagnostics(context()).runDiagnostic(100)
        assertTrue(r is NmeaDiagnostics.DiagnosticResult.Failure)
    }

    @Test fun resultTypes_areValueObjects() {
        val s = NmeaDiagnostics.DiagnosticResult.Success(3, 12, "\$GPGGA")
        assertEquals(s, s.copy())
        assertEquals(12, s.satellitesVisible)
        val p = NmeaDiagnostics.DiagnosticResult.PartialSuccess(9, 0, "m")
        assertEquals(p, NmeaDiagnostics.DiagnosticResult.PartialSuccess(9, 0, "m"))
        assertTrue(NmeaDiagnostics.DiagnosticResult.Testing is NmeaDiagnostics.DiagnosticResult)
    }
}
