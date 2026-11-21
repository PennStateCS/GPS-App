package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat

/** sessions.txt: one row per SESSION_START and how that session ended. */
object SessionsCollector {

    private fun field(m: String, name: String) = Regex("$name=(\"[^\"]*\"|\\S+)").find(m)?.groupValues?.get(1)?.trim('"')

    fun render(idx: LogIndex): String = buildString {
        appendLine("=== Sessions ===")
        appendLine("started                        session       version        how it ended")
        val s = idx.sessions
        if (s.isEmpty()) appendLine("(no SESSION_START lines in the logs)")
        s.forEachIndexed { i, e ->
            val id = field(e.message, "session") ?: "?"
            val version = "${field(e.message, "version") ?: "?"} (${field(e.message, "code") ?: "?"})"
            val next = s.getOrNull(i + 1)
            val fatal = idx.crashes.firstOrNull { it.message.contains("session=$id") && it.level == 'E' }
            val ended = when {
                fatal != null -> "CRASH at ${LogLineFormat.timestamp(fatal.epochMs)}"
                next == null -> "running (this report)"
                field(next.message, "lastSessionClean") == "true" -> "normally"
                field(next.message, "lastSessionClean") == "false" -> "NOT cleanly (ANR, kill or crash while in use)"
                else -> "unknown"
            }
            appendLine("${LogLineFormat.timestamp(e.epochMs)}  ${id.padEnd(12)}  ${version.padEnd(13)}  $ended")
        }
    }
}