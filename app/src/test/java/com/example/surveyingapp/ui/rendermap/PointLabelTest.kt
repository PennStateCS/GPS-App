package com.example.surveyingapp.ui.rendermap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointLabelTest {

    @Test
    fun `mode cycles Off Name Elevation Distance Off`() {
        assertEquals(PointLabelMode.NAME, PointLabelMode.OFF.next())
        assertEquals(PointLabelMode.ELEVATION, PointLabelMode.NAME.next())
        assertEquals(PointLabelMode.DISTANCE, PointLabelMode.ELEVATION.next())
        assertEquals(PointLabelMode.OFF, PointLabelMode.DISTANCE.next())
    }

    @Test
    fun `off mode produces no label`() {
        assertNull(PointLabel.labelText(PointLabelMode.OFF, "Point 1", 399.21, 4.2))
    }

    @Test
    fun `name mode shows name with fallback`() {
        assertEquals("Point 104", PointLabel.labelText(PointLabelMode.NAME, "Point 104", 399.21, 4.2))
        assertEquals("Point", PointLabel.labelText(PointLabelMode.NAME, "  ", null, null))
    }

    @Test
    fun `elevation mode shows name and elevation, name-only when missing`() {
        assertEquals("Point 104\nElev 399.21 m",
            PointLabel.labelText(PointLabelMode.ELEVATION, "Point 104", 399.21, null))
        assertEquals("Point 104",
            PointLabel.labelText(PointLabelMode.ELEVATION, "Point 104", null, null))
    }

    @Test
    fun `distance mode shows name and distance, name-only when no live fix`() {
        assertEquals("Point 104\n4.2 m",
            PointLabel.labelText(PointLabelMode.DISTANCE, "Point 104", 399.21, 4.2))
        // No live position → name only (less cluttered than a placeholder).
        assertEquals("Point 104",
            PointLabel.labelText(PointLabelMode.DISTANCE, "Point 104", 399.21, null))
    }

    @Test
    fun `distance formatting scales with magnitude`() {
        assertEquals("0.40 m", PointLabel.formatDistance(0.4))
        assertEquals("4.2 m", PointLabel.formatDistance(4.2))
        assertEquals("120 m", PointLabel.formatDistance(120.0))
        assertEquals("1.5 km", PointLabel.formatDistance(1500.0))
    }

    @Test
    fun `content descriptions describe state and next action`() {
        assertEquals("Point labels off. Tap to show point names.",
            PointLabel.contentDescription(PointLabelMode.OFF))
        assertEquals("Point labels show distance. Tap to turn labels off.",
            PointLabel.contentDescription(PointLabelMode.DISTANCE))
    }
}
