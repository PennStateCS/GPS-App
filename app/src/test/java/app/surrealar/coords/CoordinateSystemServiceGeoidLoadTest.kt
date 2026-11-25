package app.surrealar.coords

import androidx.test.core.app.ApplicationProvider
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.settings.model.CoordinateSystemSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The GEOID18 grid is read in the background (Dispatchers.IO) and becomes available shortly after. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CoordinateSystemServiceGeoidLoadTest {

    @Test fun `grid loads in the background after construction`() {
        val repo = Mockito.mock(CoordinateDisplaySettingsRepository::class.java)
        Mockito.`when`(repo.coordinateSystemSettings).thenReturn(flowOf(CoordinateSystemSettings()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val svc = CoordinateSystemService(ApplicationProvider.getApplicationContext(), repo, scope)
        val deadline = System.currentTimeMillis() + 15_000
        while (!svc.geoidLoaded && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("GEOID18 grid loaded from assets in the background", svc.geoidLoaded)
        scope.cancel()
    }

    @Test fun `heightDataVersion changes when the GEOID18 grid finished loading`() {
        val repo = Mockito.mock(CoordinateDisplaySettingsRepository::class.java)
        Mockito.`when`(repo.coordinateSystemSettings).thenReturn(flowOf(CoordinateSystemSettings()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val svc = CoordinateSystemService(ApplicationProvider.getApplicationContext(), repo, scope)
        // heightDataVersion = GEOID18 load count (0 → 1 when the grid load ends) + AppGeoidModels.loadEvents.
        fun grid18Signalled() = svc.heightDataVersion.value >= AppGeoidModels.loadEvents.value + 1
        val deadline = System.currentTimeMillis() + 15_000
        while (!(svc.geoidLoaded && grid18Signalled()) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("grid loaded", svc.geoidLoaded)
        assertTrue("signal advanced", grid18Signalled())
        scope.cancel()
    }
}
