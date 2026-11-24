package app.surrealar.ui.settings.categories

import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.EditText
import app.surrealar.R
import app.surrealar.domain.repository.GnssCaptureSettingsRepository
import app.surrealar.ui.settings.common.inputLayoutOf
import io.github.pennstatecs.geokit.gnss.capture.CapturePreset
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CaptureSettingsControllerTest {

    private class FakeRepo(initial: GnssCaptureSettings) : GnssCaptureSettingsRepository {
        val state = MutableStateFlow(initial)
        val saved = mutableListOf<GnssCaptureSettings>()
        override val gnssCaptureSettings: Flow<GnssCaptureSettings> = state
        override suspend fun setGnssCaptureSettings(settings: GnssCaptureSettings) { saved += settings; state.value = settings }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val custom = GnssCaptureSettings(requiredMinStatus = RtkStatus.FLOAT, minDurationSec = 10, maxDurationSec = 30,
        minSamples = 20, maxFixAgeSec = 2, maxDiffAgeSec = 5, preset = CapturePreset.CUSTOM, precisionTargetM = null)

    private fun show(repo: FakeRepo): View {
        val host = ControllerHost()
        return host.show(CaptureSettingsController(host.fragment, repo, mainScope()), mainScope())
    }

    private fun View.edit(id: Int) = findViewById<EditText>(id)

    @Test fun `binds the stored rules, a non-Custom preset shows them read-only`() {
        val view = show(FakeRepo(custom.copy(preset = CapturePreset.STANDARD)))
        assertEquals("10", view.edit(R.id.edit_min_duration).text.toString())
        assertEquals("RTK FLOAT or better", view.findViewById<AutoCompleteTextView>(R.id.spinner_rtk_status).text.toString())
        assertEquals(CapturePreset.STANDARD.label, view.findViewById<AutoCompleteTextView>(R.id.spinner_capture_preset).text.toString())
        assertFalse(view.edit(R.id.edit_min_duration).isEnabled)

        val customView = show(FakeRepo(custom))
        assertTrue(customView.edit(R.id.edit_min_duration).isEnabled)
    }

    @Test fun `max below min is rejected with an error and nothing is saved, a valid edit saves`() {
        val repo = FakeRepo(custom)
        val view = show(repo)
        val maxDur = view.edit(R.id.edit_max_duration)

        maxDur.setText("5")                                    // below the 10 s minimum
        maxDur.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertNotNull(inputLayoutOf(maxDur)?.error)
        assertTrue(repo.saved.isEmpty())

        maxDur.setText("45")
        maxDur.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertNull(inputLayoutOf(maxDur)?.error)
        assertEquals(custom.copy(maxDurationSec = 45), repo.saved.last())
    }

    @Test fun `spread target outside 0_001 to 10 m is rejected`() {
        val repo = FakeRepo(custom)
        val view = show(repo)
        val precision = view.edit(R.id.edit_precision_target)
        precision.setText("11")
        precision.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertNotNull(inputLayoutOf(precision)?.error)
        assertTrue(repo.saved.isEmpty())
    }
}
