package app.surrealar.util

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagnosticsLoggerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File

    @Before fun setUp() { dir = tmp.newFolder("logs"); DiagnosticsLogger.initForTest(dir) }
    @After fun tearDown() { DiagnosticsLogger.currentScreen = null; DiagnosticsLogger.sessionId = null }

    @Test fun `crash write is on disk as soon as the handler returns`() {
        DiagnosticsLogger.currentScreen = "homeFragment"
        DiagnosticsLogger.sessionId = "s-1234"
        val t = Thread(null, {}, "worker-7")
        val crash = IllegalStateException("outer", IllegalArgumentException("root cause"))
        DiagnosticsLogger.writeCrashSync(t, crash, fatal = true)
        // No flush, no waiting: the write must already be there.
        for (name in listOf("app-errors-current.txt", "app-log-current.txt")) {
            val text = File(dir, name).readText()
            assertTrue(name, text.contains("thread=worker-7"))
            assertTrue(name, text.contains("screen=homeFragment"))
            assertTrue(name, text.contains("session=s-1234"))
            assertTrue(name, text.contains("IllegalStateException: outer"))
            assertTrue("cause included in $name", text.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
        }
    }

    @Test fun `coroutine crash is marked not fatal`() {
        DiagnosticsLogger.writeCrashSync(Thread.currentThread(), RuntimeException("x"), fatal = false)
        assertTrue(File(dir, "app-errors-current.txt").readText().contains("not fatal"))
    }
}