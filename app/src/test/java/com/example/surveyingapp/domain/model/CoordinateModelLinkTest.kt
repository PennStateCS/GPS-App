package com.example.surveyingapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateModelLinkTest {

    private fun coord(
        modelId: String? = null,
        iconKey: String? = null,
        icon: String = ""
    ) = Coordinate(
        id = "c1", name = "P1", latitude = 1.0, longitude = 2.0, altitude = 0.0,
        timestamp = 0L, icon = icon, color = 0,
        modelId = modelId, iconKey = iconKey
    )

    @Test
    fun prefersExplicitModelId() {
        val c = coord(modelId = "abc", icon = "model:legacy")
        assertEquals("abc", c.linkedModelId)
        assertTrue(c.hasLinkedModel)
        assertNull(c.displayIconKey)
    }

    @Test
    fun fallsBackToLegacyIcon() {
        val c = coord(icon = "model:legacy42")
        assertEquals("legacy42", c.linkedModelId)
        assertTrue(c.hasLinkedModel)
    }

    @Test
    fun builtinIconResolvesAsIconKey() {
        assertEquals("ic_pin", coord(iconKey = "ic_pin").displayIconKey)
        assertEquals("ic_star", coord(icon = "ic_star").displayIconKey)
    }

    @Test
    fun noModelNoIcon() {
        val c = coord(icon = "")
        assertNull(c.linkedModelId)
        assertFalse(c.hasLinkedModel)
        assertNull(c.displayIconKey)
    }

    @Test
    fun toLegacyIcon_format() {
        assertEquals("model:xyz", CoordinateModelLink.toLegacyIcon("xyz"))
        assertEquals("xyz", CoordinateModelLink.resolveModelId(null, "model:xyz"))
    }
}
