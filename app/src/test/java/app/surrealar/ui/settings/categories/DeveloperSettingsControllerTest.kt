package app.surrealar.ui.settings.categories

import android.app.Application
import android.content.DialogInterface
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.surrealar.R
import app.surrealar.domain.repository.DeveloperSettingsRepository
import app.surrealar.gnss.diagnostics.raw.RawNmeaDuration
import app.surrealar.gnss.diagnostics.raw.RawNmeaFiles
import app.surrealar.gnss.diagnostics.raw.RawNmeaOrigin
import app.surrealar.gnss.diagnostics.raw.RawNmeaRecorder
import app.surrealar.gnss.diagnostics.raw.RawNmeaSourceMetadata
import app.surrealar.gnss.diagnostics.raw.RawNmeaState
import app.surrealar.gnss.diagnostics.raw.RawNmeaTermination
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import app.surrealar.settings.model.DeveloperSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeveloperSettingsControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeRepo(initial: DeveloperSettings, ack: Boolean = false) : DeveloperSettingsRepository {
        val state = MutableStateFlow(initial)
        val saved = mutableListOf<DeveloperSettings>()
        val ack = MutableStateFlow(ack)
        override val developerSettings: Flow<DeveloperSettings> = state
        override suspend fun setDeveloperSettings(settings: DeveloperSettings) { saved += settings; state.value = settings }
        override val rawNmeaPrivacyAcknowledged: Flow<Boolean> = this.ack
        override suspend fun setRawNmeaPrivacyAcknowledged(value: Boolean) { ack.value = value }
    }

    private val provider = MutableStateFlow(ProviderChoice.REPLAY)

    private fun recorder(): RawNmeaRecorder = RawNmeaRecorder(
        scope = mainScope(),
        ioDispatcher = Dispatchers.Main,
        files = RawNmeaFiles { File(tmp.root, "rec") },
        metadata = { RawNmeaSourceMetadata(fileLabel = "Internal") },
        monoClockMs = { 0L },
    ).also { it.watchSource(provider, MutableStateFlow(ConnectionState.DISCONNECTED), MutableStateFlow(null)) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `binds and persists the developer tools switch`() {
        val repo = FakeRepo(DeveloperSettings(developerToolsEnabled = true))
        val host = ControllerHost()
        val view = host.show(DeveloperSettingsController(host.fragment, repo, mainScope(), recorder(), Dispatchers.Main), mainScope())
        val switch = view.findViewById<MaterialSwitch>(R.id.switch_dev_tools)

        assertTrue(switch.isChecked)
        repo.saved.clear()             // in the app the async bind may re-save the same value; only the user change matters here
        switch.isChecked = false
        assertEquals(listOf(DeveloperSettings(developerToolsEnabled = false)), repo.saved)
    }

    @Test fun `raw NMEA - replay is unavailable, privacy notice before the first start, leaving the page stops`() {
        val repo = FakeRepo(DeveloperSettings(developerToolsEnabled = true), ack = false)
        val rec = recorder()
        val host = ControllerHost()
        val categoryJob = Job()
        val controller = DeveloperSettingsController(host.fragment, repo, mainScope(), rec, Dispatchers.Main)
        val view = host.show(controller, CoroutineScope(Dispatchers.Main + categoryJob))
        val availability = view.findViewById<TextView>(R.id.text_raw_nmea_availability)
        val start = view.findViewById<MaterialButton>(R.id.button_raw_nmea_start)

        assertTrue(availability.text.toString(), availability.text.contains("not a live source"))
        assertFalse(start.isEnabled)

        provider.value = ProviderChoice.INTERNAL
        ShadowLooper.idleMainLooper()
        assertTrue(availability.text.toString(), availability.text.contains("tablet GNSS"))
        assertTrue(start.isEnabled)

        start.performClick()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(repo.ack.value)
        assertTrue(rec.state.value is RawNmeaState.Recording)
        assertEquals(RawNmeaDuration.DEFAULT.seconds!! * 1000L, (rec.state.value as RawNmeaState.Recording).durationLimitMs)

        rec.offer(RawNmeaOrigin.INTERNAL, "\$GPGGA,SYNTHETIC*00")
        controller.onDestroyView()
        categoryJob.cancel()
        ShadowLooper.idleMainLooper()
        val result = (rec.state.value as RawNmeaState.Finished).result
        assertEquals(RawNmeaTermination.LIFECYCLE, result.termination)
        assertFalse(result.complete)
        assertEquals(1L, result.stats.sentences)
        runBlocking { assertTrue(rec.files.list().single().summary!!.termination == "LIFECYCLE") }
    }
}
