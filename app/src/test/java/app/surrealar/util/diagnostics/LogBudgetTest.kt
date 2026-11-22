package app.surrealar.util.diagnostics

import app.surrealar.gnss.replay.NmeaLineSource
import app.surrealar.gnss.replay.ReplayAdapter
import app.surrealar.util.DiagnosticsLogger
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCorrections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Log budget: one simulated hour of the Emlid NMEA fixtures at 5 Hz through the GNSS loggers, with
 * lifecycle/navigation/settings/receiver events, must grow the event log by less than 2 MB and stay
 * readable: under [MAX_LINES] lines in total, of which under [MAX_GNSS_LINES] from the GNSS feed
 * (RTK transitions, heartbeat, clock offset). The simulated app events alone are ~136 lines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogBudgetTest {

    @get:Rule val tmp = TemporaryFolder()

    private class ResourceSource(private val res: String) : NmeaLineSource {
        override val name = "budget $res"
        override fun lines(): Flow<String> =
            LogBudgetTest::class.java.getResourceAsStream(res)!!.bufferedReader().readLines().asFlow()
                .onEach { kotlinx.coroutines.yield() }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.fixesFrom(res: String): List<Fix> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val adapter = ReplayAdapter(backgroundScope, dispatcher = dispatcher)
        val out = mutableListOf<Fix>()
        backgroundScope.launch(dispatcher) { adapter.fixes.collect { out.add(it) } }
        adapter.load(ResourceSource(res)); adapter.start()
        testScheduler.advanceUntilIdle()
        return out
    }

    @After fun restoreClock() { DiagnosticsLogger.nowMs = { System.nanoTime() / 1_000_000L } }

    @Test fun `one hour of fixes and app events stays under 2 MB`() = runTest {
        val fixes = listOf("/nmea/reach_nmea_capture.txt", "/nmea/reach_nmea_rs2_capture.txt", "/nmea/reach_nmea_withimu.txt")
            .flatMap { fixesFrom(it) }
        assertTrue("fixture fixes: ${fixes.size}", fixes.size > 300)

        val dir = tmp.newFolder("logs")
        DiagnosticsLogger.initForTest(dir)
        DiagnosticsLogger.resetThrottles()
        ClockOffset.reset()
        DiagnosticsLogger.flush()
        val before = File(dir, "app-log-current.txt").length()

        var now = 0L
        DiagnosticsLogger.nowMs = { now }
        val feed = GnssFeedTracker { level, msg -> GnssFeedLogger.log(level, DiagTags.GNSS, msg) }
        val link = ReceiverLinkTracker { level, tag, msg -> GnssFeedLogger.log(level, tag, msg) }
        feed.onSource("EXTERNAL_TCP", 0); link.onConnectionState("CONNECTED", 0)
        val hourMs = 3_600_000L
        var i = 0
        var nextHeartbeat = 60_000L
        while (now < hourMs) {
            val f = fixes[i % fixes.size].let { it.copy(timeUtc = Instant.ofEpochMilli(1_700_000_000_000L + now)) }
            feed.onFix(f, now); feed.onNmea(now); link.onData(now)
            // Delivery jitter: 400/600 ms alternating, so an offset rounded to whole seconds would flip every fix.
            ClockOffset.onFix(f, 1_700_000_000_000L + now + if (i % 2 == 0) 400 else 600)
            DiagnosticsLogger.logOnChange(DiagTags.GNSS, "toolbar.link", if ((now / 600_000L) % 2 == 0L) "c" else "s") { "Toolbar link change" }
            if (now >= nextHeartbeat) { feed.heartbeatIfDue(now)?.let { DiagnosticsLogger.i(DiagTags.GNSS, it) }; nextHeartbeat += 60_000L }
            if (now % 60_000L == 0L) { NavLogger.line("nav_screen_${(now / 60_000L) % 4}", listOf("coordinateId")).let { DiagnosticsLogger.i(DiagTags.NAV, it) } }
            if (now % 300_000L == 0L) {
                DiagnosticsLogger.i(DiagTags.LIFECYCLE, "App background"); DiagnosticsLogger.i(DiagTags.LIFECYCLE, "App foreground")
                DiagnosticsLogger.i(DiagTags.SETTINGS, "Setting keep_screen_awake: true → false")
                link.onCorrections(ReceiverCorrections(isReceiving = (now / 300_000L) % 2 == 0L, stationId = "PAWB"))
            }
            if (now % 600_000L == 0L && now > 0) {
                link.onConnectionState("RECONNECTING", now); link.onConnectionState("CONNECTED", now + 1)
                DiagnosticsLogger.w(DiagTags.RECEIVER, "No NMEA data for 10000ms — triggering reconnect")
            }
            now += 200; i++
        }
        DiagnosticsLogger.flush()
        val grown = File(dir, "app-log-current.txt").length() - before + dir.listFiles()!!.filter { it.name.startsWith("app-log-") && it.name != "app-log-current.txt" }.sumOf { it.length() }
        val parsed = dir.listFiles()!!.filter { it.name.startsWith("app-log-") }
            .flatMap { it.readLines() }.mapNotNull { LogLineFormat.parse(it) }
        val gnss = parsed.filter { it.tag == DiagTags.GNSS && it.message != "Toolbar link change" }
        println("Log budget: ${fixes.size} fixture fixes, $i fixes fed, event log grew ${grown / 1024} KB and " +
            "${parsed.size} lines (${gnss.size} GNSS feed) in one simulated hour")
        gnss.groupingBy { it.message.substringBefore(' ') + " " + it.message.split(' ').getOrElse(1) { "" } }.eachCount()
            .forEach { (k, n) -> println("  GNSS $k: $n") }
        assertTrue("event log grew $grown bytes in an hour (budget 2 MB)", grown < 2L * 1024 * 1024)
        assertTrue("${parsed.size} lines in an hour (budget $MAX_LINES)", parsed.size < MAX_LINES)
        assertTrue("${gnss.size} GNSS feed lines in an hour (budget $MAX_GNSS_LINES)", gnss.size < MAX_GNSS_LINES)
    }

    private companion object {
        const val MAX_LINES = 250
        const val MAX_GNSS_LINES = 100
    }
}