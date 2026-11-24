package app.surrealar.ui.settings.categories

import android.app.Application
import app.surrealar.R
import app.surrealar.domain.repository.DeveloperSettingsRepository
import app.surrealar.settings.model.DeveloperSettings
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
class DeveloperSettingsControllerTest {

    private class FakeRepo(initial: DeveloperSettings) : DeveloperSettingsRepository {
        val state = MutableStateFlow(initial)
        val saved = mutableListOf<DeveloperSettings>()
        override val developerSettings: Flow<DeveloperSettings> = state
        override suspend fun setDeveloperSettings(settings: DeveloperSettings) { saved += settings; state.value = settings }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `binds and persists the developer tools switch`() {
        val repo = FakeRepo(DeveloperSettings(developerToolsEnabled = true))
        val host = ControllerHost()
        val view = host.show(DeveloperSettingsController(host.fragment, repo, mainScope()), mainScope())
        val switch = view.findViewById<MaterialSwitch>(R.id.switch_dev_tools)

        assertTrue(switch.isChecked)
        repo.saved.clear()             // in the app the async bind may re-save the same value; only the user change matters here
        switch.isChecked = false
        assertEquals(listOf(DeveloperSettings(developerToolsEnabled = false)), repo.saved)
    }
}
