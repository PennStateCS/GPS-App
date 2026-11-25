package app.surrealar.ui.settings.receiver

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.settings.GnssReceiverSettings
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.receiver.selection.FakeReceiverSettings
import app.surrealar.receiver.selection.ReceiverConnectionController
import app.surrealar.receiver.selection.ReceiverProbe
import app.surrealar.receiver.selection.TcpProbeResult
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.CoordinateSystemSettings
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.mockito.Mockito
import org.robolectric.Shadows.shadowOf

/** [FakeReceiverSettings] plus the settings the receiver details read (live flows, defaults). */
internal class UiFakeSettings(val core: FakeReceiverSettings = FakeReceiverSettings()) : SettingsRepository by core {
    val coordinates = MutableStateFlow(CoordinateSystemSettings())
    val receiver = MutableStateFlow(GnssReceiverSettings())
    val compensated = MutableStateFlow(CompensatedOutputSetting.DEFAULT)
    override val coordinateSystemSettings: Flow<CoordinateSystemSettings> get() = coordinates
    override val gnssReceiverSettings: Flow<GnssReceiverSettings> get() = receiver
    override val compensatedNmeaOutput: Flow<CompensatedOutputSetting> get() = compensated
}

/** Probe fake: each TCP probe waits for the next queued result (or answers NMEA at once); counts calls. */
internal class QueuedProbe : ReceiverProbe {
    val results = ArrayDeque<CompletableDeferred<TcpProbeResult>>()
    var tcpCalls = 0
    var httpCalls = 0
    var ended = 0
    fun next() = CompletableDeferred<TcpProbeResult>().also { results.addLast(it) }
    override suspend fun tcp(host: String, port: Int): TcpProbeResult {
        tcpCalls++
        val d = results.removeFirstOrNull() ?: CompletableDeferred(TcpProbeResult.RECEIVING_NMEA)
        try { return d.await() } finally { ended++ }
    }
    override suspend fun http(host: String): Boolean { httpCalls++; return false }
}

internal class TestUiDeps(
    override val connection: ReceiverConnectionController,
    override val settings: SettingsRepository,
    val fix: MutableStateFlow<Fix?> = MutableStateFlow(null),
    val sky: MutableStateFlow<SkySnapshot> = MutableStateFlow(SkySnapshot()),
    val deviceInfo: MutableStateFlow<ReceiverDeviceInfo?> = MutableStateFlow(null),
    val battery: MutableStateFlow<ReceiverBattery?> = MutableStateFlow(null),
    val corrections: MutableStateFlow<ReceiverCorrections?> = MutableStateFlow(null),
    val capabilities: MutableStateFlow<ReceiverCapabilities> = MutableStateFlow(ReceiverCapabilities.ALL),
    override val nmeaLinesPerSecond: Flow<Double> = flowOf(5.0),
) : ReceiverUiDeps {
    override val fixSwitchboard: FixSwitchboard = Mockito.mock(FixSwitchboard::class.java).also {
        Mockito.`when`(it.currentFix).thenReturn(fix)
        Mockito.`when`(it.sky).thenReturn(sky)
    }
    override val devices: ReceiverDeviceManager = Mockito.mock(ReceiverDeviceManager::class.java).also {
        Mockito.`when`(it.deviceInfo).thenReturn(deviceInfo)
        Mockito.`when`(it.battery).thenReturn(battery)
        Mockito.`when`(it.corrections).thenReturn(corrections)
        Mockito.`when`(it.profileCapabilities).thenReturn(capabilities)
        Mockito.`when`(it.capabilities).thenReturn(capabilities)
    }
}

internal fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

/** All TextView texts under [root] (depth first). */
internal fun texts(root: View): List<String> {
    val out = mutableListOf<String>()
    fun walk(v: View) {
        if (v is TextView && v.visibility == View.VISIBLE && v.text.isNotEmpty()) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(root)
    return out
}

/** The row under [root] whose title is [title]. */
internal fun rowTitled(root: View, title: String): View {
    fun find(v: View): View? {
        if (v.id == app.surrealar.R.id.text_picker_row_title && (v as TextView).text.toString() == title) return v.parent.parent as View
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
    return find(root) ?: error("no row \"$title\" in ${texts(root)}")
}
