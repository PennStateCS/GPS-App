package app.surrealar.util.diagnostics

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import app.surrealar.util.DiagnosticsLogger

/**
 * NAV: one line per destination, with argument NAMES only (values can hold point names or IDs).
 * Also keeps [DiagnosticsLogger.currentScreen] so a crash record says which screen was open.
 */
object NavLogger : NavController.OnDestinationChangedListener {

    /** "nav_home" from "app.surrealar:id/nav_home"; falls back to the label or the numeric id. */
    fun screenName(displayName: String?, label: CharSequence?, id: Int): String =
        displayName?.substringAfter('/')?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
            ?: label?.toString()?.takeIf { it.isNotBlank() }
            ?: "id_$id"

    fun line(screen: String, argNames: Collection<String>): String =
        "Screen $screen" + if (argNames.isEmpty()) "" else " args=[${argNames.sorted().joinToString(",")}]"

    /**
     * Identity of a visit: the screen plus its back-stack entry id. A recreated Activity re-adds the
     * listener and NavController re-reports the current (restored) entry with the same id, so that
     * repeat is not logged; a real navigation, even back to the same screen, gets a new entry id.
     */
    fun visitKey(screen: String, entryId: String?): String = "$screen#${entryId ?: "?"}"

    override fun onDestinationChanged(controller: NavController, destination: NavDestination, arguments: Bundle?) {
        // displayName throws for an id with no resource name; screenName then falls back.
        val screen = screenName(runCatching { destination.displayName }.getOrNull(), destination.label, destination.id)
        DiagnosticsLogger.currentScreen = screen
        val names = arguments?.keySet()?.filterNot { it.startsWith("android-support-nav:") }.orEmpty()
        val entryId = runCatching { controller.currentBackStackEntry?.id }.getOrNull()
        DiagnosticsLogger.logOnChange(DiagTags.NAV, "nav.screen", visitKey(screen, entryId)) { line(screen, names) }
    }
}