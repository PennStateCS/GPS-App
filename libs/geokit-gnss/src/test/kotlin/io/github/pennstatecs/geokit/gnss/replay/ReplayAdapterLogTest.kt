package io.github.pennstatecs.geokit.gnss.replay

import io.github.pennstatecs.geokit.gnss.link.LinkLog
import io.github.pennstatecs.geokit.gnss.link.LinkLogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Replay's lines go to the log it is given, under the GNSS tag. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayAdapterLogTest {

    private class Recording : LinkLog {
        val lines = mutableListOf<String>()
        override fun d(tag: String, message: () -> String) {}
        override fun i(tag: String, message: String) { lines += "I $tag $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "W $tag $message" }
        override fun logOnChange(tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String) = true
        override fun every(tag: String, key: String, intervalMs: Long, level: LinkLogLevel, throwable: Throwable?, message: () -> String): Boolean {
            lines += "${level.name[0]} $tag ${message()}"; return true
        }
        override fun resetThrottles(keyPrefix: String) {}
    }

    @Test fun `start and stop are logged, nothing plays without a source`() = runTest {
        val log = Recording()
        val adapter = ReplayAdapter(backgroundScope, dispatcher = UnconfinedTestDispatcher(testScheduler), log = log)
        adapter.start()
        assertEquals(emptyList<String>(), log.lines)
        assertEquals(null, adapter.sourceName)

        adapter.load(object : NmeaLineSource {
            override val name = "inline"
            override fun lines(): Flow<String> = listOf("not nmea", "\$GNGGA,1").asFlow()
        })
        assertEquals("inline", adapter.sourceName)
        adapter.start(); runCurrent()
        adapter.stop()
        assertEquals(listOf("I GNSS NMEA replay started source=inline", "I GNSS NMEA replay stopped"), log.lines)
    }
}
