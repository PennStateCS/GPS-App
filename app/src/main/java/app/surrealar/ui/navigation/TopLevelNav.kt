package app.surrealar.ui.navigation

import androidx.annotation.IdRes
import androidx.navigation.NavOptions
import androidx.navigation.navOptions

/**
 * Nav options for jumping to a top-level screen (drawer item, Home shortcut). Pops back to the
 * graph's start destination (saving that stack's state), reuses the destination if it is already
 * on top and restores its saved state. Without this every switch pushed a fresh copy, so long
 * field sessions piled up fragments and ViewModels.
 */
fun topLevelNavOptions(@IdRes startDestinationId: Int): NavOptions = navOptions {
    popUpTo(startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
