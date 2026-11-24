package app.surrealar.ui.settings.categories

import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.settings.model.CoordinateDisplaySettings
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.ui.settings.common.inputLayoutOf
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.pennstatecs.geokit.gnss.model.Fix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoordinatesSettingsControllerTest {

    private class FakeRepo(display: CoordinateDisplaySettings, system: CoordinateSystemSettings) : CoordinateDisplaySettingsRepository {
        val displayState = MutableStateFlow(display)
        val systemState = MutableStateFlow(system)
        val savedDisplay = mutableListOf<CoordinateDisplaySettings>()
        val savedSystem = mutableListOf<CoordinateSystemSettings>()
        override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = displayState
        override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) { savedDisplay += settings; displayState.value = settings }
        override val coordinateSystemSettings: Flow<CoordinateSystemSettings> = systemState
        override suspend fun setCoordinateSystemSettings(settings: CoordinateSystemSettings) { savedSystem += settings; systemState.value = settings }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun show(repo: FakeRepo): View {
        val host = ControllerHost()
        val switchboard = Mockito.mock(FixSwitchboard::class.java)
        Mockito.`when`(switchboard.currentFix).thenReturn(MutableStateFlow<Fix?>(null))
        val service = Mockito.mock(CoordinateSystemService::class.java)
        return host.show(CoordinatesSettingsController(host.fragment, repo, switchboard, service, mainScope()), mainScope())
    }

    @Test fun `binds the coordinate system and display settings`() {
        val repo = FakeRepo(CoordinateDisplaySettings(showAccuracyIndicators = false, defaultNamePrefix = "Stake"),
            CoordinateSystemSettings(horizontal = HorizontalSystem.UTM))
        val view = show(repo)
        assertEquals(HorizontalSystem.UTM.label, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.row_coord_spcs_zone).visibility)
        assertEquals("Current position: waiting for GNSS", view.findViewById<TextView>(R.id.text_coord_preview).text.toString())
        assertEquals(false, view.findViewById<MaterialSwitch>(R.id.switch_show_accuracy_indicators).isChecked)
        assertEquals("Stake", view.findViewById<EditText>(R.id.edit_coordinate_name_prefix).text.toString())
        assertTrue(repo.savedDisplay.isEmpty())
        assertTrue(repo.savedSystem.isEmpty())
    }

    @Test fun `a name prefix over 20 characters is rejected, a valid one saves`() {
        val repo = FakeRepo(CoordinateDisplaySettings(), CoordinateSystemSettings())
        val view = show(repo)
        val prefix = view.findViewById<EditText>(R.id.edit_coordinate_name_prefix)

        prefix.setText("A".repeat(21))
        prefix.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertNotNull(inputLayoutOf(prefix)?.error)
        assertTrue(repo.savedDisplay.isEmpty())

        prefix.setText("Pt")
        prefix.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(CoordinateDisplaySettings(defaultNamePrefix = "Pt"), repo.savedDisplay.last())
    }

    @Test fun `switching the accuracy indicator persists it`() {
        val repo = FakeRepo(CoordinateDisplaySettings(), CoordinateSystemSettings())
        val view = show(repo)
        view.findViewById<MaterialSwitch>(R.id.switch_show_accuracy_indicators).isChecked = false
        assertEquals(CoordinateDisplaySettings(showAccuracyIndicators = false), repo.savedDisplay.last())
    }
}
