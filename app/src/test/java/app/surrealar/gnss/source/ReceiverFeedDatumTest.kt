package app.surrealar.gnss.source

import app.surrealar.settings.model.ReceiverDatum
import io.github.pennstatecs.geokit.gnss.datum.FixDatumNormalizer
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

/** [ReceiverFeedDatum]: unchanged normalisation maths plus the per-fix record of the datum applied. */
class ReceiverFeedDatumTest {

    private val datum = MutableStateFlow(ReceiverDatum.ITRF2014)
    private val feed = ReceiverFeedDatum(datum, capacity = 4)

    private fun fix(provider: Provider = Provider.EXTERNAL_TCP) = Fix(
        provider = provider, timeUtc = Instant.parse("2026-11-24T15:00:00Z"), timestampSource = TimestampSource.DEVICE,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = 100.0, altMslM = 133.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.0, pDop = 1.2, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = RtkStatus.FIX, satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = 0.0, courseDeg = 0.0
    )

    @Test fun internalFix_isUnchanged_andNotRecorded() {
        datum.value = ReceiverDatum.NAD83_2011
        val f = fix(Provider.INTERNAL)
        assertSame(f, feed.normalise(f))
        assertNull(feed.datumOf(f))
    }

    @Test fun itrf2014_returnsTheSameFix_recordedAsItrf2014() {
        val f = fix()
        val out = feed.normalise(f)
        assertSame(f, out)
        assertEquals(ReceiverDatum.ITRF2014, feed.datumOf(out))
    }

    @Test fun nad83_matchesFixDatumNormalizer_bitForBit_andIsRecorded() {
        datum.value = ReceiverDatum.NAD83_2011
        val f = fix()
        val out = feed.normalise(f)
        assertEquals(FixDatumNormalizer.nad83ToWgs84(f), out)
        assertNotEquals(f.latDeg, out.latDeg, 0.0)
        assertEquals(ReceiverDatum.NAD83_2011, feed.datumOf(out))
        assertNull("the raw input was not published", feed.datumOf(f))
    }

    @Test fun datumIsReadPerFix() {
        val a = feed.normalise(fix())
        datum.value = ReceiverDatum.NAD83_2011
        val b = feed.normalise(fix())
        assertEquals(ReceiverDatum.ITRF2014, feed.datumOf(a))
        assertEquals(ReceiverDatum.NAD83_2011, feed.datumOf(b))
    }

    @Test fun recordIsByInstance_notByEquality() {
        val a = feed.normalise(fix())
        val equalButOther = fix()
        assertEquals(a, equalButOther)
        assertNull(feed.datumOf(equalButOther))
    }

    @Test fun oldestRecordsAreEvicted_andReadAsUnknown() {
        val first = feed.normalise(fix())
        repeat(4) { feed.normalise(fix()) }
        assertNull(feed.datumOf(first))
    }
}
