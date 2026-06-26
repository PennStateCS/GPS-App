package com.example.surveyingapp.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerBadgeFormatterTest {

    @Test
    fun `zero shows 0`() {
        assertEquals("0", DrawerBadgeFormatter.format(0))
    }

    @Test
    fun `negative clamps to 0`() {
        assertEquals("0", DrawerBadgeFormatter.format(-5))
    }

    @Test
    fun `small counts are exact`() {
        assertEquals("1", DrawerBadgeFormatter.format(1))
        assertEquals("12", DrawerBadgeFormatter.format(12))
        assertEquals("999", DrawerBadgeFormatter.format(999))
    }

    @Test
    fun `large counts collapse to 999 plus`() {
        assertEquals("999+", DrawerBadgeFormatter.format(1000))
        assertEquals("999+", DrawerBadgeFormatter.format(54321))
    }
}
