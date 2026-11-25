package app.surrealar.gnss.mock

import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import app.surrealar.gnss.bus.FixSwitchboard
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import app.surrealar.domain.repository.SettingsRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Unit tests for [AndroidMockLocationPublisher].
 *
 * Robolectric provides a real Android context, avoiding NPE on LocationManager.
 * The pure predicate and conversion methods are declared internal for direct testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidMockLocationPublisherTest {

    private lateinit var publisher: AndroidMockLocationPublisher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        publisher = AndroidMockLocationPublisher(
            context        = context,
            fixSwitchboard = Mockito.mock(FixSwitchboard::class.java),
            settingsRepo   = Mockito.mock(SettingsRepository::class.java)
        )
    }

    // ── Helper: build a complete, valid, fresh, external Fix ──────────────────

    private fun externalFix(
        lat: Double        = 43.123456,
        lon: Double        = -76.987654,
        provider: Provider = Provider.EXTERNAL_TCP,
        timeUtc: Instant   = Instant.now(),
        hAccM: Double?     = 0.015,
        vAccM: Double?     = 0.025,
        altM: Double?      = 150.5,
        speedMps: Double?  = null,
        courseDeg: Double? = null
    ): Fix = Fix(
        provider          = provider,
        timeUtc           = timeUtc,
        timestampSource   = TimestampSource.NMEA_ZDA,
        latDeg            = lat,
        lonDeg            = lon,
        altEllipsoidalM   = altM,
        altMslM           = null,
        geoidSeparationM  = null,
        hDop              = null,
        vDop              = null,
        pDop              = null,
        hAccM             = hAccM,
        vAccM             = vAccM,
        stdDevEastM       = null,
        stdDevNorthM      = null,
        stdDevUpM         = null,
        rtkStatus         = RtkStatus.FIX,
        satsUsed          = 22,
        satsVisible       = 28,
        diffAgeS          = 1.0,
        speedMps          = speedMps,
        courseDeg         = courseDeg
    )

    // ── isExternalFix ─────────────────────────────────────────────────────────

    @Test fun `internal fix is not external`() {
        assertFalse(publisher.isExternalFix(externalFix(provider = Provider.INTERNAL)))
    }

    @Test fun `RS2_TCP fix is external`() {
        assertTrue(publisher.isExternalFix(externalFix(provider = Provider.EXTERNAL_TCP)))
    }

    @Test fun `RS2_BT fix is external`() {
        assertTrue(publisher.isExternalFix(externalFix(provider = Provider.EXTERNAL_BT)))
    }

    @Test fun `RS2_EXTERNAL fix is external`() {
        assertTrue(publisher.isExternalFix(externalFix(provider = Provider.EXTERNAL_TCP)))
    }

    @Test fun `OTHER provider fix is external`() {
        assertTrue(publisher.isExternalFix(externalFix(provider = Provider.OTHER)))
    }

    // ── isStale ───────────────────────────────────────────────────────────────

    @Test fun `fresh fix is not stale`() {
        assertFalse(publisher.isStale(externalFix(timeUtc = Instant.now())))
    }

    @Test fun `tablet clock far off GNSS time does not make a live stream stale`() {
        // GNSS time 2 minutes ahead of the tablet clock (both directions used to block publishing).
        val base = Instant.now().plusSeconds(120)
        repeat(5) { i -> assertFalse(publisher.isStale(externalFix(timeUtc = base.plusMillis(i * 10L)))) }
    }

    @Test fun `fix whose GNSS time jumps backwards beyond the window is stale`() {
        val now = Instant.now()
        assertFalse(publisher.isStale(externalFix(timeUtc = now)))
        val old = now.minusMillis(AndroidMockLocationPublisher.MAX_FIX_AGE_MS + 1_000)
        assertTrue(publisher.isStale(externalFix(timeUtc = old)))
    }

    @Test fun `repeated stale fixes rebuild the baseline so publishing recovers`() {
        val now = Instant.now()
        assertFalse(publisher.isStale(externalFix(timeUtc = now)))
        val old = now.minusMillis(AndroidMockLocationPublisher.MAX_FIX_AGE_MS + 5_000)
        repeat(AndroidMockLocationPublisher.STALE_RESET_COUNT) { assertTrue(publisher.isStale(externalFix(timeUtc = old))) }
        // Baseline rebuilt: the new timeline is accepted.
        assertFalse(publisher.isStale(externalFix(timeUtc = old.plusMillis(100))))
    }

    // ── isValid ───────────────────────────────────────────────────────────────

    @Test fun `normal coordinates are valid`() {
        assertTrue(publisher.isValid(externalFix(lat = 43.1, lon = -76.9)))
    }

    @Test fun `poles are valid`() {
        assertTrue(publisher.isValid(externalFix(lat =  90.0, lon = 0.0)))
        assertTrue(publisher.isValid(externalFix(lat = -90.0, lon = 0.0)))
    }

    @Test fun `antimeridian longitude is valid`() {
        assertTrue(publisher.isValid(externalFix(lat = 0.0, lon =  180.0)))
        assertTrue(publisher.isValid(externalFix(lat = 0.0, lon = -180.0)))
    }

    @Test fun `latitude beyond 90 is invalid`() {
        assertFalse(publisher.isValid(externalFix(lat =  90.001, lon = 0.0)))
        assertFalse(publisher.isValid(externalFix(lat = -90.001, lon = 0.0)))
    }

    @Test fun `longitude beyond 180 is invalid`() {
        assertFalse(publisher.isValid(externalFix(lat = 0.0, lon =  180.001)))
        assertFalse(publisher.isValid(externalFix(lat = 0.0, lon = -180.001)))
    }

    // ── isDuplicate ───────────────────────────────────────────────────────────

    @Test fun `first fix at timestamp is not duplicate`() {
        val fix = externalFix(timeUtc = Instant.ofEpochMilli(1_000_000))
        assertFalse(publisher.isDuplicate(fix))
    }

    @Test fun `same timestamp is duplicate`() {
        val t = Instant.ofEpochMilli(1_000_000)
        publisher.isDuplicate(externalFix(timeUtc = t))          // first — not duplicate
        assertTrue(publisher.isDuplicate(externalFix(timeUtc = t))) // second — duplicate
    }

    @Test fun `older timestamp after newer is duplicate`() {
        val newer = Instant.ofEpochMilli(2_000_000)
        val older = Instant.ofEpochMilli(1_999_000)   // 1 s older: re-delivered / out of order
        publisher.isDuplicate(externalFix(timeUtc = newer))
        assertTrue(publisher.isDuplicate(externalFix(timeUtc = older)))
    }

    @Test fun `time jumping back beyond the window resets the duplicate check`() {
        // First fix dated a day ahead (wrong tablet date before RMC/ZDA arrived), then real GNSS time:
        // the real fixes must publish instead of being dropped as duplicates for the whole session.
        val wrongDay = Instant.ofEpochMilli(2_000_000).plusSeconds(86_400)
        publisher.isDuplicate(externalFix(timeUtc = wrongDay))
        val real = Instant.ofEpochMilli(2_000_000)
        assertFalse(publisher.isDuplicate(externalFix(timeUtc = real)))
        assertFalse(publisher.isDuplicate(externalFix(timeUtc = real.plusSeconds(1))))
    }

    @Test fun `strictly later timestamp advances and is not duplicate`() {
        val t1 = Instant.ofEpochMilli(1_000_000)
        val t2 = Instant.ofEpochMilli(1_001_000)
        publisher.isDuplicate(externalFix(timeUtc = t1))
        assertFalse(publisher.isDuplicate(externalFix(timeUtc = t2)))
    }

    // ── fixToLocation — field mapping ─────────────────────────────────────────

    @Test fun `location provider is GPS_PROVIDER`() {
        val loc = publisher.fixToLocation(externalFix())
        assertEquals(LocationManager.GPS_PROVIDER, loc.provider)
    }

    @Test fun `latitude and longitude are copied exactly`() {
        val fix = externalFix(lat = 43.123456789, lon = -76.987654321)
        val loc = publisher.fixToLocation(fix)
        assertEquals(fix.latDeg, loc.latitude,  1e-10)
        assertEquals(fix.lonDeg, loc.longitude, 1e-10)
    }

    @Test fun `altitude is set when present`() {
        val fix = externalFix(altM = 147.83)
        val loc = publisher.fixToLocation(fix)
        assertTrue(loc.hasAltitude())
        assertEquals(147.83, loc.altitude, 1e-6)
    }

    @Test fun `altitude is absent when null`() {
        val fix = externalFix(altM = null)
        val loc = publisher.fixToLocation(fix)
        assertFalse(loc.hasAltitude())
    }

    @Test fun `omitted altitude also omits the vertical accuracy`() {
        val withAlt = publisher.fixToLocation(externalFix(altM = 150.0, vAccM = 0.03))
        assertTrue(withAlt.hasVerticalAccuracy())
        val omitted = publisher.fixToLocation(externalFix(altM = 150.0, vAccM = 0.03), omitAltitude = true)
        assertFalse(omitted.hasAltitude())
        assertFalse(omitted.hasVerticalAccuracy())
        assertTrue("horizontal accuracy is still set", omitted.hasAccuracy())
    }

    @Test fun `horizontal accuracy is set when present`() {
        val fix = externalFix(hAccM = 0.015)
        val loc = publisher.fixToLocation(fix)
        assertTrue(loc.hasAccuracy())
        assertEquals(0.015f, loc.accuracy, 0.0001f)
    }

    @Test fun `speed is set when present`() {
        val fix = externalFix(speedMps = 1.5)
        val loc = publisher.fixToLocation(fix)
        assertTrue(loc.hasSpeed())
        assertEquals(1.5f, loc.speed, 0.0001f)
    }

    @Test fun `speed is absent when null`() {
        val fix = externalFix(speedMps = null)
        val loc = publisher.fixToLocation(fix)
        assertFalse(loc.hasSpeed())
    }

    @Test fun `bearing is set from courseDeg when present`() {
        val fix = externalFix(courseDeg = 135.0)
        val loc = publisher.fixToLocation(fix)
        assertTrue(loc.hasBearing())
        assertEquals(135.0f, loc.bearing, 0.0001f)
    }

    @Test fun `bearing is absent when courseDeg is null`() {
        val fix = externalFix(courseDeg = null)
        val loc = publisher.fixToLocation(fix)
        assertFalse(loc.hasBearing())
    }

    @Test fun `time is set from fix timeUtc epoch millis`() {
        val t = Instant.ofEpochMilli(1_700_000_000_000L)
        val loc = publisher.fixToLocation(externalFix(timeUtc = t))
        assertEquals(1_700_000_000_000L, loc.time)
    }

    @Test fun `elapsedRealtimeNanos is non-negative`() {
        val loc = publisher.fixToLocation(externalFix())
        assertTrue(loc.elapsedRealtimeNanos >= 0)
    }

    // ── Integration guard: internal fix must never reach publishLocation ───────

    @Test fun `internal fix rejected by isExternalFix guard`() {
        val internalFix = externalFix(provider = Provider.INTERNAL)
        assertFalse(
            "Internal GPS fix must be rejected before reaching publishLocation",
            publisher.isExternalFix(internalFix)
        )
    }

    @Test fun `stale internal fix is doubly rejected`() {
        val now = Instant.now()
        assertFalse(publisher.isStale(externalFix(timeUtc = now)))
        val old = now.minusMillis(AndroidMockLocationPublisher.MAX_FIX_AGE_MS + 5_000)
        val fix = externalFix(provider = Provider.INTERNAL, timeUtc = old)
        assertFalse(publisher.isExternalFix(fix))
        assertTrue(publisher.isStale(fix))
    }

    @Test fun `invalid coordinates rejected before publishing`() {
        assertFalse(publisher.isValid(externalFix(lat = Double.NaN, lon = 0.0)))
        assertFalse(publisher.isValid(externalFix(lat = 0.0, lon = Double.NaN)))
    }

    // ── AR accuracy: antenna offset, accuracy fallback, back-dated timestamps ─────────────

    @Test fun `altitude is lowered by the antenna height above the tablet camera`() {
        val loc = publisher.fixToLocation(externalFix(altM = 150.5), antennaAboveCameraM = 0.6)
        assertEquals(149.9, loc.altitude, 1e-9)
    }

    @Test fun `missing accuracy falls back by solution type, not a flat 5 m`() {
        assertEquals(0.05f, publisher.fallbackHorizontalAccuracyM(RtkStatus.FIX), 0f)
        assertEquals(0.5f, publisher.fallbackHorizontalAccuracyM(RtkStatus.FLOAT), 0f)
        assertEquals(1.5f, publisher.fallbackHorizontalAccuracyM(RtkStatus.DGPS), 0f)
        assertEquals(5.0f, publisher.fallbackHorizontalAccuracyM(RtkStatus.SINGLE), 0f)
        val loc = publisher.fixToLocation(externalFix(hAccM = null))   // externalFix is RTK FIX
        assertEquals(0.05f, loc.accuracy, 0f)
    }

    @Test fun `elapsed realtime is back-dated by a plausible fix age`() {
        val fixMs = 1_800_000_000_000L
        val loc = publisher.fixToLocation(externalFix(timeUtc = Instant.ofEpochMilli(fixMs)),
            nowWallMs = fixMs + 250, nowElapsedNanos = 10_000_000_000L)
        assertEquals(10_000_000_000L - 250_000_000L, loc.elapsedRealtimeNanos)
        assertEquals(fixMs, loc.time)
    }

    @Test fun `implausible fix ages (clock skew) are not back-dated`() {
        val fixMs = 1_800_000_000_000L
        val future = publisher.fixToLocation(externalFix(timeUtc = Instant.ofEpochMilli(fixMs)),
            nowWallMs = fixMs - 500, nowElapsedNanos = 10_000_000_000L)    // device clock behind
        assertEquals(10_000_000_000L, future.elapsedRealtimeNanos)
        val old = publisher.fixToLocation(externalFix(timeUtc = Instant.ofEpochMilli(fixMs)),
            nowWallMs = fixMs + 5_000, nowElapsedNanos = 10_000_000_000L)  // 5 s: skew, not latency
        assertEquals(10_000_000_000L, old.elapsedRealtimeNanos)
    }}
