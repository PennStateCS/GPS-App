package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One positive and one negative case per [ReportRedactor] rule. */
class ReportRedactorTest {

    private fun scrub(s: String) = ReportRedactor.scrub(s)
    private fun kept(s: String) = assertEquals(s, scrub(s))

    // ── Raw GNSS data ──

    @Test fun `nmea sentences are removed, with or without the dollar`() {
        assertEquals("raw [NMEA removed]", scrub("raw \$GNGGA,142307.40,4120.8591234,N,07601.3554321,W,4,18,0.7,345.6,M,-33.0,M,1.2,0000*5C"))
        assertEquals("raw [NMEA removed]", scrub("raw GNRMC,142307.40,A,4120.8591,N,07601.3554,W,0.0,0.0,211126,,,R*7A"))
        kept("NMEA sentence counts GGA=3600 RMC=3600 GST=3600")
    }

    // ── Secrets ──

    @Test fun `api keys`() {
        assertEquals("map key [key removed]", scrub("map key AIzaSyD3adB33fFAKEFAKEFAKEFAKE12345678"))
        kept("AIza is the Google key prefix")
    }

    @Test fun `authorization headers, bearer tokens and jwt`() {
        assertEquals("Authorization: (redacted)", scrub("Authorization: Basic YWxpY2U6UzNjcjN0"))
        assertEquals("authorization=(redacted) next", scrub("authorization=abc123 next"))
        assertEquals("sent Bearer (redacted) ok", scrub("sent Bearer abcDEF123456.xyz ok"))
        assertEquals("jwt [token removed] end", scrub("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl end"))
        kept("Corrections authorization not required")
        kept("bearer of bad news")
    }

    @Test fun `url user info`() {
        assertEquals("ntrip://(redacted)@caster.example.com:2101/MOUNT", scrub("ntrip://alice:S3cr3t@caster.example.com:2101/MOUNT"))
        assertEquals("http://(redacted)@10.0.0.5/x", scrub("http://admin@10.0.0.5/x"))
        kept("http://192.168.42.1:80/api/status")
    }

    @Test fun `bare ntrip user-pass at caster`() {
        assertEquals("caster (redacted)@rtk.example.org:2101", scrub("caster alice:S3cr3t@rtk.example.org:2101"))
        kept("2026-11-21T14:03:07.412-05:00 W RECEIVER [main] Link down")
    }

    @Test fun `password, token, user name and credential values`() {
        assertEquals("password=(redacted) next", scrub("password=Hunter2! next"))
        assertEquals("ntrip_password: (redacted)", scrub("ntrip_password: Hunter2!"))
        assertEquals("\"password\": (redacted), \"x\": 1", scrub("\"password\": \"Hunter2!\", \"x\": 1"))
        assertEquals("ntrip_user=(redacted) user=(redacted) mount=PAWB", scrub("ntrip_user=surveyor user=surveyor mount=PAWB"))
        assertEquals("username: (redacted)", scrub("username: alice"))
        assertEquals("credentials=(redacted)", scrub("credentials=alice:S3cr3t"))
        kept("ntrip_password          : (set)  [changed]")
        kept("ntrip_user : (empty)")
        kept("ARCore install declined by user: AR unavailable")
        kept("tokens shown as (set)")
    }

    @Test fun `long tokens after key-like labels`() {
        assertEquals("deviceKey=(redacted)", scrub("deviceKey=0123456789abcdef0123456789abcdef"))
        assertEquals("signature: (redacted)", scrub("signature: QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIz"))
        kept("signingSha256: AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89")
        kept("signature=short")
    }

    @Test fun `email addresses`() {
        assertEquals("send to [email removed] now", scrub("send to bob@example.com now"))
        kept("at java.lang.Object@1b6d3586")
    }

    // ── Personal files ──

    @Test fun `shared storage paths keep only a hashed name and the extension`() {
        val out = scrub("open failed /storage/emulated/0/Download/JohnDoe-house.glb: ENOENT")
        assertEquals("open failed [storage]/${LogPrivacy.file("JohnDoe-house.glb")}: ENOENT", out)
        assertFalse(out.contains("JohnDoe"))
        assertTrue(scrub("/sdcard/Documents/John Doe house.gltf").endsWith(".gltf"))
        assertFalse(scrub("/sdcard/Documents/John Doe house.gltf").contains("Doe"))
        kept("ntrip://(redacted)@caster.example.com:2101/MOUNT")
    }

