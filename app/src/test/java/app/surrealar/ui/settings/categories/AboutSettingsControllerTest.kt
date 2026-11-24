package app.surrealar.ui.settings.categories

import android.app.Application
import android.content.Context
import android.widget.TextView
import app.surrealar.BuildConfig
import app.surrealar.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AboutSettingsControllerTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `shows the build version`() {
        val host = ControllerHost()
        val view = host.show(AboutSettingsController(host.fragment) { R.string.diag_export_ready }, mainScope())
        assertEquals(BuildConfig.VERSION_NAME, view.findViewById<TextView>(R.id.text_about_version).text.toString())
    }

    @Test fun `export button runs the export path once, shows busy, then restores the button`() {
        val host = ControllerHost()
        val result = CompletableDeferred<Int>()
        val contexts = mutableListOf<Context>()
        val view = host.show(AboutSettingsController(host.fragment) { ctx -> contexts += ctx; result.await() }, mainScope())
        val button = view.findViewById<MaterialButton>(R.id.btn_export_diagnostic)

        button.performClick()
        assertEquals(1, contexts.size)
        assertSame(host.activity, contexts.single())
        assertFalse(button.isEnabled)
        assertEquals(host.activity.getString(R.string.diag_export_button_busy), button.text.toString())

        result.complete(R.string.diag_export_ready)
        assertTrue(button.isEnabled)
        assertEquals(host.activity.getString(R.string.diag_export_button), button.text.toString())
    }
}
