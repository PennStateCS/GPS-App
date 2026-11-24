package app.surrealar.ui.settings.categories

import android.app.Application
import android.view.View
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.usecase.ExportCoordinateBackupUseCase
import app.surrealar.domain.usecase.ImportCoordinateBackupUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Data category: the fragment-registered launchers are started from the buttons, and their results are
 * routed to the controller (a cancelled picker — null uri — does nothing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataSettingsControllerTest {

    private class RecordingLauncher<I> : ActivityResultLauncher<I>() {
        val inputs = mutableListOf<I>()
        override fun launch(input: I, options: ActivityOptionsCompat?) { inputs += input }
        override fun unregister() {}
        override fun getContract(): ActivityResultContract<I, *> = throw UnsupportedOperationException()
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val exportLauncher = RecordingLauncher<String>()
    private val csvLauncher = RecordingLauncher<String>()
    private val importLauncher = RecordingLauncher<Array<String>>()
    private val repository = Mockito.mock(CoordinateRepository::class.java)
    private val settings = Mockito.mock(CoordinateDisplaySettingsRepository::class.java)
    private val service = Mockito.mock(CoordinateSystemService::class.java)
    private val exportBackup = Mockito.mock(ExportCoordinateBackupUseCase::class.java)
    private val importBackup = Mockito.mock(ImportCoordinateBackupUseCase::class.java)

    private fun controller(host: ControllerHost) = DataSettingsController(host.fragment, repository, settings, service,
        exportBackup, importBackup, mainScope(), exportLauncher, csvLauncher, importLauncher)

    @Test fun `buttons start the fragment's launchers with the same file names and types`() {
        val host = ControllerHost()
        val view: View = host.show(controller(host), mainScope())

        view.findViewById<Button>(R.id.btn_export_coordinates).performClick()
        view.findViewById<Button>(R.id.btn_export_points_csv).performClick()
        view.findViewById<Button>(R.id.btn_import_coordinates).performClick()

        assertTrue(exportLauncher.inputs.single().matches(Regex("""coordinates_backup_\d+\.json""")))
        assertTrue(csvLauncher.inputs.single().matches(Regex("""survey_points_\d+\.csv""")))
        assertArrayEquals(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"),
            importLauncher.inputs.single())
    }

    @Test fun `a cancelled picker result does nothing`() {
        val host = ControllerHost()
        val data = controller(host)
        host.show(data, mainScope())

        data.onExportBackupResult(null)
        data.onExportPointsCsvResult(null)
        data.onImportResult(null)

        Mockito.verifyNoInteractions(repository, settings, service, exportBackup, importBackup)
    }

    @Test fun `results still route after the category view is destroyed (launcher results outlive the view)`() {
        val host = ControllerHost()
        val data = controller(host)
        host.show(data, mainScope())
        data.onDestroyView()

        data.onImportResult(null)                         // no view: must not crash
        assertEquals(0, importLauncher.inputs.size)
        Mockito.verifyNoInteractions(repository)
    }
}
