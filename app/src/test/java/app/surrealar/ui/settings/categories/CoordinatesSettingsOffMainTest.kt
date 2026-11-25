package app.surrealar.ui.settings.categories

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemGroupsTest
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.coords.Nsrs2022Capabilities
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.settings.model.CoordinateDisplaySettings
import app.surrealar.settings.model.CoordinateSystemSettings
import io.github.pennstatecs.geokit.crs.CrsCatalogCsv
import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.gnss.model.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 4 WP U (Phase 3 known limitation): the SPCS2022 catalog (953 rows) is parsed off the main thread
 * when Settings → Coordinates opens; the screen shows "Loading…" and renders the groups when ready. Uses the
 * real Android main dispatcher (Robolectric looper) and the production Dispatchers.Default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoordinatesSettingsOffMainTest {

    private class Repo(s: CoordinateSystemSettings) : CoordinateDisplaySettingsRepository {
        override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = MutableStateFlow(CoordinateDisplaySettings())
        override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) = Unit
        override val coordinateSystemSettings: Flow<CoordinateSystemSettings> = MutableStateFlow(s)
        override suspend fun setCoordinateSystemSettings(settings: CoordinateSystemSettings) = Unit
    }

    @Test fun `SPCS2022 catalog is parsed off the main thread and the groups render when ready`() {
        val parseStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parseThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
        val shipped = CrsRegistry.DEFAULT.catalog(CrsCatalogId.SPCS2022).entries.size
        val catalogText = javaClass.getResourceAsStream("/" + CrsRegistry.SPCS2022_RESOURCE)!!.bufferedReader().readText()
        // The real catalog, read through a source that records its thread and waits for the test.
        val registry = CrsRegistry(spcs2022Source = {
            parseThread.set(Thread.currentThread())
            parseStarted.countDown()
            release.await(10, TimeUnit.SECONDS)
            CrsCatalogCsv.parse(catalogText, CrsCatalogId.SPCS2022)
        })
        val host = ControllerHost()
        val switchboard = Mockito.mock(FixSwitchboard::class.java)
        Mockito.`when`(switchboard.currentFix).thenReturn(MutableStateFlow<Fix?>(null))
        val service = Mockito.mock(CoordinateSystemService::class.java)
        Mockito.`when`(service.heightDataVersion).thenReturn(MutableStateFlow(0L))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = CoordinatesSettingsController(host.fragment, Repo(CoordinateSystemSettings(nsrs2022Preview = true)),
            switchboard, service, scope, crsRegistry = { registry }, capabilities = { Nsrs2022Capabilities.all(crs = it) })

        // createView returns while the parse is blocked: the main thread never waits for it.
        val view = host.show(controller, scope)
        assertTrue("parse started in the background", parseStarted.await(10, TimeUnit.SECONDS))
        assertNotSame("not on the main thread", Looper.getMainLooper().thread, parseThread.get())
        ShadowLooper.idleMainLooper()
        val status = view.findViewById<TextView>(R.id.text_coord_nsrs2022_status)
        assertEquals(View.VISIBLE, status.visibility)
        assertEquals(CoordinatesSettingsController.STATUS_LOADING, status.text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.container_coord_nsrs2022_groups).visibility)

        release.countDown()
        val groups = view.findViewById<LinearLayout>(R.id.container_coord_nsrs2022_groups)
        val deadline = System.currentTimeMillis() + 10_000
        while (groups.visibility != View.VISIBLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        assertEquals(View.VISIBLE, groups.visibility)
        assertEquals(View.GONE, status.visibility)
        val texts = (0 until groups.childCount).flatMap { i ->
            when (val c = groups.getChildAt(i)) {
                is TextView -> listOf(c.text.toString())
                is LinearLayout -> (0 until c.childCount).mapNotNull { (c.getChildAt(it) as? TextView)?.text?.toString() }
                else -> emptyList()
            }
        }
        assertTrue(texts.toString(), texts.any { it.startsWith("SPCS2022 ($shipped NGS beta zones): unavailable") })
        assertTrue(texts.toString(), texts.contains("Unsupported / not yet verified"))
    }

    @Test fun `stub registry with operational SPCS2022 zones - zone entries are prepared off-main, labelled with their frame`() {
        val host = ControllerHost()
        val switchboard = Mockito.mock(FixSwitchboard::class.java)
        Mockito.`when`(switchboard.currentFix).thenReturn(MutableStateFlow<Fix?>(null))
        val service = Mockito.mock(CoordinateSystemService::class.java)
        Mockito.`when`(service.heightDataVersion).thenReturn(MutableStateFlow(0L))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val crs = CoordinateSystemGroupsTest.stubCrs()
        val controller = CoordinatesSettingsController(host.fragment, Repo(CoordinateSystemSettings(nsrs2022Preview = true)),
            switchboard, service, scope, crsRegistry = { crs },
            capabilities = { Nsrs2022Capabilities.all(frames = CoordinateSystemGroupsTest.stubFrames(), crs = it) })
        val view = host.show(controller, scope)
        val groups = view.findViewById<LinearLayout>(R.id.container_coord_nsrs2022_groups)
        val deadline = System.currentTimeMillis() + 10_000
        while (groups.visibility != View.VISIBLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        assertEquals(View.VISIBLE, groups.visibility)
        val rows = (0 until groups.childCount).map { groups.getChildAt(it) }.filterIsInstance<LinearLayout>()
        val spcs = rows.first { it.contentDescription?.startsWith("SPCS2022") == true }
        assertTrue(spcs.contentDescription.toString(), spcs.contentDescription.contains("not offered") &&
            spcs.contentDescription.contains("beta") && spcs.contentDescription.contains("not selectable"))
        // The zone selector's SPCS2022 entry for this registry (what the dropdown would add with the preview ON).
        val z = ZoneSelectorOptions.spcs2022("", preview = true, registry = crs).single()
        assertTrue(z.label, z.label.contains("NATRF2022") && z.label.contains("β beta") && !z.selectable)
    }
}
