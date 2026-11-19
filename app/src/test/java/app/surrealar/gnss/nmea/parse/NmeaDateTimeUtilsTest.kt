package app.surrealar.gnss.nmea.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class NmeaDateTimeUtilsTest {

    @Test fun time_wholeSeconds() =
        assertEquals(LocalTime.of(17, 28, 14), NmeaDateTimeUtils.parseNmeaTime("172814"))

    @Test fun time_fractionalSeconds_toNanos() {
        assertEquals(LocalTime.of(17, 28, 14, 500_000_000), NmeaDateTimeUtils.parseNmeaTime("172814.5"))
        assertEquals(LocalTime.of(17, 28, 14, 123_000_000), NmeaDateTimeUtils.parseNmeaTime("172814.123"))
        // Extra precision beyond nanoseconds is truncated, not rejected.
        assertEquals(LocalTime.of(0, 0, 0, 123_456_789), NmeaDateTimeUtils.parseNmeaTime("000000.1234567899"))
    }

    @Test fun time_invalidInputs_returnNull() {
        listOf(null, "", "ab2814", "250000", "176000", "172860").forEach {
            assertNull("'$it' should not parse", NmeaDateTimeUtils.parseNmeaTime(it))
        }
    }

    @Test fun date_basic() =
        assertEquals(LocalDate.of(2026, 11, 19), NmeaDateTimeUtils.parseNmeaDate("191126"))

    @Test fun date_twoDigitYearPivot() {
        assertEquals(2079, NmeaDateTimeUtils.parseNmeaDate("010179")!!.year)
        assertEquals(1980, NmeaDateTimeUtils.parseNmeaDate("010180")!!.year)
        assertEquals(1999, NmeaDateTimeUtils.parseNmeaDate("311299")!!.year)
        assertEquals(2000, NmeaDateTimeUtils.parseNmeaDate("010100")!!.year)
    }

    @Test fun date_invalidInputs_returnNull() {
        listOf(null, "", "1911", "1911260", "321126", "191326", "300226", "ab1126").forEach {
            assertNull("'$it' should not parse", NmeaDateTimeUtils.parseNmeaDate(it))
        }
    }

    @Test fun dateTime_combinesAsUtcEpochMillis() {
        // 2026-11-19T17:28:14.500Z
        assertEquals(1_795_109_294_500L, NmeaDateTimeUtils.parseNmeaDateTime("172814.5", "191126"))
    }

    @Test fun dateTime_requiresBothParts() {
        assertNull(NmeaDateTimeUtils.parseNmeaDateTime("172814", null))
        assertNull(NmeaDateTimeUtils.parseNmeaDateTime(null, "191126"))
        assertNull(NmeaDateTimeUtils.parseNmeaDateTime("bad", "191126"))
    }
}
