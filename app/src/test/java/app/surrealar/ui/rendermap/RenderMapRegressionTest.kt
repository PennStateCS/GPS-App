package app.surrealar.ui.rendermap

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.repository.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins defects found during the RenderMapFragment split (the destroyed-map marker move is pinned in
 * [StakeoutRestoreFlowTest]; the fragment dropping its GoogleMap in onDestroyView needs the Hilt
 * fragment and is checked on the device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RenderMapRegressionTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val themed = ContextThemeWrapper(app, R.style.Theme_SurveyingApp)
    private val modelRepository = Mockito.mock(ModelRepository::class.java)

    private fun markerLayer() = CoordinateMarkerLayer(
        map = { null },
        context = { themed },
        scope = { CoroutineScope(Dispatchers.Unconfined) },
        modelRepository = modelRepository,
    )

    /** The drawer list detaches its adapter with the view, which cancels its pending icon loads. */
    @Test fun `drawer releases the destroyed list and its adapter`() {
        val root = LayoutInflater.from(themed).inflate(R.layout.fragment_render_map, null, false)
        val drawer = MapDrawerController(
            context = { themed },
            markers = markerLayer(),
            modelRepository = modelRepository,
            coordinateSystemService = Mockito.mock(CoordinateSystemService::class.java),
            mapUiVm = MapUiStateViewModel(),
            selectedId = { null },
            onRowClick = {},
            onVisibilityChanged = {},
            showSnackbar = {},
        )
        drawer.bindListViews(root)
        val list = root.findViewById<RecyclerView>(R.id.coordinate_toggle_list)
        assertNotNull(list.adapter)
        drawer.onDestroyView()
        assertNull(list.adapter)
        drawer.showNoPoints()   // a late coordinate emission touches no destroyed view
    }

    /** The marker icon cache is written from Dispatchers.IO and Main: it must be a concurrent map. */
    @Test fun `marker icon cache is thread-safe`() {
        val field = CoordinateMarkerLayer::class.java.getDeclaredField("markerDescriptorCache")
        field.isAccessible = true
        assertTrue(field.get(markerLayer()) is java.util.concurrent.ConcurrentMap<*, *>)
    }
}
