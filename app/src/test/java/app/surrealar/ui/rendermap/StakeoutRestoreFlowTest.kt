package app.surrealar.ui.rendermap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.StakeoutSettings
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

/**
 * Stakeout across map-view recreation: Details / AR (fragment kept on the back stack, view
 * destroyed), view recreation, a bottom-nav tab switch (fragment destroyed, activity-scoped
 * [MapUiStateViewModel] kept), stale GNSS on return, a target deleted while away, and repeated
 * round trips.
 *
 * [MapHost] replays RenderMapFragment's calls in its order (onViewCreated → bindViews, onResume →
 * resumeGuidanceFeedback + syncCompass, onMapReady → onMapReady, coordinate load → revalidateTarget,
 * onPause → pauseGuidanceFeedback + syncCompass, onDestroyView → compass.unregister + onDestroyView)
 * on the real stakeout card layout, a real [CompassHeadingController] (Robolectric SensorManager) and
 * a fresh mocked GoogleMap per view. The fragment itself is not started (it needs Hilt and a working
 * Google Maps MapView); that wiring is checked on the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StakeoutRestoreFlowTest {

    private class FakeTone : GuidanceTone {
        var released = false
        val plays = mutableListOf<Boolean>()
        override fun play(arrived: Boolean) { plays += arrived }
        override fun release() { released = true }
    }

    /** One GoogleMap per map view, recording the markers added to it. */
    private class FakeMap {
        val map: GoogleMap = Mockito.mock(GoogleMap::class.java)
        val markers = mutableListOf<Marker>()
        val options = mutableListOf<MarkerOptions>()
        init {
            Mockito.`when`(map.addMarker(any(MarkerOptions::class.java))).thenAnswer { inv ->
                options += inv.getArgument<MarkerOptions>(0)
                Mockito.mock(Marker::class.java).also { markers += it }
            }
        }
    }

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val themed = ContextThemeWrapper(app, R.style.Theme_SurveyingApp)
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val testScope = TestScope(StandardTestDispatcher())
    private val vm = MapUiStateViewModel()
    private val tones = mutableListOf<FakeTone>()
    private val snackbars = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private var now = 1_000_000L
    private var fixStale = false
    private var live: LatLng? = null
    private val css = Mockito.mock(CoordinateSystemService::class.java).also {
        Mockito.`when`(it.distanceText(anyDouble())).thenReturn("12.3 m")
        Mockito.`when`(it.deltaText(anyDouble(), anyInt())).thenReturn("1.0 m")
        Mockito.`when`(it.lengthText(anyDouble(), anyInt())).thenReturn("0.10 m")
    }

    init { shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)) }

    /** A RenderMapFragment instance: its controllers live as long as it does. */
    private inner class MapHost {
        var view: View? = null
        var fakeMap: FakeMap? = null
        var viewScope: CoroutineScope? = null
        var resumed = false
        val compass = CompassHeadingController(
            sensorManagerProvider = { sm },
            displayRotation = { android.view.Surface.ROTATION_0 },
            headingProvider = HeadingProvider(latestFix = { null }),
            onHeadingChanged = {},
        )
        val stakeout: StakeoutController = StakeoutController(
            map = { fakeMap?.map },
            view = { view },
            scope = { checkNotNull(viewScope) },
            coordinateSystemService = css,
            currentFix = { null },
            liveLatLng = { live },
            compassHeading = { null },
            isMagneticOnly = { false },
            logHeadingSource = {},
            syncCompass = { syncCompass() },
            logMapEvent = { _, msg -> events += msg },
            showSnackbar = { snackbars += it },
            loadSession = { vm.state.stakeout },
            saveSession = { s -> vm.update { it.copy(stakeout = s) } },
            isFixStale = { fixStale },
            toneFactory = { FakeTone().also { tones += it } },
            clock = ElapsedClock { now },
        )

        fun syncCompass() = compass.sync(CompassHeadingController.isNeeded(
            resumed = resumed,
            guidanceActive = stakeout.isGuidanceActive,
            guidanceUsesCompass = stakeout.settings.guidanceUsesCompassHeading,
            showMyLocation = false,
        ))

        /** onCreateView/onViewCreated, onStart/onResume, onMapReady, the first coordinate emission. */
        fun open(points: List<Coordinate>? = listOf(point)) {
            view = LayoutInflater.from(themed).inflate(R.layout.fragment_render_map, null, false)
            viewScope = CoroutineScope(testScope.coroutineContext + Job(testScope.coroutineContext[Job]))
            stakeout.bindViews(view!!)
            stakeout.onSettingsChanged(settings)
            resumed = true; stakeout.resumeGuidanceFeedback(); syncCompass()
            fakeMap = FakeMap(); stakeout.onMapReady()
            if (points != null) stakeout.revalidateTarget(points)
            testScope.testScheduler.runCurrent()
        }

        /** onPause, onStop, onDestroyView (Details/AR on top, tab switch, recreation). */
        fun leave() {
            stakeout.pauseGuidanceFeedback(); resumed = false; syncCompass()
            compass.unregister()
            stakeout.onDestroyView()
            viewScope?.cancel(); viewScope = null
            fakeMap = null; view = null
            testScope.testScheduler.runCurrent()
        }

        fun text(id: Int): String = view!!.findViewById<TextView>(id).text.toString()
        fun visibility(id: Int): Int = view!!.findViewById<View>(id).visibility
    }

    private var settings = StakeoutSettings(enableAudio = true, keepScreenOnDuringStakeout = true,
        guidanceUsesCompassHeading = true, enableHaptics = false)

    private val point = Coordinate(
        id = "c1", name = "Corner 1", latitude = 41.2459, longitude = -75.8813, altitude = 300.0,
        timestamp = 0L, icon = "ic_pin", color = 0,
    )
    private val other = point.copy(id = "c2", name = "Corner 2", latitude = 41.2460)

    // BitmapDescriptorFactory needs an initialised Maps SDK; the marker icons are not under test.
    private val descriptors: MockedStatic<BitmapDescriptorFactory> =
        Mockito.mockStatic(BitmapDescriptorFactory::class.java)

    @After fun tearDown() { testScope.cancel(); descriptors.close() }

    private fun activeTones() = tones.count { !it.released }
    private fun compassListeners(h: MapHost) = shadowOf(sm).getListeners().count { it === h.compass.rotationListener }
    private fun activeWatchers() = testScope.coroutineContext[Job]!!.children.sumOf { parent ->
        parent.children.count { it.isActive }
    }

    /** Starts guidance on [point] from the selected-point card, then gets a live readout. */
    private fun MapHost.startStakeoutWithGuidance() {
        stakeout.startForCoordinate(point)
        stakeout.startGuidance()
        live = LatLng(41.2458, -75.8813)
        stakeout.updateStakeoutCalculations(live!!)
        testScope.testScheduler.runCurrent()
    }

    private fun assertStakeoutCardRestored(h: MapHost, guidance: Boolean) {
        assertTrue(h.stakeout.isStakeoutMode)
        assertEquals("c1", h.stakeout.stakeoutTargetId)
        assertEquals(View.VISIBLE, h.visibility(R.id.stakeout_panel))
        assertEquals("Corner 1", h.text(R.id.txt_stakeout_target))
        assertEquals(guidance, h.stakeout.isGuidanceActive)
        assertEquals(if (guidance) "Stop Guidance" else "Start Guidance", h.text(R.id.btn_stakeout_guidance))
        assertEquals(if (guidance) View.VISIBLE else View.GONE, h.visibility(R.id.img_stakeout_arrow))
        // Exactly one target marker, on the CURRENT map, red, at the target.
        val m = h.fakeMap!!
        assertEquals(1, m.options.size)
        assertEquals(LatLng(point.latitude, point.longitude), m.options.single().position)
        assertEquals("Stakeout: Corner 1", m.options.single().title)
    }

    // 1. Start stakeout → open Details → return.
    @Test fun `details round trip restores target, marker, card and guidance`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        assertEquals("12.3 m", h.text(R.id.txt_stakeout_distance))
        val firstMap = h.fakeMap!!

        h.leave()   // Details pushed on top: view destroyed, fragment kept
        assertEquals(0, activeTones())
        assertFalse(events.contains("Stakeout guidance stopped"))
        assertNotNull(vm.state.stakeout)

        h.open()
        assertStakeoutCardRestored(h, guidance = true)
        // The readout is not restored from the position seen before leaving: waiting for a live fix.
        assertEquals("—", h.text(R.id.txt_stakeout_distance))
        assertEquals("Waiting for live position…", h.text(R.id.txt_stakeout_status))
        // Guidance resources back, once.
        assertEquals(1, activeTones())
        assertTrue(h.view!!.keepScreenOn)
        assertEquals(1, compassListeners(h))
        assertEquals(1, activeWatchers())
        // The marker on the destroyed map was never touched again.
        Mockito.verify(firstMap.markers.single(), Mockito.never()).setPosition(any())

        // The next live fix drives the readout and the line on the new map.
        h.stakeout.updateStakeoutCalculations(LatLng(41.2458, -75.8813))
        assertEquals("12.3 m", h.text(R.id.txt_stakeout_distance))
        Mockito.verify(h.fakeMap!!.map).addPolyline(any())
        assertTrue(events.contains("Stakeout restored targetId=c1 guidance=true"))
    }

    // 2. Start stakeout → open AR → return (guidance off: nothing is started on return).
    @Test fun `AR round trip without guidance restores the card and starts no feedback`() {
        val h = MapHost(); h.open()
        h.stakeout.startForCoordinate(point)
        h.leave()   // AR pushed on top
        h.open()
        assertStakeoutCardRestored(h, guidance = false)
        assertTrue(tones.isEmpty())
        assertFalse(h.view!!.keepScreenOn)
        assertEquals(0, compassListeners(h))
        assertEquals(0, activeWatchers())
    }

    // 3. Destroy and recreate the map view; a free map-tap target is restored too.
    @Test fun `view recreation keeps a map-tap target and taps move the marker on the new map`() {
        val h = MapHost(); h.open()
        h.stakeout.startForCoordinate(point)
        val tap = LatLng(41.25, -75.88)
        h.stakeout.onMapTap(tap)
        val oldMarker = h.fakeMap!!.markers.single()
        h.leave()

        h.open(points = null)   // a free target needs no database confirmation
        assertNull(h.stakeout.stakeoutTargetId)
        assertEquals("41.25000, -75.88000", h.text(R.id.txt_stakeout_target))
        assertEquals(tap, h.fakeMap!!.options.single().position)
        assertEquals("Stakeout Target", h.fakeMap!!.options.single().title)

        val tap2 = LatLng(41.26, -75.87)
        h.stakeout.onMapTap(tap2)
        Mockito.verify(h.fakeMap!!.markers.single()).setPosition(tap2)
        Mockito.verify(oldMarker, Mockito.never()).setPosition(tap2)
        assertEquals(1, h.fakeMap!!.markers.size)
        assertEquals(tap2.latitude, vm.state.stakeout!!.targetLat!!, 0.0)
    }

    // 4. Return with a stale GNSS feed: no readout from the replayed old fix.
    @Test fun `return with a stale fix waits for a live position`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        h.leave()
        fixStale = true
        h.open()
        h.stakeout.updateStakeoutCalculations(LatLng(41.2458, -75.8813))   // FixSwitchboard replay
        assertEquals("—", h.text(R.id.txt_stakeout_distance))
        now += StakeoutController.STAKEOUT_STALE_TICK_MS
        testScope.testScheduler.advanceTimeBy(StakeoutController.STAKEOUT_STALE_TICK_MS + 1)
        assertEquals("Waiting for live position…", h.text(R.id.txt_stakeout_status))
        assertTrue(tones.last().plays.isEmpty())   // no cue from the stale fix

        fixStale = false   // receiver back
        h.stakeout.updateStakeoutCalculations(LatLng(41.2458, -75.8813))
        assertEquals("12.3 m", h.text(R.id.txt_stakeout_distance))
    }

    // 5. Delete the target while away → return.
    @Test fun `target deleted while away ends stakeout with an explanation`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        h.leave()
        h.open(points = listOf(other))
        assertFalse(h.stakeout.isStakeoutMode)
        assertFalse(h.stakeout.isGuidanceActive)
        assertEquals(View.GONE, h.visibility(R.id.stakeout_panel))
        assertEquals("Stakeout target was removed", snackbars.last())
        assertFalse(snackbars.contains("Stakeout stopped"))
        assertTrue(events.contains("Stakeout target removed targetId=c1"))
        assertTrue(h.fakeMap!!.markers.isEmpty())   // never drawn for the deleted point
        assertNull(vm.state.stakeout)
        assertEquals(0, activeTones())
        assertFalse(h.view!!.keepScreenOn)
        assertEquals(0, compassListeners(h))
        assertEquals(0, activeWatchers())
    }

    @Test fun `all points deleted while away ends stakeout`() {
        val h = MapHost(); h.open()
        h.stakeout.startForCoordinate(point)
        h.leave()
        h.open(points = emptyList())
        assertFalse(h.stakeout.isStakeoutMode)
        assertEquals("Stakeout target was removed", snackbars.last())
    }

    @Test fun `target moved while away is followed`() {
        val h = MapHost(); h.open()
        h.stakeout.startForCoordinate(point)
        h.leave()
        val moved = point.copy(latitude = 41.2470, name = "Corner 1b")
        h.open(points = listOf(moved))
        assertEquals(LatLng(41.2470, point.longitude), h.fakeMap!!.options.single().position)
        assertEquals("Corner 1b", h.text(R.id.txt_stakeout_target))
        assertEquals(41.2470, vm.state.stakeout!!.targetLat!!, 0.0)
    }

    @Test fun `no marker or readout before the restored target is confirmed`() {
        val h = MapHost(); h.open()
        h.stakeout.startForCoordinate(point)
        h.leave()
        h.open(points = null)   // map ready, coordinates not loaded yet
        assertTrue(h.fakeMap!!.markers.isEmpty())
        h.stakeout.updateStakeoutCalculations(LatLng(41.2458, -75.8813))
        assertEquals("—", h.text(R.id.txt_stakeout_distance))
        h.stakeout.revalidateTarget(listOf(point))
        assertEquals(1, h.fakeMap!!.markers.size)
    }

    // 6. Leave and return repeatedly.
    @Test fun `repeated round trips never accumulate tones, watchers, listeners or markers`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        repeat(5) {
            h.leave()
            assertEquals(0, activeTones())
            assertEquals(0, compassListeners(h))
            assertEquals(0, activeWatchers())
            assertFalse(h.compass.isRegistered)
            h.open()
            assertStakeoutCardRestored(h, guidance = true)
            assertEquals(1, activeTones())
            assertEquals(1, compassListeners(h))
            assertEquals(1, activeWatchers())
            // Each map got exactly one target marker; coordinate emissions don't add more.
            h.stakeout.revalidateTarget(listOf(point, other))
            assertEquals(1, h.fakeMap!!.markers.size)
        }
        assertEquals(1, events.count { it == "Stakeout started targetId=c1" })
        assertEquals(1, snackbars.count { it == "Stakeout started: Corner 1" })
    }

    // 7. Switch tabs while stakeout is active: a new fragment instance, the activity ViewModel kept.
    @Test fun `tab switch restores the same stakeout in a new fragment`() {
        val first = MapHost(); first.open()
        first.startStakeoutWithGuidance()
        first.leave()   // popUpTo(Home): fragment destroyed

        val second = MapHost(); second.open()
        assertStakeoutCardRestored(second, guidance = true)
        assertEquals(1, activeTones())
        assertEquals(1, compassListeners(second))
        assertEquals(0, compassListeners(first))
        assertEquals(1, activeWatchers())
        second.stakeout.stopGuidance()
        assertFalse(vm.state.stakeout!!.guidanceActive)
    }

    // 8. Stopping stakeout clears the session: nothing comes back.
    @Test fun `a stopped stakeout is not restored`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        h.stakeout.stopStakeout()
        assertNull(vm.state.stakeout)
        h.leave()
        h.open()
        assertFalse(h.stakeout.isStakeoutMode)
        assertEquals(View.GONE, h.visibility(R.id.stakeout_panel))
        assertTrue(h.fakeMap!!.markers.isEmpty())
        assertEquals(0, activeTones())
        assertEquals(0, compassListeners(h))
    }

    @Test fun `guidance stopped before leaving comes back off`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        h.stakeout.stopGuidance()
        h.leave()
        h.open()
        assertStakeoutCardRestored(h, guidance = false)
        assertEquals(0, activeTones())
        assertEquals(0, compassListeners(h))
    }

    @Test fun `background and foreground during a restored stakeout follow the pause rules`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        h.leave(); h.open()
        // onPause / onResume only (app backgrounded, view kept)
        h.stakeout.pauseGuidanceFeedback(); h.resumed = false; h.syncCompass()
        assertEquals(0, activeTones()); assertFalse(h.view!!.keepScreenOn); assertEquals(0, compassListeners(h))
        h.resumed = true; h.stakeout.resumeGuidanceFeedback(); h.syncCompass()
        testScope.testScheduler.runCurrent()
        assertEquals(1, activeTones()); assertTrue(h.view!!.keepScreenOn); assertEquals(1, compassListeners(h))
        assertEquals(1, activeWatchers())
    }

    @Test fun `settings changed while away apply to the restored guidance`() {
        val h = MapHost(); h.open()
        h.startStakeoutWithGuidance()
        h.leave()
        settings = settings.copy(enableAudio = false, keepScreenOnDuringStakeout = false, guidanceUsesCompassHeading = false)
        h.open()
        assertTrue(h.stakeout.isGuidanceActive)
        assertEquals(0, activeTones())
        assertFalse(h.view!!.keepScreenOn)
        assertEquals(0, compassListeners(h))
    }
}
