package app.surrealar.ui.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test
    fun rapidCalls_runOnlyTheLastOne_afterThePause() = runTest {
        val saved = mutableListOf<String>()
        val d = Debouncer(backgroundScope, 400L)
        for (text in listOf("1", "1.", "1.8")) {
            d.submit { saved += text }
            advanceTimeBy(100L)
            runCurrent()
        }
        assertEquals(emptyList<String>(), saved)          // still typing
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(listOf("1.8"), saved)
    }

    @Test
    fun separatePauses_eachSave() = runTest {
        val saved = mutableListOf<Int>()
        val d = Debouncer(backgroundScope, 400L)
        d.submit { saved += 1 }
        advanceTimeBy(401L); runCurrent()
        d.submit { saved += 2 }
        advanceTimeBy(401L); runCurrent()
        assertEquals(listOf(1, 2), saved)
    }
}
