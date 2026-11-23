package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogPrivacy
import kotlin.math.abs

/**
 * Last line of defence: everything written into the report passes through [scrub], so a value that
 * slipped through a log line never leaves the device. Sources are expected to keep secrets and
 * personal data out in the first place (see docs/diagnostics-logging.md and [LogPrivacy]); these
 * rules catch what they missed. Rules run in this order:
 *
 * Raw GNSS data
 *  - NMEA sentences (`$GNGGA,…`, also without the `$`)          → `[NMEA removed]`
 *
 * Secrets
 *  - Google API keys (`AIza…`)                                    → `[key removed]`
 *  - `Authorization:`/`Proxy-Authorization:` header values        → `Authorization: (redacted)`
 *  - `Bearer <token>`                                             → `Bearer (redacted)`
 *  - JSON Web Tokens (`eyJ….….…`)                                 → `[token removed]`
 *  - URL user info (`ntrip://user:pass@host`)                     → `ntrip://(redacted)@host`
 *  - bare NTRIP-style `user:pass@caster.host`                     → `(redacted)@caster.host`
 *  - password / secret / token / api key / credentials / user name / login values
 *    (`key=value`, `key: value`, JSON `"key": "value"`; prefixed keys such as `ntrip_user`) → `(redacted)`.
 *    `(set)`, `(empty)` and `(redacted)` are kept. Short generic keys (`user`, `pass`, `auth`,
 *    `login`, `pin`) only with `=`, so prose such as "declined by user: …" survives.
 *  - 32+ character hex/base64 values after key-like labels (`…key`, `…token`, `signature`, `nonce` …) → `(redacted)`
 *  - e-mail addresses                                             → `[email removed]`
 *
 * Personal files
 *  - shared-storage paths (`/storage/…`, `/sdcard/…`, `/mnt/…`) and home folders (`/Users/…`,
 *    `/home/…`): the folders are dropped, the file name becomes a [LogPrivacy.file] hash that keeps
 *    the extension → `[storage]/#9b03de.glb`
 *  - app-private paths (`/data/…`): the folders are kept (they are the app's own), a user-chosen
 *    file name is hashed the same way; app-generated names (logs, traces) are kept
 *  - `content://` URIs                                            → `content://[removed]` (+ extension)
 *  - Windows paths (`C:\Users\…`)                                 → `[path removed]` (+ extension)
 *  - `file=` / `fileName=` / `model=` / `asset=` values with an extension → hashed like above,
 *    unless already a hash (`#…`) or an app-generated name
 *
 * User text
 *  - quoted `name="…"` / `title="…"` / `pointName="…"` …, and any `note=` / `notes=` /
 *    `comment=` / `description=` value (also JSON `"name": "…"`) → `(redacted)`.
 *    Unquoted single-word names (`Activity created name=MainActivity`) are kept: user text is
 *    always quoted by the logging rules.
 *
 * Coordinates (context-aware: ordinary measurements with many decimals are kept, so
 * `hAcc=0.0123456`, `vAcc=0.0217891`, `ageS=1.234567` and AR offsets `east=0.1234567` survive)
 *  - NMEA-style `ddmm.mmmm,N` / `dddmm.mmmm,W` fields               → `[coord removed],N`
 *  - numbers labelled lat / lon / lng / latitude / longitude (any prefix, optional `Deg` suffix:
 *    `lat=`, `latDeg:`, `"longitude": `, `embeddedLatitude=`; separated by spaces only for the bare words: `lat 41.2`) → `[coord removed]`
 *  - numbers labelled easting / northing / ecef…                    → `[coord removed]`
 *  - 4+-decimal numbers labelled position / location / coord(s) / coordinate(s) / fix / origin → `[coord removed]`
 *  - unlabelled lat,lon pairs: two numbers in range (|lat| ≤ 90, |lon| ≤ 180, at least one ≥ 1)
 *    separated by `, ` or spaces with 4+ decimals each, or by a bare comma with 6+ decimals each
 *    (bare-comma rows with ≤ 5 decimals are the coordinate-free ar-track.csv) → `[coord removed]`
 */
object ReportRedactor {
    private const val COORD = "[coord removed]"

    private val NMEA = Regex("""\$[A-Z]{5},\S*""")
    private val NMEA_BARE = Regex("""(?<![\w$])(?:GP|GN|GL|GA|GB|BD|GQ)(?:GGA|RMC|GLL|GNS|GST|GSA|GSV|VTG|ZDA),[^\s]*""")

