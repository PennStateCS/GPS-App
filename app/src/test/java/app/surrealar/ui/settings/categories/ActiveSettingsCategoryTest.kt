package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** SettingsFragment's category switching: the replaced controller's view hook always runs. */
class ActiveSettingsCategoryTest {

    private class Recorder(val name: String, val log: MutableList<String>) : SettingsCategoryController {
        override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View =
            throw UnsupportedOperationException()
        override fun onDestroyView() { log += "destroy $name" }
    }

    @Test fun `showing another category destroys the previous controller's view`() {
        val log = mutableListOf<String>()
        val a = Recorder("a", log); val b = Recorder("b", log)
        val active = ActiveSettingsCategory()

        active.replace(a)
        assertEquals(emptyList<String>(), log)
        active.replace(b)
        assertEquals(listOf("destroy a"), log)
        assertSame(b, active.current)
    }

    @Test fun `re-showing the same category destroys its old view first`() {
        val log = mutableListOf<String>()
        val a = Recorder("a", log)
        val active = ActiveSettingsCategory()
        active.replace(a); active.replace(a)
        assertEquals(listOf("destroy a"), log)
        assertSame(a, active.current)
    }

    @Test fun `a category without a controller, and the fragment's onDestroyView, clear the current one`() {
        val log = mutableListOf<String>()
        val active = ActiveSettingsCategory()
        active.replace(Recorder("a", log))
        active.replace(null)                       // fragment.onDestroyView / non-controller category
        assertEquals(listOf("destroy a"), log)
        assertNull(active.current)
        active.replace(null)                       // nothing left to destroy
        assertEquals(listOf("destroy a"), log)
    }
}
