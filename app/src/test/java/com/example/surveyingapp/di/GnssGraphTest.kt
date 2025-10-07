package com.example.surveyingapp.di

import android.content.Context
import com.example.surveyingapp.domain.repository.SettingsRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class GnssGraphTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        context = Mockito.mock(Context::class.java)
        settingsRepository = Mockito.mock(SettingsRepository::class.java)
        // Reset singleton for each test (if a reset method exists)
        val field = GnssGraph::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `getOrCreate returns singleton instance`() {
        val graph1 = GnssGraph.getOrCreate(context, settingsRepository)
        val graph2 = GnssGraph.getOrCreate(context, settingsRepository)
        assertSame(graph1, graph2)
    }

    @Test
    fun `graph components are initialized`() {
        val graph = GnssGraph.getOrCreate(context, settingsRepository)
        assertNotNull(graph.scope)
        assertNotNull(graph.bus)
        assertNotNull(graph.switchboard)
        assertNotNull(graph.external)
        assertNotNull(graph.internal)
        assertNotNull(graph.inventory)
    }

    @Test
    fun `bus delegates to switchboard fixes`() {
        val graph = GnssGraph.getOrCreate(context, settingsRepository)
        assertSame(graph.bus.fixes, graph.switchboard.fixes)
    }

    @Test
    fun `singleton is thread safe`() {
        val nThreads = 10
        val latch = CountDownLatch(nThreads)
        val results = mutableListOf<GnssGraph.Graph>()
        val executor = Executors.newFixedThreadPool(nThreads)
        repeat(nThreads) {
            executor.execute {
                results.add(GnssGraph.getOrCreate(context, settingsRepository))
                latch.countDown()
            }
        }
        latch.await()
        val first = results.first()
        results.forEach { assertSame(first, it) }
    }

    @Test
    fun `getOrCreate handles null dependencies gracefully`() {
        try {
            GnssGraph.getOrCreate(context, null as SettingsRepository?)
            // Should not throw
        } catch (e: Exception) {
            fail("Should not throw with null SettingsRepository: ${e.message}")
        }
    }
}