    private val API_KEY = Regex("""AIza[0-9A-Za-z_\-]{20,}""")
    private val AUTH_HEADER = Regex("""(?i)\b((?:proxy-)?authorization)("?\s*[:=]\s*)(?!\(redacted\))(?:(?:basic|bearer|digest|token|negotiate)\s+)?("[^"]*"|\S+)""")
    private val BEARER = Regex("""(?i)\bbearer\s+(?!\(redacted\))[A-Za-z0-9._~+/=\-]{6,}""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}(?:\.[A-Za-z0-9_\-]*)?""")
    private val URL_USERINFO = Regex("""\b([A-Za-z][A-Za-z0-9+.\-]*://)([^\s/@?#]+)@""")
    private val BARE_USERINFO = Regex("""(?<![\w/@.:\-])[\w.+\-]+:[^\s@/:]+@(?=[\w\-]+(?:\.[\w\-]+)+)""")
    private val SECRET_LONG = Regex(
        """(?i)\b([\w\-]*?(?:password|passwd|pwd|secret|token|api[_\-]?key|apikey|credentials?|user_?name|access[_\-]?key|private[_\-]?key)""" +
            """|(?:[\w\-]*[_\-]|ntrip|caster)(?:user|login|pass))\b("?\s*[=:]\s*)(?!\((?:set|empty|redacted)\))("[^"]*"|'[^']*'|\S+)""")
    private val SECRET_SHORT = Regex("""(?i)\b(user|pass|auth|login|pin)(=)(?!\((?:set|empty|redacted)\))("[^"]*"|\S+)""")
    private val LONG_TOKEN = Regex("""(?i)\b([\w\-]*(?:key|token|secret|signature|sig|nonce|bearer|cookie))("?\s*[=:]\s*"?)([A-Za-z0-9+/_\-]{32,}={0,2})""")
    private val EMAIL = Regex("""(?<![\w.%+\-])[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}\b""")

    private val SHARED_PATH = Regex("""(?<![\w.:/])/(?:storage|sdcard|mnt|Users|home)/(?:[^\n"'<>|*?:]{0,255}?\.[A-Za-z0-9]{1,5}\b|[^\s"'<>|*?:]*)""")
    private val APP_PATH = Regex("""(?<![\w.:/])(/data/[^\s"'<>|*?:]*/)([^\s"'<>|*?:/]+)""")
    private val CONTENT_URI = Regex("""\bcontent://[^\s"'<>]+""")
    private val WINDOWS_PATH = Regex("""(?<![\w])[A-Za-z]:\\(?:[^\n"'<>|*?:]{0,255}?\.[A-Za-z0-9]{1,5}\b|[^\s"'<>|*?:]*)""")
    private val FILE_FIELD = Regex("""\b(file|fileName|filename|model|asset|modelFile)=("[^"]*"|[^\s"]+)""")
    /** Names the app itself creates (log files, exit traces, the report): safe to show. */
    private val APP_FILE = Regex(
        """(?:exits/)?(?:app-(?:log|errors)[\w.\-]*|captures(?:-\d)?|ar-track[\w.\-]*|ar-last-session|nmea[\w.\-]*|\d{8}-\d{6}[\w.\-]*|unlit|surreal-ar[\w.\-]*|[a-z\-]+-(?:summary|snapshot|status|stats|health|diagnostics|integrity)|README|manifest|timeline|sessions|exits|captures)\.(?:txt|log|csv|zip|gz|filamat)""")

