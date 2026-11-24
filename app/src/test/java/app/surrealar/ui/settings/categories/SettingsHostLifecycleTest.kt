package app.surrealar.ui.settings.categories

import android.app.Application
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import app.surrealar.R
import app.surrealar.ui.common.BaseTwoPaneFragment
import app.surrealar.ui.settings.SettingsCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Settings host wiring — [BaseTwoPaneFragment] (category scope, view swap) plus
 * [ActiveSettingsCategory.show] / `replace(null)` exactly as SettingsFragment uses them — with
 * recording controllers. SettingsFragment itself needs Hilt injection (no Hilt test runtime here), so
 * this fragment stands in with the same two hooks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsHostLifecycleTest {

    /** A shared source each shown controller collects on its category scope. */
    private val source = MutableStateFlow(0)

    private class Recording(val id: Int, private val source: MutableStateFlow<Int>) : SettingsCategoryController {
        var created = 0
        var destroyed = 0
        override fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View {
            created++
            categoryScope.launch { source.collect {} }
            val label = "category $id"
            return TextView(container.context).apply { text = label }
        }
        override fun onDestroyView() { destroyed++ }
    }

    /** SettingsFragment's controller hooks on the real two-pane base. */
    class HostFragment : BaseTwoPaneFragment() {
        internal var controllers: Map<Int, SettingsCategoryController> = emptyMap()
        internal val active = ActiveSettingsCategory()
        override fun provideCategories() = controllers.keys.map { SettingsCategory(it, "Category $it", R.drawable.ic_file) }
        override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? =
            active.show(controllers[category.id], inflater, contentContainer, categoryScope)
        override fun onDestroyView() {
            active.replace(null)
            super.onDestroyView()
        }
    }

    private val a = Recording(1, source)
    private val b = Recording(2, source)
    private val c = Recording(3, source)
    private val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get().also {
        it.setTheme(R.style.Theme_SurveyingApp)
    }
    private val fragment = HostFragment().also { it.controllers = linkedMapOf(1 to a, 2 to b, 3 to c) }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start() {
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        idle()
    }

    /** Picks category [index] the way a user does (narrow layout: the category dropdown). */
    private fun select(index: Int) {
        val dropdown = fragment.requireView().findViewById<AutoCompleteTextView>(R.id.category_dropdown)
        dropdown.onItemClickListener.onItemClick(null, dropdown, index, index.toLong())
        idle()
    }

    private fun shownText(): String = fragment.requireView().findViewById<ViewGroup>(R.id.twopane_content_container)
        .let { (it.getChildAt(it.childCount - 1) as TextView).text.toString() }

    @Test fun `switching categories destroys the previous controller's view and keeps one collector`() {
        start()
        assertEquals(1, a.created); assertEquals(0, a.destroyed)
        assertEquals(1, source.subscriptionCount.value)

        select(1)
        assertEquals("A's view destroyed", 1, a.destroyed)
        assertEquals(1, b.created)
        assertSame(b, fragment.active.current)
        assertEquals("category 2", shownText())
        assertEquals("A's collector stopped with its category", 1, source.subscriptionCount.value)

        select(0)
        assertEquals(1, b.destroyed)
        assertEquals("the same controller instance is reused", 2, a.created)
        assertEquals(1, source.subscriptionCount.value)
    }

    @Test fun `rapid switching never stacks views, collectors or live controllers`() {
        start()
        repeat(20) { select(it % 3) }

        val all = listOf(a, b, c)
        assertEquals("exactly one controller has a live view", 1, all.sumOf { it.created } - all.sumOf { it.destroyed })
        assertEquals(1, source.subscriptionCount.value)
        val container = fragment.requireView().findViewById<ViewGroup>(R.id.twopane_content_container)
        assertEquals("header + placeholder + one category view", 3, container.childCount)
    }

    @Test fun `destroying and recreating the fragment view destroys the shown controller's view once and rebuilds once`() {
        start()
        select(2)
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        idle()

        assertEquals("C's view destroyed with the fragment view", 1, c.destroyed)
        assertEquals("nothing collects without a view", 0, source.subscriptionCount.value)
        assertEquals(null, fragment.active.current)

        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        idle()
        val all = listOf(a, b, c)
        assertEquals(1, all.sumOf { it.created } - all.sumOf { it.destroyed })
        assertEquals(1, source.subscriptionCount.value)
    }
}
