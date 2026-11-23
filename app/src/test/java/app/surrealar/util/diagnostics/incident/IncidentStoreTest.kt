package app.surrealar.util.diagnostics.incident

import android.app.Application
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

// Robolectric for a real org.json (unit tests otherwise get the stubbed android.jar copy).
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncidentStoreTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private val logged = mutableListOf<String>()
    private var now = 1_764_000_000_000L   // 2025-11-24T16:00:00Z
    private var ids = 0

    @Before fun setUp() { dir = tmp.newFolder("diagnostics"); logged.clear() }

    private fun store(
        session: String? = "sess-A", screen: String? = "settingsFragment", max: Int = 20,
        log: (String) -> Unit = { logged += it },
    ) = IncidentStore(
        dir = { dir }, clock = { now }, zone = { ZoneId.of("America/New_York") },
        newId = { "ID%06d".format(++ids) }, sessionId = { session }, screen = { screen },
        appVersion = { "1.2.3 (45)" }, maxEntries = max, io = Dispatchers.Unconfined, log = log,
    )

    private val ctx = IncidentContext(gnssSource = "EXTERNAL_TCP", rtkStatus = "FIX", hAccM = 0.014, fixAgeMs = 200)

    @Test fun `marked incident survives a restart`() = runBlocking {
        val m = store().mark("  map froze after switching source  ", ctx)
        val reread = store().recent(5)   // a new instance on the same folder, like a new process
        assertEquals(1, reread.size)
        assertEquals(m, reread.single())
        with(reread.single()) {
            assertEquals("ID000001", id)
            assertEquals(now, epochMs)
            assertEquals("2025-11-24T16:00:00Z", utcTime)
            assertEquals("2025-11-24T11:00:00-05:00", localTime)
            assertEquals("America/New_York", zoneId)
            assertEquals("sess-A", sessionId)
            assertEquals("1.2.3 (45)", appVersion)
            assertEquals("settingsFragment", screen)
            assertEquals("map froze after switching source", description)
            assertEquals(ctx, context)
        }
        assertTrue(File(dir, IncidentStore.FILE_NAME).exists())
    }

    @Test fun `keeps only the newest N`() = runBlocking {
        val s = store(max = 3)
        repeat(5) { now += 1_000; s.mark("n$it", IncidentContext()) }
        val recent = store(max = 3).recent(10)
        assertEquals(listOf("n4", "n3", "n2"), recent.map { it.description })
        assertEquals(3, File(dir, IncidentStore.FILE_NAME).readLines().count { it.isNotBlank() })
        assertEquals(listOf("n4"), s.recent(1).map { it.description })
        assertEquals("n4", s.latest()?.description)
    }

    @Test fun `corrupt and partial lines are skipped and counted`() = runBlocking {
        val s = store()
        s.mark("first", ctx)
        val f = File(dir, IncidentStore.FILE_NAME)
        f.appendText("this is not json\n{\"id\":\"x\"}\n{\"v\":1,\"id\":\"CUT\",\"epo")   // last line cut mid-write
        s.mark("second", ctx)
        val fresh = store()
        val recent = fresh.recent()
        assertEquals(listOf("second", "first"), recent.map { it.description })
        assertEquals(3, fresh.corruptLinesSkipped)
    }

    @Test fun `empty or missing folder reads as no incidents`() {
        assertTrue(store().recent().isEmpty())
        assertNull(IncidentStore(dir = { null }).latest())
    }

    @Test fun `description is trimmed, capped and blank becomes null`() = runBlocking {
        val long = "x".repeat(IncidentMarker.MAX_DESCRIPTION + 200)
        assertEquals(IncidentMarker.MAX_DESCRIPTION, store().mark(long, ctx).description!!.length)
        assertNull(store().mark("   \n ", ctx).description)
        assertNull(store().mark(null, ctx).description)
    }

    @Test fun `description never reaches the event log`() = runBlocking {
        val logs = tmp.newFolder("logs")
        DiagnosticsLogger.initForTest(logs)
        val secret = "SECRET-NOTE password=hunter2 near the north corner"
        // The default sink: the real DiagnosticsLogger.
        val m = IncidentStore(dir = { dir }, clock = { now }, newId = { "IDLOG001" }, sessionId = { "sess-L" },
            screen = { "openInARFragment" }, io = Dispatchers.Unconfined).mark(secret, ctx)
        assertTrue(DiagnosticsLogger.flush())
        val text = File(logs, "app-log-current.txt").readText()
        val line = text.lines().single { it.contains("INCIDENT_MARKED id=IDLOG001") }
        assertTrue(line, line.contains(" DIAG "))
        assertTrue(line, line.contains("screen=openInARFragment"))
        assertTrue(line, line.contains("session=sess-L"))
        assertTrue(line, line.contains("description=true"))
        assertFalse(text.contains("SECRET-NOTE"))
        assertFalse(text.contains("hunter2"))
        // ...but it is in the incident file.
        assertEquals(secret, m.description)
        assertTrue(File(dir, IncidentStore.FILE_NAME).readText().contains("hunter2"))
    }

    @Test fun `log line carries id, screen and session but no note`() = runBlocking {
        store(session = "s1", screen = null).mark("private words", ctx)
        assertEquals(listOf("INCIDENT_MARKED id=ID000001 screen=unknown session=s1 description=true"), logged)
    }

    @Test fun `incidents from different sessions keep their session ids`() = runBlocking {
        store(session = "sess-1").mark(null, ctx)
        now += 60_000
        store(session = "sess-2").mark(null, ctx)
        now += 60_000
        store(session = null).mark(null, ctx)
        val recent = store(session = "sess-3").recent()
        assertEquals(listOf(null, "sess-2", "sess-1"), recent.map { it.sessionId })
        assertTrue(recent.first().render().contains("session: unknown"))
        assertTrue(recent.last().render().contains("session: sess-1"))
    }

    @Test fun `render holds no coordinates even from weird inputs`() = runBlocking {
        val weird = object : IncidentContextInputs {
            override fun gnssSource() = "40.7128123,-74.0060123"
            override suspend fun receiverProfile() = "Reach RS2+ @ 40.71281 N 74.00601 W"
            override fun receiverLink() = "CONNECTED\n\$GNGGA,120000.00,4042.7687,N,07400.3607,W,4,12,0.6,10.0,M"
            override fun corrections() = "receiving station=1234 age=1.2s base=41.2410321,-75.8812011"
            override fun fixQuality() = FixQuality("FIX", 120, 0.012, 0.021, 1.2)
            override fun arState() = ArState(true, "TRACKING", "-77.1234567", 2, 1)
            override fun freeStorageBytes() = 5L * 1024 * 1024 * 1024
            override fun permissions() = PermissionState(true, true, false)
        }
        val context = IncidentContextCollector.collect(weird)
        val m = store().mark("I was at 40.7128123, -74.0060123 when it broke", context)
        for (text in listOf(m.render(), store().latest()!!.render(), m.render(includeDescription = true))) {
            for (bad in listOf("40.7128", "74.0060", "40.71281", "41.2410", "75.8812", "77.1234", "4042.7687", "07400.3607", "GNGGA")) {
                assertFalse("'$bad' leaked:\n$text", text.contains(bad))
            }
        }
        val plain = m.render()
        assertTrue(plain, plain.contains("description: provided ("))
        assertFalse(plain, plain.contains("when it broke"))
        assertTrue(m.render(includeDescription = true).contains("when it broke"))
        assertTrue(plain, plain.contains("hAccM: 0.012"))
        assertTrue(plain, plain.contains("rtkStatus: FIX"))
        assertTrue(plain, plain.contains("arModelsInScene: 2"))
    }

    @Test fun `render is stable`() = runBlocking {
        val m = store().mark(null, IncidentContext(gnssSource = "INTERNAL", fixAgeMs = 1500, hAccM = 3.25,
            cameraPermission = true, freeStorageBytes = 3L * 1024 * 1024 * 1024))
        val expected = """
            Incident ID000001
              utcTime: 2025-11-24T16:00:00Z
              localTime: 2025-11-24T11:00:00-05:00 America/New_York
              session: sess-A
              appVersion: 1.2.3 (45)
              screen: settingsFragment
              description: none
              gnssSource: INTERNAL
              receiverProfile: unknown
              receiverLink: unknown
              corrections: unknown
              rtkStatus: unknown
              fixAgeMs: 1500
              hAccM: 3.250
              vAccM: unknown
              correctionAgeS: unknown
              arSessionActive: unknown
              arCameraTracking: unknown
              arEarthTracking: unknown
              arModelsInScene: unknown
              arModelsLoading: unknown
              freeStorageMb: 3072
              cameraPermission: true
              fineLocationPermission: unknown
              notificationPermission: unknown
        """.trimIndent() + "\n"
        assertEquals(expected, m.render().replace("\r\n", "\n"))
    }
}
