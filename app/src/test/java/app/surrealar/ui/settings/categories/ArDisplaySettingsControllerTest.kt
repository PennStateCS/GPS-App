package app.surrealar.ui.settings.categories

import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.EditText
import app.surrealar.R
import app.surrealar.domain.repository.ArDisplaySettingsRepository
import app.surrealar.settings.model.ArDisplaySettings
import app.surrealar.ui.openinar.OpenInARViewModel
import app.surrealar.ui.settings.common.inputLayoutOf
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
class ArDisplaySettingsControllerTest {

    private class FakeRepo(initial: ArDisplaySettings) : ArDisplaySettingsRepository {
        val state = MutableStateFlow(initial)
        val saved = mutableListOf<ArDisplaySettings>()
        override val arDisplaySettings: Flow<ArDisplaySettings> = state
        override suspend fun setArDisplaySettings(settings: ArDisplaySettings) { saved += settings; state.value = settings }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val stored = ArDisplaySettings(altitudeMode = "TERRAIN", distanceFilterIndex = 2, showDebugOverlay = true,
        showLabels = false, showOffscreenArrows = true, modelScale = 2.5f, showArDebugTools = false)

    private fun show(repo: FakeRepo): View {
        val host = ControllerHost()
        return host.show(ArDisplaySettingsController(host.fragment, repo, mainScope()), mainScope())
    }

    @Test fun `binds the persisted AR display settings and hides the legacy save button`() {
        val view = show(FakeRepo(stored))
        assertEquals("Terrain Altitude", view.findViewById<AutoCompleteTextView>(R.id.spinner_altitude_mode).text.toString())
        assertEquals("100 m", view.findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter).text.toString())
        assertEquals(false, view.findViewById<MaterialSwitch>(R.id.switch_ar_show_labels).isChecked)
        assertEquals(true, view.findViewById<MaterialSwitch>(R.id.switch_ar_debug_overlay).isChecked)
        assertEquals("2.5", view.findViewById<EditText>(R.id.edit_ar_model_scale).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.btn_save_ar_display).visibility)
    }

    @Test fun `binding does not write, a switch change writes all fields`() {
        val repo = FakeRepo(stored)
        val view = show(repo)
        assertTrue("loading the values must not save them", repo.saved.isEmpty())

        view.findViewById<MaterialSwitch>(R.id.switch_ar_show_labels).isChecked = true

        assertEquals(listOf(stored.copy(showLabels = true)), repo.saved)
    }

    @Test fun `an invalid scale shows the error and keeps the last valid scale, other changes still save`() {
        val repo = FakeRepo(stored)
        val view = show(repo)
        val scale = view.findViewById<EditText>(R.id.edit_ar_model_scale)

        scale.setText("50")
        scale.onEditorAction(EditorInfo.IME_ACTION_DONE)

        assertNotNull(inputLayoutOf(scale)?.error)
        assertEquals(listOf(stored), repo.saved)          // scale kept at 2.5

        scale.setText("0.5")
        scale.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertNull(inputLayoutOf(scale)?.error)
        assertEquals(stored.copy(modelScale = 0.5f), repo.saved.last())
    }

    private fun selectDistance(view: View, position: Int) {
        val dropdown = view.findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter)
        dropdown.setText(dropdown.adapter.getItem(position).toString(), false)
        dropdown.onItemClickListener.onItemClick(null, dropdown, position, position.toLong())
    }

    @Test fun `selecting each distance option persists its step index 0 to 3`() {
        val repo = FakeRepo(stored)
        val view = show(repo)
        val dropdown = view.findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter)
        assertEquals(4, dropdown.adapter.count)

        for (position in 0..3) {
            selectDistance(view, position)
            assertEquals("option $position", position, repo.saved.last().distanceFilterIndex)
        }
        assertEquals(listOf(0, 1, 2, 3), repo.saved.map { it.distanceFilterIndex })
    }

    @Test fun `a later non-dropdown save keeps the shown distance step`() {
        val repo = FakeRepo(stored)
        val view = show(repo)
        selectDistance(view, 3)

        view.findViewById<MaterialSwitch>(R.id.switch_ar_show_labels).isChecked = true

        assertEquals(3, repo.saved.last().distanceFilterIndex)
    }

    @Test fun `each stored step index shows its label when the view is recreated`() {
        val expected = listOf("All distances", "50 m", "100 m", "500 m")
        for (index in 0..3) {
            val view = show(FakeRepo(stored.copy(distanceFilterIndex = index)))
            assertEquals("index $index", expected[index],
                view.findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter).text.toString())
        }
    }

    @Test fun `out-of-range stored indexes show the nearest end`() {
        assertEquals("All distances", show(FakeRepo(stored.copy(distanceFilterIndex = -1)))
            .findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter).text.toString())
        assertEquals("500 m", show(FakeRepo(stored.copy(distanceFilterIndex = 9)))
            .findViewById<AutoCompleteTextView>(R.id.spinner_distance_filter).text.toString())
    }

    @Test fun `the persisted index maps to the AR distance filter in metres`() {
        val repo = FakeRepo(stored)
        val view = show(repo)
        val metres = (0..3).map { position ->
            selectDistance(view, position)
            OpenInARViewModel.DISTANCE_FILTER_STEPS[repo.saved.last().distanceFilterIndex]
        }
        assertEquals(listOf(null, 50.0, 100.0, 500.0), metres)
        assertEquals(OpenInARViewModel.DISTANCE_FILTER_STEPS.size, ArDistanceFilterOptions.labels.size)
    }
}
