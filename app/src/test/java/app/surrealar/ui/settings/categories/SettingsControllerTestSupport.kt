package app.surrealar.ui.settings.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import app.surrealar.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/** Plain fragment standing in for SettingsFragment (no Hilt): gives controllers a real host + view. */
internal class ControllerHostFragment : Fragment() {
    lateinit var container: FrameLayout
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).also { container = it }
}

/** A started activity hosting a [ControllerHostFragment] with the app theme. */
internal class ControllerHost {
    val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get().also {
        it.setTheme(R.style.Theme_SurveyingApp)
    }
    val fragment = ControllerHostFragment()

    init {
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
    }

    val inflater: LayoutInflater get() = LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_SurveyingApp))

    /** Builds [controller]'s view into the host container, like SettingsFragment does. */
    fun show(controller: SettingsCategoryController, categoryScope: CoroutineScope): View =
        controller.createView(inflater, fragment.container, categoryScope).also {
            fragment.container.addView(it)
            ShadowLooper.idleMainLooper()
        }
}

/** A Main-dispatcher scope; tests set Main to an unconfined test dispatcher so launches run eagerly. */
internal fun mainScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
