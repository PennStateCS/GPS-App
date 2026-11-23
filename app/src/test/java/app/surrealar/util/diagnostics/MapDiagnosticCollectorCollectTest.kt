package app.surrealar.util.diagnostics

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [MapDiagnosticCollector.collect] on Robolectric: every section is present, permissions are
 * reported, and the Maps API key from the manifest is never printed (only its redacted form).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapDiagnosticCollectorCollectTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `collect renders every section with permissions`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val text = MapDiagnosticCollector.collect(app)
        for (header in listOf("=== Map Troubleshooting ===", "--- App identity ---", "--- Signing certificate",
                "--- Maps API key ---", "--- Google Play Services ---", "--- Network ---", "--- Map runtime ---",
                "--- Map display state ---", "--- Permissions ---")) {
            assertTrue("missing $header", text.contains(header))
        }
        assertTrue(text, text.contains("Package / applicationId : ${app.packageName}"))
        assertTrue(text, text.contains("Fine location           : true"))
        assertTrue(text, text.contains("Coarse location         : false"))
        assertTrue(text, text.contains("Installed GMS version   : not installed"))
    }

    @Test fun `the raw Maps API key never appears in the output`() {
        val key = runCatching {
            app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.google.android.geo.API_KEY")
        }.getOrNull()
        val text = MapDiagnosticCollector.collect(app)
        val line = text.lines().first { it.startsWith("Key metadata") }
        assertTrue(line, line.contains(MapDiagnosticCollector.redactApiKey(key)))
        if (!key.isNullOrBlank() && key.length > 8) assertFalse("API key leaked", text.contains(key))
    }
}