    private val QUOTED_NAME = Regex("""(?i)\b((?:point|coord|coordinate|model|display|user|file)?name|title)=("[^"]*"|'[^']*')""")
    private val NOTE_FIELD = Regex("""(?i)\b(notes?|comment|description)=("[^"]*"|'[^']*'|\S+)""")
    private val JSON_TEXT = Regex("""(?i)"((?:point|coord|coordinate|model|display|user)?name|title|notes?|comment|description)"(\s*:\s*)"(?:[^"\\]|\\.)*"""")

    private val NMEA_DDMM = Regex("""(?<![\w.])\d{4,5}\.\d{2,},([NSEW])\b""")
    private const val NUM = """-?\d{1,3}(?:\.\d+)?"""
    private val LATLON_LABEL = Regex("""(?i)(\b"?\w*?(?:lat|lon|lng|latitude|longitude)(?:deg|degrees)?"?)(\s*[=:]\s*)\(?\[?($NUM)(?![\w.])""")
    private val LATLON_WORD = Regex("""(?i)\b(lat|lon|lng|latitude|longitude)([ \t]+)($NUM)(?![\w.])""")
    private val PLANAR_LABEL = Regex("""(?i)(\b"?(?:easting|northing|ecef\w*|utm[_\-]?[en])"?)(\s*[=:]\s*)(-?\d+(?:\.\d+)?)(?![\w.])""")
    private val POSITION_LABEL = Regex(
        """(?i)(\b"?(?:position|location|coords?|coordinates?|fix|origin)"?)(\s*[=:]\s*)\(?\[?(-?\d{1,3}\.\d{4,}(?:\s*,\s*-?\d{1,3}\.\d{4,})*)(?![\w.])""")
    private val PAIR = Regex("""(?<![\w.\-])(-?\d{1,2}\.(\d+))([ \t]*,[ \t]*|[ \t]+)(-?\d{1,3}\.(\d+))(?![\w.])""")

    fun scrub(text: String): String {
        var t = text
        // Raw GNSS data
        if (t.indexOf('$') >= 0) t = NMEA.replace(t, "[NMEA removed]")
        t = NMEA_BARE.replace(t, "[NMEA removed]")
        // Secrets
        if (t.contains("AIza")) t = API_KEY.replace(t, "[key removed]")
        t = AUTH_HEADER.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "(redacted)" }
        t = BEARER.replace(t) { m -> m.value.substring(0, 6) + " (redacted)" }
        if (t.contains("eyJ")) t = JWT.replace(t, "[token removed]")
        if (t.contains('@')) {
            t = URL_USERINFO.replace(t) { m -> m.groupValues[1] + "(redacted)@" }
            t = BARE_USERINFO.replace(t, "(redacted)@")
        }
        // Personal files (before e-mail and secrets, so path parts are not mistaken for either)
        if (t.contains("content://")) t = CONTENT_URI.replace(t) { m -> "content://[removed]" + extensionOf(m.value) }
        if (t.contains('/')) {
            t = SHARED_PATH.replace(t) { m -> "[storage]/" + LogPrivacy.file(m.value.trimEnd('/', '.', ',')) }
            t = APP_PATH.replace(t) { m -> m.groupValues[1] + fileName(m.groupValues[2]) }
        }
        if (t.contains(":\\")) t = WINDOWS_PATH.replace(t) { m -> "[path removed]" + extensionOf(m.value) }
        if (t.contains('@')) t = EMAIL.replace(t, "[email removed]")
        t = SECRET_LONG.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "(redacted)" }
        t = SECRET_SHORT.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "(redacted)" }
        t = LONG_TOKEN.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "(redacted)" }
        t = FILE_FIELD.replace(t) { m -> m.groupValues[1] + "=" + fileName(m.groupValues[2]) }
        // User text
        t = QUOTED_NAME.replace(t) { m -> m.groupValues[1] + "=\"(redacted)\"" }
        t = NOTE_FIELD.replace(t) { m -> m.groupValues[1] + "=(redacted)" }
        t = JSON_TEXT.replace(t) { m -> "\"" + m.groupValues[1] + "\"" + m.groupValues[2] + "\"(redacted)\"" }
        // Coordinates
        t = NMEA_DDMM.replace(t) { m -> "$COORD,${m.groupValues[1]}" }
        t = LATLON_LABEL.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + COORD }
        t = LATLON_WORD.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + COORD }
        t = PLANAR_LABEL.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + COORD }
        t = POSITION_LABEL.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + COORD }
        t = PAIR.replace(t) { m -> if (looksLikeLatLon(m)) COORD else m.value }
        return t
    }

    private fun looksLikeLatLon(m: MatchResult): Boolean {
        val lat = m.groupValues[1].toDoubleOrNull() ?: return false
        val lon = m.groupValues[4].toDoubleOrNull() ?: return false
        val bareComma = m.groupValues[3] == ","
        val minDecimals = if (bareComma) 6 else 4
        return m.groupValues[2].length >= minDecimals && m.groupValues[5].length >= minDecimals &&
            abs(lat) <= 90.0 && abs(lon) <= 180.0 && (abs(lat) >= 1.0 || abs(lon) >= 1.0)
    }

    /** A file name for the report: hashes and app-generated names as they are, anything else hashed (extension kept). */
    private fun fileName(raw: String): String {
        val v = raw.trim('"', '\'')
        if (v.startsWith("#") || v == "unknown" || APP_FILE.matches(v)) return raw
        if (!Regex("""\.[A-Za-z0-9]{1,5}$""").containsMatchIn(v)) return raw
        return LogPrivacy.file(v)
    }

    private fun extensionOf(s: String): String {
        val decoded = s.replace("%2F", "/", ignoreCase = true).replace("%2E", ".", ignoreCase = true).trimEnd('/', '.', ',', ')')
        val base = decoded.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', "")
        return if (ext.length in 1..5 && ext.all(Char::isLetterOrDigit) && base.length > ext.length + 1) ".${ext.lowercase()}" else ""
    }
}
