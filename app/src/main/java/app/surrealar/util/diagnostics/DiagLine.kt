package app.surrealar.util.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds a [DiagEvents] line: `CODE key=value … [extra] [(human text)]`.
 *
 *  - Null values are left out; an empty value reads `unknown`; spaces in a value become `_` so
 *    [DiagEvents.parse] (which splits on spaces) reads every field back.
 *  - [extra] is already-formatted `key=value` text kept from an older message.
 *  - [text] is the older human sentence, kept in parentheses at the end so people (and report rules
 *    written for the old message) still recognise the line. It must not contain `=`.
 */
object DiagLine {

    fun of(code: String, vararg fields: Pair<String, Any?>, extra: String? = null, text: String? = null): String {
        val sb = StringBuilder(code)
        for ((k, v) in fields) {
            if (v == null) continue
            sb.append(' ').append(k).append('=').append(clean(v.toString()))
        }
        if (!extra.isNullOrBlank()) sb.append(' ').append(extra.trim())
        if (!text.isNullOrBlank()) sb.append(" (").append(text).append(')')
        return sb.toString()
    }

    private fun clean(v: String): String = if (v.isBlank()) "unknown" else v.trim().replace(' ', '_')
}

/**
 * Short correlation ids for [DiagEvents] keys (`conn=c3`, `capture=cap2`, `ar=a1`, `op=op7`): one
 * counter per prefix for the life of the process, so with `session=` (the app session) an id names
 * one operation. Minted once per operation and reused on all its lines — never per line.
 */
object DiagIds {
    const val CONN = "c"
    const val CAPTURE = "cap"
    const val AR = "a"
    const val OP = "op"

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun next(prefix: String): String = prefix + counters.getOrPut(prefix) { AtomicInteger() }.incrementAndGet()
}
