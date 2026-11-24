package app.surrealar.ui.rendermap

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings
import com.google.android.gms.maps.model.LatLng
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Live GNSS tracking on the map without a GoogleMap (final): source matching, trail acceptance,
 * stale/provider clearing and collector lifetime on the view lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LiveTrackingControllerTest {

    private fun fix(
        lat: Double = 41.2459, lon: Double = -75.8813, provider: Provider = Provider.INTERNAL,
        course: Double? = null,
    ) = Fix(
        provider = provider, timeUtc = Instant.parse("2026-11-19T12:00:00Z"),
        timestampSource = TimestampSource.UNKNOWN, latDeg = lat, lonDeg = lon,
        altEllipsoidalM = null, altMslM = null, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = 0.02, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 30,
        diffAgeS = null, speedMps = null, courseDeg = course,
    )

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class Harness {
        val fixes = MutableSharedFlow<Fix>(replay = 1)
        val stale = MutableStateFlow(false)
        val provider = MutableStateFlow(SourceSettings.ProviderChoice.INTERNAL)
        val source = MutableStateFlow(LocationSourceType.INTERNAL)
        val positions = mutableListOf<LatLng>()
        val live = LiveTrackingController(
            map = { null }, context = { null },
            fixes = fixes, fixStale = stale, activeProvider = provider, locationSource = source,
            compassHeading = { null },
            onLivePosition = { positions += it },
        )
    }

    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    // ── Pure rules ────────────────────────────────────────────────────────────

    @Test fun `source matching follows the selected source`() {
        assertTrue(LiveTrackingController.matchesSelectedSource(fix(provider = Provider.INTERNAL), LocationSourceType.INTERNAL))
        assertFalse(LiveTrackingController.matchesSelectedSource(fix(provider = Provider.EXTERNAL_TCP), LocationSourceType.INTERNAL))
        assertFalse(LiveTrackingController.matchesSelectedSource(fix(provider = Provider.INTERNAL), LocationSourceType.EXTERNAL))
        assertTrue(LiveTrackingController.matchesSelectedSource(fix(provider = Provider.EXTERNAL_TCP), LocationSourceType.EXTERNAL))
        assertTrue(LiveTrackingController.matchesSelectedSource(fix(provider = Provider.REPLAY), LocationSourceType.EXTERNAL))
    }

    @Test fun `first trail point is always accepted`() {
        assertTrue(LiveTrackingController.shouldAddTrailPoint(null, null, LatLng(41.0, -76.0), fix()))
        assertTrue(LiveTrackingController.shouldAddTrailPoint(fix(), null, LatLng(41.0, -76.0), fix()))
    }

    @Test fun `trail point needs half a metre of movement`() {
        val last = LatLng(41.0, -76.0)
        val near = LatLng(41.000003, -76.0)   // ~0.33 m north
        val far = LatLng(41.00001, -76.0)     // ~1.1 m north
        assertFalse(LiveTrackingController.shouldAddTrailPoint(fix(), last, near, fix()))
        assertTrue(LiveTrackingController.shouldAddTrailPoint(fix(), last, far, fix()))
    }

    @Test fun `trail point on a course change of at least 5 degrees, across north`() {
        val p = LatLng(41.0, -76.0)
        assertFalse(LiveTrackingController.shouldAddTrailPoint(fix(course = 10.0), p, p, fix(course = 14.0)))
        assertTrue(LiveTrackingController.shouldAddTrailPoint(fix(course = 10.0), p, p, fix(course = 15.0)))
        assertTrue(LiveTrackingController.shouldAddTrailPoint(fix(course = 358.0), p, p, fix(course = 3.0)))
        assertFalse(LiveTrackingController.shouldAddTrailPoint(fix(course = null), p, p, fix(course = 90.0)))
    }

    // ── Per-fix state ─────────────────────────────────────────────────────────

    @Test fun `a fix updates the live position even with My Location hidden`() {
        val h = Harness()
        h.live.setShowCurrentLocation(false)
        h.live.updateLiveTracking(fix())
        assertEquals(LatLng(41.2459, -75.8813), h.live.lastFixLatLng)
        assertNotNull(h.live.currentFix)
        assertEquals(1, h.positions.size)
    }

    @Test fun `an out-of-range fix is ignored`() {
        val h = Harness()
        h.live.updateLiveTracking(fix(lat = 91.0))
        assertNull(h.live.lastFixLatLng)
        assertTrue(h.positions.isEmpty())
    }

    @Test fun `clearing the live marker drops the position`() {
        val h = Harness()
        h.live.updateLiveTracking(fix())
        h.live.clearLiveMarker()
        assertNull(h.live.lastFixLatLng)
        assertNull(h.live.currentFix)
    }

    @Test fun `view destruction keeps the latest fix`() {
        val h = Harness()
        h.live.updateLiveTracking(fix())
        h.live.onDestroyView()
        assertNotNull(h.live.lastFixLatLng)
        assertFalse(h.live.headingAvailable)
        assertFalse(h.live.accuracyCircleVisible)
    }

    // ── Collectors ────────────────────────────────────────────────────────────

    @Test fun `collector forwards matching fixes and clears on the other source`() {
        val h = Harness()
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.STARTED
        h.live.collectFixes(owner)
        h.fixes.tryEmit(fix(provider = Provider.INTERNAL))
        idle()
        assertNotNull(h.live.lastFixLatLng)

        h.source.value = LocationSourceType.EXTERNAL   // internal fix no longer matches
        idle()
        assertNull(h.live.lastFixLatLng)
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    @Test fun `stale feed clears the live position`() {
        val h = Harness()
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.STARTED
        h.live.collectFixes(owner)
        h.fixes.tryEmit(fix())
        idle()
        assertNotNull(h.live.lastFixLatLng)
        h.stale.value = true
        idle()
        assertNull(h.live.lastFixLatLng)
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    @Test fun `provider change clears the position, the first emission does not`() {
        val h = Harness()
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.STARTED
        h.live.observeProviderChanges(owner)
        h.live.updateLiveTracking(fix())
        idle()
        assertNotNull(h.live.lastFixLatLng)
        h.provider.value = SourceSettings.ProviderChoice.EXTERNAL_TCP
        idle()
        assertNull(h.live.lastFixLatLng)
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    @Test fun `collectors follow the view lifecycle and never accumulate`() {
        val h = Harness()
        repeat(3) {
            val owner = Owner()
            owner.registry.currentState = Lifecycle.State.STARTED
            h.live.collectFixes(owner)
            idle()
            assertEquals(1, h.fixes.subscriptionCount.value)
            assertEquals(1, h.stale.subscriptionCount.value)
            owner.registry.currentState = Lifecycle.State.CREATED   // stopped: collection pauses
            idle()
            assertEquals(0, h.fixes.subscriptionCount.value)
            assertEquals(0, h.stale.subscriptionCount.value)
            owner.registry.currentState = Lifecycle.State.STARTED
            idle()
            assertEquals(1, h.fixes.subscriptionCount.value)
            owner.registry.currentState = Lifecycle.State.DESTROYED   // view destroyed
            idle()
            assertEquals(0, h.fixes.subscriptionCount.value)
            assertEquals(0, h.stale.subscriptionCount.value)
        }
    }
}
