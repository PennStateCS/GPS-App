package app.surrealar.ui.viewpoints

/**
 * Suggests the next auto-incremented point name for Settings → Coordinates → "Auto-increment names".
 *
 * Uses one more than the highest number already used with [prefix] ("Point 7" → "Point 8"), so the
 * suggestion never repeats an existing name after deletions and ignores points with other prefixes
 * (the old `count + 1` rule did both).
 */
object PointNameSuggester {
    fun next(prefix: String, existingNames: Collection<String>): String {
        val p = prefix.trim()
        val pattern = Regex("^" + Regex.escape(p) + "\\s*(\\d+)$", RegexOption.IGNORE_CASE)
        val highest = existingNames.mapNotNull { pattern.find(it.trim())?.groupValues?.get(1)?.toLongOrNull() }
            .maxOrNull() ?: 0L
        return "$p ${highest + 1}"
    }
}
