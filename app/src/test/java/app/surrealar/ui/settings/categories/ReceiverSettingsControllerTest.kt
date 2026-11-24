package app.surrealar.ui.settings.categories

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import app.surrealar.R
import app.surrealar.domain.model.ExternalConnectionType
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.gnss.source.GnssSourceCoordinator
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Receiver category connect flow against a local TCP "receiver" (no device): a probe that finds NMEA
 * activates the external provider through the coordinator; a disconnect while the probe is in flight
 * switches to device GNSS and the superseded attempt never activates External. Runs on the real
 * (Robolectric) main looper; the probes use real sockets on 127.0.0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReceiverSettingsControllerTest {

    private lateinit var server: ServerSocket
    private val respond = CountDownLatch(1)
    private val accepted = AtomicInteger()
    /** Connections the fake receiver has answered with NMEA (so a probe on them has its result). */
    private val served = AtomicInteger()

    @Before fun startReceiver() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                accepted.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching {
                        respond.await(15, TimeUnit.SECONDS)
                        s.getOutputStream().write("\$GPGGA,120000.00,4114.0,N,07552.0,W,4,12,0.8,180.0,M,-34.0,M,1.0,0000*4A\r\n".toByteArray())
                        s.getOutputStream().flush()
                        served.incrementAndGet()
                        Thread.sleep(1_000)
                    }
                    runCatching { s.close() }
                }
            }
        }
    }

    @After fun stopReceiver() {
        respond.countDown()
        server.close()
    }

    private fun settings(): SettingsRepository = Mockito.mock(SettingsRepository::class.java).also {
        Mockito.`when`(it.externalReceiverProfile).thenReturn(flowOf(ExternalReceiverProfile.DEFAULT))
        Mockito.`when`(it.externalTcpHost).thenReturn(flowOf(null))
        Mockito.`when`(it.externalTcpPort).thenReturn(flowOf(null))
        Mockito.`when`(it.externalTcpName).thenReturn(flowOf(null))
        Mockito.`when`(it.locationSource).thenReturn(flowOf(LocationSourceType.EXTERNAL))
        Mockito.`when`(it.externalConnType).thenReturn(flowOf(ExternalConnectionType.TCP))
        Mockito.`when`(it.gnssReceiverSettings).thenReturn(flowOf(GnssReceiverSettings()))
        Mockito.`when`(it.mockLocationEnabled).thenReturn(flowOf(false))
    }

    private fun switchboard(): FixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also {
        Mockito.`when`(it.currentFix).thenReturn(MutableStateFlow<Fix?>(null))
        Mockito.`when`(it.sky).thenReturn(MutableStateFlow(SkySnapshot()))
        Mockito.`when`(it.rawNmea).thenReturn(MutableSharedFlow())
    }

    private fun devices(): ReceiverDeviceManager = Mockito.mock(ReceiverDeviceManager::class.java).also {
        Mockito.`when`(it.profileCapabilities).thenReturn(MutableStateFlow(ReceiverCapabilities.NONE))
        Mockito.`when`(it.deviceInfo).thenReturn(MutableStateFlow(null))
        Mockito.`when`(it.battery).thenReturn(MutableStateFlow(null))
        Mockito.`when`(it.corrections).thenReturn(MutableStateFlow(null))
    }

    private val coordinator: GnssSourceCoordinator = Mockito.mock(GnssSourceCoordinator::class.java)

    private fun pumpUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun activations() = Mockito.mockingDetails(coordinator).invocations
        .count { it.method.name == "activateExternalTcpProvider" }

    private fun showAndConnect(): View {
        val host = ControllerHost()
        val controller = ReceiverSettingsController(host.fragment, settings(), switchboard(), coordinator, devices(),
            mainScope(), refreshCategoriesForSource = {})
        val view = host.show(controller, mainScope())
        shadowOf(Looper.getMainLooper()).idle()
        view.findViewById<EditText>(R.id.edit_host).setText("127.0.0.1")
        view.findViewById<EditText>(R.id.edit_port).setText(server.localPort.toString())
        view.findViewById<Button>(R.id.btn_connect).performClick()
        return view
    }

    @Test fun `a probe that finds the receiver activates the external provider through the coordinator`() {
        respond.countDown()
        showAndConnect()

        assertTrue("external provider activated", pumpUntil(12_000) { activations() > 0 })
        Mockito.verify(coordinator).activateExternalTcpProvider("127.0.0.1", server.localPort, "settings-connect #1")
    }

    @Test fun `disconnecting during the probe switches to device GNSS and the old attempt never activates External`() {
        val view = showAndConnect()
        assertTrue("probe connected", pumpUntil(5_000) { accepted.get() >= 1 })

        view.findViewById<Button>(R.id.btn_disconnect_device).performClick()
        Mockito.verify(coordinator, Mockito.atLeastOnce()).activateInternalProvider("settings")

        respond.countDown()                       // the receiver answers after the user left External
        // Wait until every probe connection really got its NMEA (so the superseded attempt has a result
        // to deliver), then let the main looper run its continuation: only then is "never" meaningful.
        assertTrue("receiver answered every probe", pumpUntil(10_000) { served.get() >= accepted.get() })
        pumpUntil(1_000) { false }
        Mockito.verify(coordinator, Mockito.never()).activateExternalTcpProvider(anyString(), anyInt(), anyString())
    }
}
