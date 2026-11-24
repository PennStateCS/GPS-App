package app.surrealar.ui.settings.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope

/**
 * The UI of one Settings category. [app.surrealar.ui.settings.SettingsFragment] keeps one instance
 * per category for the fragment's lifetime, so state that outlived a category switch when it lived in
 * the fragment (an import in progress, the receiver connect attempt) still does. The fragment calls
 * [createView] each time the category is shown and [onDestroyView] when the category is replaced or
 * the fragment's view is destroyed.
 */
internal interface SettingsCategoryController {
    /**
     * Inflates and binds the category's view (not attached to [container]). [categoryScope] is
     * cancelled when another category is shown or the view is destroyed: use it for loads, collectors
     * and status display, never for a write that must complete.
     */
    fun createView(inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View

    /** Drops references to the views built by [createView]. */
    fun onDestroyView() {}
}

/** The shown category's controller; [replace] runs the previous one's [SettingsCategoryController.onDestroyView]. */
internal class ActiveSettingsCategory {
    var current: SettingsCategoryController? = null
        private set

    /** Destroys the current controller's view (if any), then makes [next] current (null = none). */
    fun replace(next: SettingsCategoryController?) {
        current?.onDestroyView()
        current = next
    }

    /**
     * SettingsFragment.buildCategoryContent: makes [next] current (destroying the previous view) and
     * builds its view with [categoryScope] (the host has already cancelled the previous category's).
     */
    fun show(next: SettingsCategoryController?, inflater: LayoutInflater, container: ViewGroup, categoryScope: CoroutineScope): View? {
        replace(next)
        return next?.createView(inflater, container, categoryScope)
    }
}
