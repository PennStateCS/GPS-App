package app.surrealar.data.settings.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsCorruptionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a corrupt settings file reads as defaults instead of throwing`() = runBlocking {
        val file = File(tmp.root, "app_settings.preferences_pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F, 0x12, 0x01, 0x00, 0x13)) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ds = PreferenceDataStoreFactory.create(corruptionHandler = settingsCorruptionHandler, scope = scope) { file }
        assertNull(ds.data.first()[stringPreferencesKey("location_source")])
        scope.cancel()
    }
}