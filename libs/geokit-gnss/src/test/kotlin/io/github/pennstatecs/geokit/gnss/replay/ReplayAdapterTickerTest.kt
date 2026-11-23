package io.github.pennstatecs.geokit.gnss.replay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 10 Hz GST-hold ticker only runs while a replay file is playing. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayAdapterTickerTest {

    private class Lines(private val lines: List<String>) : NmeaLineSource {
        override val name = "inline"
        override fun lines(): Flow<String> = lines.asFlow()
    }

    @Test fun `the hold ticker stops when the file ends`() = runTest {
        val adapter = ReplayAdapter(backgroundScope, dispatcher = UnconfinedTestDispatcher(testScheduler))
        adapter.load(Lines(listOf(
            "\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,0.8,395.539,M,-33.145,M,1.0,0001",
            "\$GNGGA,172425.80,4118.4448800,N,07601.0300517,W,4,22,0.8,395.539,M,-33.145,M,1.0,0001",
        )))
        adapter.start()
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertFalse("no 10 Hz ticker left running after the file ended", adapter.isTicking)
        adapter.stop()
    }

    @Test fun `stop ends the ticker of a replay still playing`() = runTest {
        val adapter = ReplayAdapter(backgroundScope, dispatcher = UnconfinedTestDispatcher(testScheduler))
        adapter.load(object : NmeaLineSource {
            override val name = "endless"
            override fun lines(): Flow<String> = kotlinx.coroutines.flow.flow { while (true) kotlinx.coroutines.delay(1_000) }
        })
        adapter.start(); runCurrent()
        assertTrue(adapter.isTicking)
        adapter.stop()
        assertFalse(adapter.isTicking)
    }
}