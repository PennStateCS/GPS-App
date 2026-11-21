package app.surrealar.util.diagnostics

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.surrealar.util.DiagnosticsLogger

/**
 * LIFECYCLE events from one place: app foreground/background (ProcessLifecycleOwner), activity
 * create/destroy, configuration changes (rotation, dark mode, font scale, locale, size) and memory
 * pressure. Installed once from Application.onCreate.
 */
object LifecycleLogger {

    private const val TAG = DiagTags.LIFECYCLE
    @Volatile private var lastConfig: ConfigSummary? = null

    /** The parts of a Configuration worth a line when they change. */
    data class ConfigSummary(
        val orientation: String, val night: Boolean, val widthDp: Int, val heightDp: Int,
        val fontScale: Float, val locale: String,
    )

    fun summarize(c: Configuration): ConfigSummary = ConfigSummary(
        orientation = when (c.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "portrait"; Configuration.ORIENTATION_LANDSCAPE -> "landscape"; else -> "undefined"
        },
        night = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        widthDp = c.screenWidthDp, heightDp = c.screenHeightDp, fontScale = c.fontScale,
        locale = if (c.locales.isEmpty) "?" else c.locales[0].toLanguageTag(),
    )

    /** "orientation portrait→landscape, dark mode off→on"; null when nothing listed changed. */
    fun diff(old: ConfigSummary, new: ConfigSummary): String? = buildList {
        if (old.orientation != new.orientation) add("orientation ${old.orientation}→${new.orientation}")
        if (old.night != new.night) add("dark mode ${onOff(old.night)}→${onOff(new.night)}")
        if (old.widthDp != new.widthDp || old.heightDp != new.heightDp)
            add("size ${old.widthDp}x${old.heightDp}dp→${new.widthDp}x${new.heightDp}dp")
        if (old.fontScale != new.fontScale) add("font scale ${old.fontScale}→${new.fontScale}")
        if (old.locale != new.locale) add("locale ${old.locale}→${new.locale}")
    }.takeIf { it.isNotEmpty() }?.joinToString(", ")

    fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        5 -> "RUNNING_MODERATE"; 10 -> "RUNNING_LOW"; 15 -> "RUNNING_CRITICAL"; 60 -> "MODERATE"
        else -> "LEVEL_$level"
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    fun install(app: Application) {
        lastConfig = summarize(app.resources.configuration)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = DiagnosticsLogger.i(TAG, "App foreground")
            override fun onStop(owner: LifecycleOwner) = DiagnosticsLogger.i(TAG, "App background")
        })
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, saved: Bundle?) =
                DiagnosticsLogger.i(TAG, "Activity created ${a.javaClass.simpleName}${if (saved != null) " (restored)" else ""}")
            override fun onActivityDestroyed(a: Activity) =
                DiagnosticsLogger.i(TAG, "Activity destroyed ${a.javaClass.simpleName}" + when {
                    a.isChangingConfigurations -> " (configuration change)"
                    a.isFinishing -> " (finishing)"
                    else -> " (by the system)"
                })
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, out: Bundle) = Unit
        })
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val now = summarize(newConfig)
                val before = lastConfig
                lastConfig = now
                if (before != null) diff(before, now)?.let { DiagnosticsLogger.i(TAG, "Configuration changed: $it") }
            }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = DiagnosticsLogger.w(TAG, "Low memory")
            override fun onTrimMemory(level: Int) {
                // UI_HIDDEN comes with every trip to the background; the foreground/background line covers it.
                if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                    DiagnosticsLogger.w(TAG, "Trim memory level=${trimLevelName(level)}")
            }
        })
    }
}