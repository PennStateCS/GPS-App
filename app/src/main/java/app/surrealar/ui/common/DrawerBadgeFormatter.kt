package app.surrealar.ui.common

/**
 * Formats a count for the navigation-drawer badges (Coordinates / Models).
 *
 * Rule: 0–999 shown exactly; 1000+ collapses to "999+" so the badge stays a fixed, compact width
 * and never overflows the drawer row. Negative inputs are clamped to 0.
 */
object DrawerBadgeFormatter {
    const val MAX_EXACT = 999

    fun format(count: Int): String = when {
        count <= 0 -> "0"
        count > MAX_EXACT -> "$MAX_EXACT+"
        else -> count.toString()
    }
}
