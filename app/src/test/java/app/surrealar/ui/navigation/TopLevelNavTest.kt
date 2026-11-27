package app.surrealar.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelNavTest {

    @Test
    fun `pops to the start destination and saves its state`() {
        val opts = topLevelNavOptions(42)
        assertEquals(42, opts.popUpToId)
        assertFalse(opts.isPopUpToInclusive())
        assertTrue(opts.shouldPopUpToSaveState())
    }

    @Test
    fun `launches single top and restores state`() {
        val opts = topLevelNavOptions(7)
        assertTrue(opts.shouldLaunchSingleTop())
        assertTrue(opts.shouldRestoreState())
    }

    @Test
    fun `jumps with arguments pop to start but never restore stale state`() {
        val opts = topLevelNavOptionsWithArgs(42)
        assertEquals(42, opts.popUpToId)
        assertFalse(opts.isPopUpToInclusive())
        assertTrue(opts.shouldLaunchSingleTop())
        assertFalse("a restored copy would ignore the new arguments", opts.shouldRestoreState())
    }
}
