package app.surrealar.util.diagnostics.report

import java.io.File

/**
 * Fixed report inputs for tests. Log files are written into [dir].
 *
 * Failure injection (optional, off by default): [failing] maps a section key ([Keys]) to the exception
 * that source throws; sections in [hanging] suspend until cancelled; [extraLogFiles] are listed after
 * the event log (e.g. a missing file, to test an unreadable log). [incidents] (newest first) and
 * [info] feed incidents.txt and the summary header; [health] overrides device-health inputs.
 */
open class FakeReportSources(
    private val dir: File,
    override val reportId: String = "7K3M-QX2D",
    override val nowMs: Long = java.time.OffsetDateTime.parse("2026-11-21T15:00:00-05:00").toInstant().toEpochMilli(),
    val eventLog: String = DEFAULT_EVENT_LOG,
    val errorLog: String = DEFAULT_ERROR_LOG,
    val failing: Map<String, Throwable> = emptyMap(),
    val hanging: Set<String> = emptySet(),
    val extraLogFiles: List<File> = emptyList(),
    val incidentList: List<app.surrealar.util.diagnostics.incident.IncidentMarker> = emptyList(),
    val info: ReportInfo = FAKE_INFO,
    val health: DeviceHealth = FAKE_HEALTH,
    val integrity: String = "ok",
) : ReportSources {

    /** Section keys for [failing] / [hanging] ([hanging] only applies to suspending sources). */
    object Keys {
        const val SUMMARY = "summary"; const val SETTINGS = "settings"; const val PERMISSIONS = "permissions"
        const val APP_STATE = "appState"; const val COORDINATES = "coordinates"; const val MODELS = "models"
        const val AR_SESSION = "arSession"; const val MAP = "map"; const val NMEA = "nmea"; const val GNSS = "gnss"
        const val DEVICE_HEALTH = "deviceHealth"; const val DATA_HEALTH = "dataHealth"; const val LIVE_STATE = "liveState"
    }

    private suspend fun <T> section(key: String, value: () -> T): T {
        failing[key]?.let { throw it }
        if (key in hanging) kotlinx.coroutines.awaitCancellation()
        return value()
    }

    private fun <T> blocking(key: String, value: () -> T): T {
        failing[key]?.let { throw it }
        return value()
    }

    override suspend fun summary() = section(Keys.SUMMARY) { "=== Diagnostic Summary ===\nReport ID        : $reportId\n" }
    override suspend fun settingsSnapshot() = section(Keys.SETTINGS) { "=== Current Settings Snapshot ===\n" }
    override suspend fun permissions() = section(Keys.PERMISSIONS) { "=== Permissions Status ===\n" }
    override suspend fun appState() = section(Keys.APP_STATE) { "=== App State Summary ===\n" }
    override suspend fun coordinates() = section(Keys.COORDINATES) { "=== Coordinates ===\nTotal coordinates        : 3\n" }
    override suspend fun models() = section(Keys.MODELS) { "=== Model Integrity ===\n" }
    override fun arSession() = blocking(Keys.AR_SESSION) { "No AR session has been recorded yet.\n" }
    override suspend fun mapDiagnostics() = section(Keys.MAP) { "=== Map ===\n" }
    override suspend fun nmeaStream() = section(Keys.NMEA) { "=== NMEA Stream Diagnostics ===\n" }

    override fun liveState() = blocking(Keys.LIVE_STATE) {
        LiveState("EXTERNAL_TCP", "FIX", "CONNECTED", 1.2, "receiving station=PAWB age=1.2s", 12L * 1024 * 1024 * 1024)
    }

    override fun exitTraceFiles(): List<File> =
        listOf(File(dir, "20261121-133000-ANR.txt").apply { writeText("----- pid 123 at 2026-11-21 13:30:00 -----\n\"main\" prio=5 tid=1 Blocked\n") })

    override fun deviceHealth() = blocking(Keys.DEVICE_HEALTH) { health }
    override fun incidents() = incidentList
    override fun reportInfo() = info
    override fun capturesLog(): File? = File(dir, "captures.log").apply {
        writeText((1..55).joinToString("\n") { "2026-11-21T14:%02d:00.000-05:00 Capture saved name=\"P$it\" mode=EXTERNAL samples=150 duration=60s rtk=FIX hAcc=<2cm vAcc=<5cm rejected=0/150".format(it % 60) } + "\n")
    }
    override suspend fun gnssStats() = section(Keys.GNSS) { FAKE_GNSS }
    override fun dataHealth() = blocking(Keys.DATA_HEALTH) { DataHealth(12, mapOf("coordinates" to 42L, "models" to 3L), integrity) }

    override fun logFiles() = listOf(File(dir, "app-log-current.txt").apply { writeText(eventLog) }) + extraLogFiles
    override fun errorFiles() = listOf(File(dir, "app-errors-current.txt").apply { writeText(errorLog) })

    companion object {
        val FAKE_INFO = ReportInfo(appVersion = "1.4 (14) debug", gitCommit = "abc1234", device = "samsung SM-X940",
            android = "15 (API 35)", sessionId = "bbb222", missingModelLinks = 0)
        val FAKE_HEALTH = DeviceHealth(
            batteryPercent = 57, charging = false, powerSave = false, thermal = "LIGHT",
            storageFreeBytes = 12L * 1024 * 1024 * 1024, storageTotalBytes = 128L * 1024 * 1024 * 1024, appDataBytes = 350L * 1024 * 1024,
            ramAvailBytes = 3L * 1024 * 1024 * 1024, ramTotalBytes = 8L * 1024 * 1024 * 1024, lowMemory = false,
            network = "Wi-Fi validated=false", gpsProvider = true, networkProvider = false,
            permissions = linkedMapOf("Fine location" to true, "Camera" to false),
            batteryOptimizationIgnored = false, mockLocationApp = "not this app",
        )
        val FAKE_GNSS = GnssStatsInput(
            stats = app.surrealar.util.diagnostics.GnssFeedTracker.Stats(
                timeInStatusMs = mapOf(io.github.pennstatecs.geokit.gnss.model.RtkStatus.FIX to 3_000_000L,
                    io.github.pennstatecs.geokit.gnss.model.RtkStatus.FLOAT to 600_000L),
                rtkDrops = 3, longestCorrectionAgeS = 31.0, sourceSwitches = 1, fixes = 18_000L),
            reconnects = 2, nmeaLines = 120_000L, nmeaParseErrors = 4L, sentenceCounts = "GGA=3600 RMC=3600 GST=3600",
        )
        val DEFAULT_EVENT_LOG = """
            2026-11-21T13:00:00.000-05:00 I APP [main] SESSION_START session=aaa111 install=i-1 version=1.4 code=14 build=debug commit=abc device="Samsung SM-X940" android=15 sdk=35 locale=en-US tz=America/New_York lastSessionClean=unknown(first run) previous=none
            2026-11-21T13:05:00.000-05:00 I LIFECYCLE [main] App foreground
            2026-11-21T13:05:01.000-05:00 I NAV [main] Screen nav_home
            2026-11-21T13:10:00.000-05:00 W RECEIVER [diag] Link down: reconnecting (attempt 1), was up 300s
            2026-11-21T13:10:05.000-05:00 I RECEIVER [diag] Link up after 2 attempts
            2026-11-21T13:20:00.000-05:00 I GNSS [DefaultDispatcher-worker-1] Heartbeat source=EXTERNAL_TCP rtk=FIX sats=18/27 hdop=0.7 hAcc=<2cm corrAge=1.2s fixes/s=5.0 lastNmea=0.2s
            2026-11-21T13:30:00.000-05:00 E ERROR [main] Uncaught exception (fatal) thread=main screen=nav_render_map session=aaa111 type=java.lang.IllegalStateException: boom
            java.lang.IllegalStateException: boom
            	at app.surrealar.Foo.bar(Foo.kt:1)
            2026-11-21T13:31:00.000-05:00 W APP [main] SESSION_START session=bbb222 install=i-1 version=1.4 code=14 build=debug commit=abc device="Samsung SM-X940" android=15 sdk=35 locale=en-US tz=America/New_York lastSessionClean=false previous=aaa111
            2026-11-21T13:31:01.000-05:00 W APP [diag-exits] Previous process exit: reason=CRASH status=0 importance=100 pss=1000kB rss=2000kB at=2026-11-21T13:30:00.100-05:00 description=crash
            2026-11-21T14:00:00.000-05:00 W CORRECTIONS [diag] Corrections stream lost
            2026-11-21T14:00:01.000-05:00 W GNSS [diag] Correction age over 10 s (12.0 s)
            2026-11-21T14:00:30.000-05:00 W GNSS [diag] Correction age over 10 s (14.0 s)
            2026-11-21T14:10:00.000-05:00 I CAPTURE [main] Capture saved name="CP-1" mode=EXTERNAL samples=150 duration=60s rtk=FIX hAcc=<2cm vAcc=<5cm rejected=0/150
            2026-11-21T14:20:00.000-05:00 I SETTINGS [diag] Setting keep_screen_awake: true → false
        """.trimIndent() + "\n"
        val DEFAULT_ERROR_LOG = """
            2026-11-21T13:30:00.000-05:00 E ERROR [main] Uncaught exception (fatal) thread=main screen=nav_render_map session=aaa111 type=java.lang.IllegalStateException: boom
            java.lang.IllegalStateException: boom
            	at app.surrealar.Foo.bar(Foo.kt:1)
        """.trimIndent() + "\n"
    }
}