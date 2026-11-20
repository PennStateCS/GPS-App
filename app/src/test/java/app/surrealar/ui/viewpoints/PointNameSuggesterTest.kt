package app.surrealar.ui.viewpoints

import org.junit.Assert.assertEquals
import org.junit.Test

class PointNameSuggesterTest {

    @Test fun `first point gets 1`() {
        assertEquals("Point 1", PointNameSuggester.next("Point", emptyList()))
    }

    @Test fun `uses highest existing number, not the count`() {
        // Two points left after deletions: count + 1 would have suggested "Point 3", which exists.
        assertEquals("Point 8", PointNameSuggester.next("Point", listOf("Point 3", "Point 7")))
    }

    @Test fun `ignores other prefixes and free-form names`() {
        val names = listOf("Corner 12", "Point 2", "Point A", "Benchmark", "Pointer 9")
        assertEquals("Point 3", PointNameSuggester.next("Point", names))
    }

    @Test fun `prefix with regex characters is matched literally`() {
        assertEquals("BM (N) 5", PointNameSuggester.next("BM (N)", listOf("BM (N) 4")))
    }
}
