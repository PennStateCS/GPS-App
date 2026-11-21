package app.surrealar.util.diagnostics

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/** Report ID, email subject/body/recipient and ZIP file name for the diagnostic report share. */
object ReportNaming {

    private const val ID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   // Crockford base32: no I, L, O, U

    /** Short random ID ("7K3M-QX2D") that is in the email body and the summary inside the ZIP. */
    fun newReportId(random: Random = SecureRandom()): String {
        val chars = CharArray(8) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] }
        return String(chars, 0, 4) + "-" + String(chars, 4, 4)
    }

    fun subject(model: String, versionName: String, versionCode: Int, epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "SurReal AR report – $model – v$versionName ($versionCode) – " +
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun body(reportId: String): String =
        "What were you doing?\n\n\nWhat did you expect?\n\n\nWhat happened instead?\n\n\nReport ID: $reportId\n"

    /** To: the configured address, or nothing when it is blank. */
    fun recipients(configured: String): Array<String> =
        configured.trim().takeIf { it.isNotEmpty() }?.let { arrayOf(it) } ?: emptyArray()

    fun fileName(model: String, versionName: String, epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "surreal-ar-${slug(model)}-v${slug(versionName)}-" +
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US).format(Instant.ofEpochMilli(epochMs).atZone(zone)) + ".zip"

    private fun slug(s: String): String =
        s.lowercase(Locale.US).replace(Regex("[^a-z0-9.]+"), "-").trim('-').ifEmpty { "unknown" }
}