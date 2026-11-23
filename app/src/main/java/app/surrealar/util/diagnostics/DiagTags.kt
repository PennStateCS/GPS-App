package app.surrealar.util.diagnostics

/** The only tags used in the diagnostic event log. Pick the nearest one; don't invent new ones. */
object DiagTags {
    const val APP = "APP"
    const val LIFECYCLE = "LIFECYCLE"
    const val NAV = "NAV"
    const val SETTINGS = "SETTINGS"
    const val SYSTEM = "SYSTEM"
    const val GNSS = "GNSS"
    const val RECEIVER = "RECEIVER"
    const val CORRECTIONS = "CORRECTIONS"
    const val NMEA = "NMEA"
    const val CAPTURE = "CAPTURE"
    const val COORD = "COORD"
    const val DATA = "DATA"
    const val MODEL = "MODEL"
    const val AR = "AR"
    const val MAP = "MAP"
    const val STAKEOUT = "STAKEOUT"
    const val EXPORT = "EXPORT"
    const val ERROR = "ERROR"
    /** Diagnostics tooling itself (e.g. a tester marking a problem with "Mark Problem"). */
    const val DIAG = "DIAG"

    val ALL: Set<String> = setOf(APP, LIFECYCLE, NAV, SETTINGS, SYSTEM, GNSS, RECEIVER, CORRECTIONS, NMEA,
        CAPTURE, COORD, DATA, MODEL, AR, MAP, STAKEOUT, EXPORT, ERROR, DIAG)
}