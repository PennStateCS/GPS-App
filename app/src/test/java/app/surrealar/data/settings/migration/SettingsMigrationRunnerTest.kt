package app.surrealar.data.settings.migration

import app.surrealar.settings.SettingsDefaults
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationRunnerTest {

    /** In-memory version store standing in for the DataStore-backed read/write. */
    private class FakeVersionStore(var version: Int? = null) {
        var writes = 0
        fun runner() = SettingsMigrationRunner(
            readVersion = { version },
            writeVersion = { version = it; writes++ },
        )
    }

    private val current = SettingsDefaults.CURRENT_SETTINGS_SCHEMA_VERSION

    @Test
    fun `fresh install (no version) migrates to current`() = runBlocking {
        val store = FakeVersionStore(version = null)
        val result = store.runner().migrateIfNeeded()

        assertEquals(0, result.fromVersion)
        assertEquals(current, result.toVersion)
        assertTrue(result.migrated)
        assertNull(result.error)
        assertEquals(current, store.version)
    }

    @Test
    fun `old version migrates to current`() = runBlocking {
        val store = FakeVersionStore(version = 0)
        val result = store.runner().migrateIfNeeded()
        assertTrue(result.migrated)
        assertEquals(current, store.version)
    }

    @Test
    fun `already-current version is a no-op and does not rewrite`() = runBlocking {
        val store = FakeVersionStore(version = current)
        val result = store.runner().migrateIfNeeded()
        assertFalse("should not migrate when already current", result.migrated)
        assertEquals(0, store.writes)
    }

    @Test
    fun `running twice is idempotent`() = runBlocking {
        val store = FakeVersionStore(version = null)
        store.runner().migrateIfNeeded()
        val second = store.runner().migrateIfNeeded()
        assertFalse("second run is a no-op", second.migrated)
        assertEquals("only one write across two runs", 1, store.writes)
        assertEquals(current, store.version)
    }

    @Test
    fun `read failure is captured as an error, not thrown`() = runBlocking {
        val runner = SettingsMigrationRunner(
            readVersion = { throw RuntimeException("datastore boom") },
            writeVersion = { },
        )
        val result = runner.migrateIfNeeded()
        assertEquals("datastore boom", result.error)
        assertFalse(result.migrated)
    }
}
