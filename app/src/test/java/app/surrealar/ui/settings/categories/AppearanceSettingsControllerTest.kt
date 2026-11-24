package app.surrealar.ui.settings.categories

import android.app.Application
import android.widget.AutoCompleteTextView
import app.surrealar.R
import app.surrealar.domain.repository.AppearanceSettingsRepository
import app.surrealar.settings.model.AppThemeMode
import app.surrealar.settings.model.AppearanceSettings
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppearanceSettingsControllerTest {

    private class FakeRepo(initial: AppearanceSettings) : AppearanceSettingsRepository {
        val state = MutableStateFlow(initial)
        val saved = mutableListOf<AppearanceSettings>()
        override val appearanceSettings: Flow<AppearanceSettings> = state
        override suspend fun setAppearanceSettings(settings: AppearanceSettings) { saved += settings; state.value = settings }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `binds the persisted appearance settings`() {
        val repo = FakeRepo(AppearanceSettings(themeMode = AppThemeMode.DARK, showLiveGnssStatusBar = false,
            keepScreenAwake = true, maxBrightnessWhileOpen = true))
        val host = ControllerHost()
        val view = host.show(AppearanceSettingsController(host.fragment, repo, mainScope()), mainScope())

        assertEquals("Dark", view.findViewById<AutoCompleteTextView>(R.id.spinner_theme_mode).text.toString())
        assertFalse(view.findViewById<MaterialSwitch>(R.id.switch_gnss_status_bar).isChecked)
        assertTrue(view.findViewById<MaterialSwitch>(R.id.switch_keep_screen_awake).isChecked)
        assertTrue(view.findViewById<MaterialSwitch>(R.id.switch_max_brightness).isChecked)
    }

    @Test fun `toggling a switch persists only that field`() {
        val repo = FakeRepo(AppearanceSettings(themeMode = AppThemeMode.LIGHT))
        val host = ControllerHost()
        val view = host.show(AppearanceSettingsController(host.fragment, repo, mainScope()), mainScope())
        repo.saved.clear()

        view.findViewById<MaterialSwitch>(R.id.switch_keep_screen_awake).isChecked = true

        assertEquals(listOf(AppearanceSettings(themeMode = AppThemeMode.LIGHT, keepScreenAwake = true)), repo.saved)
    }

    @Test fun `a toggle made after the category scope is cancelled still saves`() {
        val repo = FakeRepo(AppearanceSettings())
        val host = ControllerHost()
        val categoryScope = mainScope()
        val view = host.show(AppearanceSettingsController(host.fragment, repo, mainScope()), categoryScope)
        repo.saved.clear()

        categoryScope.cancel()   // category switched / screen left
        view.findViewById<MaterialSwitch>(R.id.switch_max_brightness).isChecked = true

        assertEquals(listOf(AppearanceSettings(maxBrightnessWhileOpen = true)), repo.saved)
    }
}