    @Test fun `app private paths keep the folders and app file names`() {
        assertEquals("/data/user/0/app.surrealar/files/models/${LogPrivacy.file("JohnDoe-house.glb")}",
            scrub("/data/user/0/app.surrealar/files/models/JohnDoe-house.glb"))
        kept("/data/user/0/app.surrealar/files/diagnostics/logs/app-log-current.txt")
    }

    @Test fun `content uris and windows paths`() {
        assertEquals("uri content://[removed].glb", scrub("uri content://com.android.externalstorage.documents/document/primary%3ADownload%2FJohnDoe-house.glb"))
        assertEquals("read [path removed].txt failed", scrub("read C:\\Users\\jdoe\\secret.txt failed"))
        kept("ratio 3:1 and C: drive")
    }

    @Test fun `file fields are hashed unless already a hash or app-generated`() {
        assertEquals("Model load failed file=${LogPrivacy.file("JohnDoe-house.glb")}", scrub("Model load failed file=JohnDoe-house.glb"))
        assertEquals("id=1 file=${LogPrivacy.file("My House.glb")} type=GLB", scrub("id=1 file=\"My House.glb\" type=GLB"))
        kept("Model preload started key=c1 file=#9b03de.glb")
        kept("NMEA log read failed file=nmea_20261121.txt")
        kept("Report file copy failed file=app-log-1.txt")
        kept("id=1 file=ok(123B)")
    }

    // ── User text ──

    @Test fun `quoted names, notes and json text fields`() {
        assertEquals("id=7 name=\"(redacted)\" modelId=3", scrub("id=7 name=\"Smith Residence Backyard\" modelId=3"))
        assertEquals("note=(redacted)", scrub("note=\"gate code 1234\""))
        assertEquals("{\"name\": \"(redacted)\", \"note\": \"(redacted)\"}", scrub("{\"name\": \"Smith\", \"note\": \"by the oak\"}"))
        kept("Activity created name=MainActivity restored=false")
        kept("device=\"Samsung SM-X940\"")
    }

    // ── Coordinates ──

    @Test fun `labelled latitude and longitude`() {
        assertEquals("lat=[coord removed] lon=[coord removed]", scrub("lat=41.2468135 lon=-75.8811223"))
        assertEquals("{\"latitude\": [coord removed], \"longitude\": [coord removed]}", scrub("{\"latitude\": 41.2468135, \"longitude\": -75.8811223}"))
        assertEquals("embeddedLatitude=[coord removed] latDeg: [coord removed]", scrub("embeddedLatitude=41.2 latDeg: 41.24"))
        assertEquals("lat [coord removed]", scrub("lat 41.2468"))
        kept("Model reprojection precision limited latResM=12.3 lonResM=9.8")
    }

    @Test fun `planar and position labels`() {
        assertEquals("easting=[coord removed] northing=[coord removed]", scrub("easting=432123.456 northing=4567890.12"))
        assertEquals("Current fix: [coord removed]", scrub("Current fix: 41.3476987, -76.0225905"))
        kept("east=0.1234567 north=-0.7654321 up=0.0123456")
        kept("origin=BOTTOM_CENTER position=unknown")
    }

    @Test fun `unlabelled lat lon pairs`() {
        assertEquals("near [coord removed] today", scrub("near 41.24681, -75.88112 today"))
        assertEquals("last: [coord removed]", scrub("last: 41.2468135,-75.8811223"))
        assertEquals("pt [coord removed]", scrub("pt 41.2468 -75.8811"))
        // ar-track.csv rows: bare commas, at most 5 decimals.
        kept("1200,3,TRACKING,0.12345,1.54321,-0.25000,0.01,0.02")
        // Out of range / too few decimals / both under 1.
        kept("values 123.45678, 200.12345")
        kept("version 1.4, 2.0")
        kept("hAcc 0.0123 vAcc 0.0217 pair 0.0123, 0.0217")
    }

    @Test fun `nmea style ddmm fields`() {
        assertEquals("[coord removed],N [coord removed],W", scrub("4120.8591234,N 07601.3554321,W"))
        kept("4120.8591234 m")
    }

    @Test fun `accuracy and measurement values survive`() {
        kept("hAcc=0.0123456 vAcc=0.0217891 ageS=1.234567 east=0.1234567 ns=0.001234 ratio=0.12345")
        kept("Heartbeat source=EXTERNAL_TCP rtk=FIX sats=18/27 hdop=0.7 hAcc=<2cm corrAge=1.2s fixes/s=5.0")
        kept("SESSION_START session=aaa111 install=i-1 version=1.4 code=14")
    }
}
