package app.surrealar.ui.rendermap

import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.StakeoutSettings
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stakeout state machine and guidance resources (tone, keep-screen-on, stale watcher, compass demand)
 * without a GoogleMap or the layout: fake tone, real View, test coroutine scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StakeoutControllerTest {

    private class FakeTone : GuidanceTone {
        val plays = mutableListOf<Boolean>()
        var released = false
        override fun play(arrived: Boolean) { plays += arrived }
        override fun release() { released = true }
    }

    private val testScope = TestScope(StandardTestDispatcher())
    private val view = View(ApplicationProvider.getApplicationContext())
    private val tones = mutableListOf<FakeTone>()
    private val snackbars = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private var compassSyncs = 0
    private var live: LatLng? = null
    private var now = 1_000_000L

    private val stakeout = StakeoutController(
        map = { null },
        view = { view },
        scope = { testScope },
        coordinateSystemService = Mockito.mock(CoordinateSystemService::class.java),
        currentFix = { null },
        liveLatLng = { live },
        compassHeading = { null },
        isMagneticOnly = { false },
        logHeadingSource = {},
        syncCompass = { compassSyncs++ },
        logMapEvent = { _, msg -> events += msg },
        showSnackbar = { snackbars += it },
        toneFactory = { FakeTone().also { tones += it } },
        clock = ElapsedClock { now },
    )

    @After fun tearDown() { testScope.cancel() }

    private val point = Coordinate(
        id = "c1", name = "Corner 1", latitude = 41.2459, longitude = -75.8813, altitude = 300.0,
        timestamp = 0L, icon = "ic_pin", color = 0,
    )

    private fun activeWatchers() =
        testScope.coroutineContext[Job]!!.children.count { it.isActive }

    private fun settings(audio: Boolean = true, keepOn: Boolean = true, compass: Boolean = true, haptics: Boolean = false) =
        StakeoutSettings(enableAudio = audio, keepScreenOnDuringStakeout = keepOn,
            guidanceUsesCompassHeading = compass, enableHaptics = haptics)

    @Test fun `guidance needs a target`() {
        stakeout.startGuidance()
        assertFalse(stakeout.isGuidanceActive)
        assertEquals(listOf("Select a stakeout target first"), snackbars)
        assertTrue(tones.isEmpty())
        assertEquals(0, compassSyncs)
        assertFalse(view.keepScreenOn)
    }

    @Test fun `starting on a saved point sets the target and logs its id only`() {
        stakeout.startForCoordinate(point)
        assertTrue(stakeout.isStakeoutMode)
        assertEquals("c1", stakeout.stakeoutTargetId)
        assertTrue(events.contains("Stakeout started targetId=c1"))
        assertEquals(listOf("Stakeout started: Corner 1"), snackbars)
    }

    @Test fun `guidance acquires tone, keep-screen-on, compass and watcher, stop releases them`() {
        stakeout.onSettingsChanged(settings())
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        testScope.testScheduler.runCurrent()
        assertTrue(stakeout.isGuidanceActive)
        assertTrue(stakeout.wantsCompass)
        assertEquals(1, tones.size)
        assertTrue(view.keepScreenOn)
        assertEquals(1, compassSyncs)
        assertEquals(1, activeWatchers())

        stakeout.stopGuidance()
        testScope.testScheduler.runCurrent()
        assertFalse(stakeout.isGuidanceActive)
        assertFalse(stakeout.wantsCompass)
        assertTrue(tones.single().released)
        assertFalse(view.keepScreenOn)
        assertEquals(2, compassSyncs)
        assertEquals(0, activeWatchers())
    }

    @Test fun `audio and keep-screen-on follow the settings`() {
        stakeout.onSettingsChanged(settings(audio = false, keepOn = false, compass = false))
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        assertTrue(tones.isEmpty())
        assertFalse(view.keepScreenOn)
        assertFalse(stakeout.wantsCompass)
    }

    @Test fun `pause releases feedback but keeps guidance, resume re-acquires it`() {
        stakeout.onSettingsChanged(settings())
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        testScope.testScheduler.runCurrent()

        stakeout.pauseGuidanceFeedback()
        testScope.testScheduler.runCurrent()
        assertTrue(stakeout.isGuidanceActive)
        assertTrue(tones[0].released)
        assertFalse(view.keepScreenOn)
        assertEquals(0, activeWatchers())

        stakeout.resumeGuidanceFeedback()
        testScope.testScheduler.runCurrent()
        assertEquals(2, tones.size)
        assertFalse(tones[1].released)
        assertTrue(view.keepScreenOn)
        assertEquals(1, activeWatchers())
        stakeout.stopGuidance()
    }

    @Test fun `pause and resume without guidance touch nothing`() {
        stakeout.onSettingsChanged(settings())
        stakeout.pauseGuidanceFeedback()
        stakeout.resumeGuidanceFeedback()
        testScope.testScheduler.runCurrent()
        assertTrue(tones.isEmpty())
        assertFalse(view.keepScreenOn)
        assertEquals(0, activeWatchers())
        assertEquals(0, compassSyncs)
    }

    @Test fun `settings changes apply to running guidance`() {
        stakeout.onSettingsChanged(settings())
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        val syncsBefore = compassSyncs
        stakeout.onSettingsChanged(settings(audio = false, keepOn = false))
        assertTrue(tones.single().released)
        assertFalse(view.keepScreenOn)
        assertEquals(syncsBefore + 1, compassSyncs)
        stakeout.stopGuidance()
    }

    @Test fun `stopping stakeout stops guidance and clears the target, once`() {
        stakeout.onSettingsChanged(settings())
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        stakeout.stopStakeout()
        assertFalse(stakeout.isStakeoutMode)
        assertFalse(stakeout.isGuidanceActive)
        assertNull(stakeout.stakeoutTargetId)
        assertTrue(tones.single().released)
        assertFalse(view.keepScreenOn)
        val shown = snackbars.size
        stakeout.stopStakeout()   // already stopped: no-op
        assertEquals(shown, snackbars.size)
    }

    @Test fun `map taps set a free target only in stakeout mode`() {
        assertFalse(stakeout.onMapTap(LatLng(41.0, -76.0)))
        stakeout.startForCoordinate(point)
        assertTrue(stakeout.onMapTap(LatLng(41.0, -76.0)))
        assertNull(stakeout.stakeoutTargetId)
        assertTrue(stakeout.isStakeoutMode)
    }

    @Test fun `arrival fires the audio cue once per entry into tolerance`() {
        stakeout.onSettingsChanged(settings(audio = true))
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        val target = LatLng(point.latitude, point.longitude)
        stakeout.updateStakeoutCalculations(target)
        stakeout.updateStakeoutCalculations(target)
        assertEquals(listOf(true), tones.single().plays)
        assertTrue(events.any { it.startsWith("Stakeout target within tolerance distM=0.000") })
        stakeout.stopGuidance()
    }

    @Test fun `no readout outside stakeout mode`() {
        stakeout.onSettingsChanged(settings(audio = true))
        stakeout.updateStakeoutCalculations(LatLng(41.0, -76.0))
        assertTrue(tones.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun `view destruction keeps the target, host stops guidance first`() {
        stakeout.onSettingsChanged(settings())
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        stakeout.stopGuidance()       // RenderMapFragment.onDestroyView order
        stakeout.onDestroyView()
        assertTrue(stakeout.isStakeoutMode)
        assertEquals("c1", stakeout.stakeoutTargetId)
        assertFalse(view.keepScreenOn)
        assertTrue(tones.single().released)
    }
}
