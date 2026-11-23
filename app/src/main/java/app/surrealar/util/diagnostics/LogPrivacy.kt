package app.surrealar.util.diagnostics

import java.security.MessageDigest
import java.util.Locale

/**
 * Stand-ins for user-chosen text in exported log lines (the diagnostic ZIP is shared by email).
 * Point names, model names and imported file names are personal: lines carry a short, stable hash
 * instead, so the same item can still be followed from line to line without revealing what it is.
 *
 *  - [ref]       "Smith Residence"           → `#4f1c2a`
 *  - [file]      "/x/JohnDoe-house.glb"      → `#9b03de.glb` (hash of the basename + its extension)
 *  - [errorType] an exception                → its simple class name (messages can hold paths/names)
 *
 * The hash is the first 3 bytes of SHA-256: enough to tell items apart in one report, far too
 * short to look anything up. Missing values read `unknown`, as the logging rules require.
 */
object LogPrivacy {

    fun ref(text: String?): String =
        if (text.isNullOrBlank()) "unknown" else "#" + shortHash(text.trim())

    fun file(name: String?): String {
        if (name.isNullOrBlank()) return "unknown"
        val base = name.trim().substringAfterLast('/').substringAfterLast('\\')
        if (base.isEmpty()) return "unknown"
        val ext = base.substringAfterLast('.', "").lowercase(Locale.US)
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) && base.length > it.length + 1 }
        return "#" + shortHash(base) + (ext?.let { ".$it" } ?: "")
    }

    fun errorType(t: Throwable?): String = t?.javaClass?.simpleName ?: "unknown"

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .take(3).joinToString("") { "%02x".format(it) }
}
