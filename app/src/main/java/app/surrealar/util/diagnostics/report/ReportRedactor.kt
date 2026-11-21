package app.surrealar.util.diagnostics.report

/**
 * Last line of defence: everything written into the report passes through [scrub], so a log line
 * that slipped through with raw NMEA, an API key, a password or a coordinate never leaves the device.
 *  - NMEA sentences ($GNGGA,…)            → [NMEA removed]
 *  - Google API keys (AIza…)              → [key removed]
 *  - password= / token: / secret= values  → (redacted)   ("(set)" and "(empty)" are kept)
 *  - decimals with 6+ places (lat/lon)    → [coord removed]
 */
object ReportRedactor {
    private val NMEA = Regex("""\$[A-Z]{5},\S*""")
    private val API_KEY = Regex("""AIza[0-9A-Za-z_\-]{20,}""")
    private val SECRET = Regex("""(?i)\b(\w*(?:password|passwd|pwd|secret|token|api[_-]?key))(\s*[=:]\s*)(?!\((?:set|empty)\))("[^"]*"|\S+)""")
    private val COORD = Regex("""(?<![\w.])-?\d{1,3}\.\d{6,}(?![\w.])""")

    fun scrub(text: String): String {
        var t = text
        if (t.indexOf('$') >= 0) t = NMEA.replace(t, "[NMEA removed]")
        if (t.contains("AIza")) t = API_KEY.replace(t, "[key removed]")
        t = SECRET.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "(redacted)" }
        t = COORD.replace(t, "[coord removed]")
        return t
    }
}