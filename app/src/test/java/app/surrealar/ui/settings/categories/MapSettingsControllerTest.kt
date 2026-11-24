package app.surrealar.ui.settings.categories

import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.EditText
import app.surrealar.R
import app.surrealar.domain.repository.MapSettingsRepository
import app.surrealar.domain.repository.StakeoutSettingsRepository
import app.surrealar.settings.model.StakeoutSettings
import app.surrealar.ui.rendermap.MapGridMode
import app.surrealar.ui.rendermap.MapSettings
import com.google.android.gms.maps.GoogleMap
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapSettingsControllerTest {

    private class FakeRepo(map: MapSettings, stakeout: StakeoutSettings) : MapSettingsRepository, StakeoutSettingsRepository {
        val mapState = MutableStateFlow(map)
        val stakeoutState = MutableStateFlow(stakeout)
        val savedMap = mutableListOf<MapSettings>()
        val savedStakeout = mutableListOf<StakeoutSettings>()
        override val mapSettings: Flow<MapSettings> = mapState
        override suspend fun setMapSettings(settings: MapSettings) { savedMap += settings; mapState.value = settings }
        override val stakeoutSettings: Flow<StakeoutSettings> = stakeoutState
        override suspend fun setStakeoutSettings(settings: StakeoutSettings) { savedStakeout += settings; stakeoutState.value = settings }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val map = MapSettings(defaultMapType = GoogleMap.MAP_TYPE_HYBRID, defaultGridMode = MapGridMode.entries.last(),
        keepMapToolsOpenByDefault = true, showMyLocationByDefault = false)
    private val stakeout = StakeoutSettings(toleranceMeters = 0.05, warningAccuracyMeters = 0.2, enableAudio = true)

    private fun show(repo: FakeRepo): View {
        val host = ControllerHost()
        return host.show(MapSettingsController(host.fragment, repo, repo, mainScope()), mainScope())
    }

    @Test fun `binds map and stakeout defaults without writing them`() {
        val repo = FakeRepo(map, stakeout)
        val view = show(repo)
        assertEquals("Hybrid", view.findViewById<AutoCompleteTextView>(R.id.spinner_map_type).text.toString())
        assertEquals(MapGridMode.entries.last().label, view.findViewById<AutoCompleteTextView>(R.id.spinner_grid_mode).text.toString())
        assertEquals(true, view.findViewById<MaterialSwitch>(R.id.switch_map_tools_open).isChecked)
        assertEquals(false, view.findViewById<MaterialSwitch>(R.id.switch_show_my_location).isChecked)
        assertEquals("0.05", view.findViewById<EditText>(R.id.edit_stakeout_tolerance).text.toString())
        assertEquals(true, view.findViewById<MaterialSwitch>(R.id.switch_stakeout_audio).isChecked)
        assertTrue(repo.savedMap.isEmpty())
        assertTrue(repo.savedStakeout.isEmpty())
    }

    @Test fun `map switch and stakeout fields persist, an invalid tolerance keeps the saved value`() {
        val repo = FakeRepo(map, stakeout)
        val view = show(repo)

        view.findViewById<MaterialSwitch>(R.id.switch_drawer_expanded).isChecked = false
        assertEquals(map.copy(mapPointsDrawerExpandedByDefault = false), repo.savedMap.last())

        val tolerance = view.findViewById<EditText>(R.id.edit_stakeout_tolerance)
        tolerance.setText("0")                               // not positive: ignored
        tolerance.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertTrue(repo.savedStakeout.isEmpty())

        tolerance.setText("0.02")
        assertEquals(stakeout.copy(toleranceMeters = 0.02), repo.savedStakeout.last())
    }
}
