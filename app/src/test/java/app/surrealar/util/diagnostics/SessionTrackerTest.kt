package app.surrealar.util.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionTrackerTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("session-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val info = SessionTracker.Info("1.2", 12, "debug", "abc1234", "Samsung SM-X940", "15", 35, "en-US", "America/New_York")
    private var n = 0
    private fun ids() = { "id-${++n}-0000-0000" }

    @Test fun `first start has no previous session and creates the install id once`() {
        val s1 = SessionTracker(prefs).start(info, ids())
        assertNull(s1.lastClean)
        assertTrue(s1.line, s1.line.startsWith("SESSION_START session="))
        listOf("install=${s1.installId}", "version=1.2 code=12 build=debug", "device=\"Samsung SM-X940\"",
            "android=15 sdk=35", "locale=en-US tz=America/New_York", "lastSessionClean=unknown(first run)")
            .forEach { assertTrue(it, s1.line.contains(it)) }
        val s2 = SessionTracker(prefs).start(info, ids())
        assertEquals(s1.installId, s2.installId)
        assertTrue(s2.sessionId != s1.sessionId)
    }

    @Test fun `a session left open is reported as not clean, a stopped one as clean`() {
        SessionTracker(prefs).start(info, ids())                 // never stopped (crash)
        val crashed = SessionTracker(prefs).start(info, ids())
        assertFalse(crashed.lastClean!!)
        SessionTracker(prefs).markStopped()                      // went to background normally
        assertTrue(SessionTracker(prefs).start(info, ids()).lastClean!!)
    }
}