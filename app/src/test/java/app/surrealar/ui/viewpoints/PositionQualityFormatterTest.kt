package app.surrealar.ui.viewpoints

import app.surrealar.ui.viewpoints.PositionQualityFormatter.AccuracySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionQualityFormatterTest {

    @Test fun accuracyPrecision_twoDecimalsUnderOneMeter_oneDecimalAtOrAbove() {
        assertEquals("±0.04 m", PositionQualityFormatter.reported(0.04))
        assertEquals("±0.35 m", PositionQualityFormatter.reported(0.349))
        assertEquals("±1.2 m", PositionQualityFormatter.reported(1.23))
        assertEquals("~0.35 m", PositionQualityFormatter.estimated(0.35))
        assertEquals("~1.2 m", PositionQualityFormatter.estimated(1.2))
    }

    @Test fun horizontal_reported_whenReceiverValuePresent() {
        val r = PositionQualityFormatter.horizontalAccuracy(reportedM = 0.04, estimatedM = null)
        assertEquals("±0.04 m reported by receiver", r.text)
        assertEquals(AccuracySource.REPORTED, r.source)
    }

    @Test fun horizontal_estimated_whenOnlyEstimatePresent() {
        val r = PositionQualityFormatter.horizontalAccuracy(reportedM = null, estimatedM = 0.35)
        assertEquals("~0.35 m estimated from HDOP", r.text)
        assertEquals(AccuracySource.ESTIMATED, r.source)
    }

    @Test fun horizontal_reportedWins_overEstimate() {
        val r = PositionQualityFormatter.horizontalAccuracy(reportedM = 0.04, estimatedM = 0.35)
        assertEquals(AccuracySource.REPORTED, r.source)
    }

    @Test fun horizontal_unavailable_whenNeitherPresent() {
        val r = PositionQualityFormatter.horizontalAccuracy(reportedM = null, estimatedM = null)
        assertEquals("Not reported by receiver", r.text)
        assertEquals(AccuracySource.UNAVAILABLE, r.source)
    }

    @Test fun vertical_omittedWhenUnavailable() {
        assertNull(PositionQualityFormatter.verticalAccuracy(null, null))
        assertEquals("±0.07 m", PositionQualityFormatter.verticalAccuracy(0.07, null))
        assertEquals("~0.50 m", PositionQualityFormatter.verticalAccuracy(null, 0.5))
    }

    @Test fun horizontalCompact_forGridCell() {
        assertEquals("±0.04 m", PositionQualityFormatter.horizontalAccuracyCompact(0.04, null))
        assertEquals("~0.35 m", PositionQualityFormatter.horizontalAccuracyCompact(null, 0.35))
        assertEquals("—", PositionQualityFormatter.horizontalAccuracyCompact(null, null))
    }

    @Test fun dop_oneDecimal() = assertEquals("0.8", PositionQualityFormatter.dop(0.84))

    @Test fun correctionAge_oneDecimalSeconds_nullWhenNegativeOrMissing() {
        assertEquals("2.1 sec", PositionQualityFormatter.correctionAgeSec(2.13))
        assertNull(PositionQualityFormatter.correctionAgeSec(null))
        assertNull(PositionQualityFormatter.correctionAgeSec(-1.0))
    }
}
