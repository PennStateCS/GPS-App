package io.github.pennstatecs.geokit.models.georef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Golden tests recorded from the app's reprojector and detector before they moved
 * here (app test GlbGeoreferenceGoldenTest, commit "Golden tests for GLB georeference ..."). Same
 * inputs; reprojected output (float32 vertices, JSON content, chunk padding) must not change. The exact
 * bytes are pinned by the app copy under Robolectric (Android's org.json); see the digest test below.
 */
class GlbGeoreferenceGoldenTest {

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("golden", ".glb").apply { writeBytes(bytes); deleteOnExit() }

    private fun cubeFixture(): ByteArray =
        javaClass.classLoader!!.getResource("models/surreal-test-cube-1m.glb")!!.readBytes()

    /** [output] = SHA-256 of the exact bytes on Android (asserted by the app copy), null = not reprojected. */
    private class Expect(val input: String, val output: String?)

    private val expected = mapOf(
        "compass" to Expect("bf146a5803ef1a748d1d7b202bf043c8770dc6424aff8e61503ce00aa8b3b47e", "01c9d18180df75bce44fc61405775223961f537539eef8ea1d33e92b5e77e055"),
        "triangle" to Expect("37e16b3e865f4d5c98ee5fb0eceeccdfb499bea3e2dfc1989e2ac8a669de0d7f", "97d75590a621041a6c351acef86b77b5059d43f64f02b13afb00397be73083ed"),
        "interleaved" to Expect("4a5ae8d6dd6ad42e49ec5ab4e2e2b229e94f8e5dc2afcdbf1540280cf07b719f", "e4645c7bdf3922e7b94893182c1fe60efba4ba7e6a21c3c901ac18458033f77e"),
        "stride0-asset" to Expect("5b554086901b5ef915d2e61171261f0880c24cc9d79f958c90b68124a1b0238e", "8b1713905aecd0b17958bedeb387bea1cb218fa925b4fc82c687680aa987814e"),
        "overrun" to Expect("4da461d51ca6a1544fd1b0100f0d570e126beb3f6dd2066aa3976207fd4590cf", null),
        "local" to Expect("6153e4df0016f1d82c78096699c12807c58833a1e3d3c710bec7070c95341b19", null),
        "cube" to Expect("c0b5a0f523e3891f6e0c74ff1ecad339a1bc9992d49a985afdddf9f4b5d0a3a9", null),
    )

    private val origins = mapOf(
        "compass" to Triple(41.0, -76.0, 300.0),
        "triangle" to Triple(41.0, -76.0, 300.0),
        "interleaved" to Triple(40.2, -77.5, 121.0),
        "stride0-asset" to Triple(60.0, 10.0002, 0.0),
    )

    private fun inputs(): Map<String, ByteArray> = GoldenGlbs.all() + ("cube" to cubeFixture())

    /**
     * [GlbDigest.canonical] of each reprojected output, recorded by the app copy from outputs whose exact
     * bytes it pins (under Robolectric, i.e. Android's org.json).
     */
    private val canonical = mapOf(
        "compass" to "7d9c681aa411a07ee0dc5e01814ea067e2877c10e1c7d41edf2f05f2a9b94624",
        "triangle" to "fbb8adf6002ab28c6137b985691c5440ad1c83bc61ce4bf9622b5b0cedfd269a",
        "interleaved" to "f98ce215eeb7d4fcd4dc6fb2dbae055780ee152c542fbd22496bbcbec0a5484f",
        "stride0-asset" to "7d0cb0cba2957a8b8757041242a9c31adc29e26b010e9887529491ffe910041e",
    )

    /**
     * android-json (this module's test org.json) keeps JSONObject keys in a HashMap, so the JSON text
     * comes out in a different key order than on Android (LinkedHashMap, insertion order). The exact
     * output bytes ([Expect.output]) are therefore asserted by the app copy of this test under
     * Robolectric; here every chunk header, every non-JSON byte (the float32 vertices) and the JSON
     * content and padded length are checked through [GlbDigest.canonical].
     */
    @Test fun `reprojected output matches the recorded golden digests`() {
        for ((name, bytes) in inputs()) {
            val exp = expected.getValue(name)
            assertEquals("$name input", exp.input, sha256(bytes))
            val result = GlbGeoreferenceReprojector.reproject(bytes.copyOf())
            if (exp.output == null) {
                assertTrue("$name must not reproject: $result", result !is GlbGeoreferenceReprojector.Result.Reprojected)
            } else {
                assertTrue("$name must reproject: $result", result is GlbGeoreferenceReprojector.Result.Reprojected)
                val out = (result as GlbGeoreferenceReprojector.Result.Reprojected).bytes
                assertEquals("$name canonical output", canonical.getValue(name), GlbDigest.canonical(out))
            }
        }
    }

    @Test fun `non-reprojected results say why`() {
        val all = GoldenGlbs.all()
        val overrun = GlbGeoreferenceReprojector.reproject(all.getValue("overrun"))
        assertEquals(GlbGeoreferenceReprojector.Result.Rejected.Reason.OUT_OF_BOUNDS,
            (overrun as GlbGeoreferenceReprojector.Result.Rejected).reason)
        val local = GlbGeoreferenceReprojector.reproject(all.getValue("local"))
        assertEquals(GlbGeoreferenceReprojector.Result.NotGeoreferenced(
            GlbGeoreferenceReprojector.Result.NotGeoreferenced.Reason.NULL_ISLAND), local)
        assertEquals(null, local.precision)
    }

    @Test fun `reprojected origins match the recorded values exactly`() {
        val all = GoldenGlbs.all()
        for ((name, o) in origins) {
            val r = GlbGeoreferenceReprojector.reproject(all.getValue(name)) as GlbGeoreferenceReprojector.Result.Reprojected
            assertEquals("$name lat", o.first, r.origin.latitudeDeg, 0.0)
            assertEquals("$name lon", o.second, r.origin.longitudeDeg, 0.0)
            assertEquals("$name h", o.third, r.origin.heightM, 0.0)
            assertEquals(GeoreferenceConfidence.HIGH, r.confidence)
            assertEquals("GLB_POSITION_WGS_LIKE", r.method)
        }
    }

    @Test fun `detector results match the recorded values exactly`() {
        val all = GoldenGlbs.all()
        for ((name, o) in origins + ("overrun" to Triple(41.0, -76.0, 300.0))) {
            val r = GlbGeoreferenceDetector.detect(temp(all.getValue(name))) as GlbGeoreferenceDetector.Result.Found
            assertEquals("$name lat", o.first, r.origin.latitudeDeg, 0.0)
            assertEquals("$name lon", o.second, r.origin.longitudeDeg, 0.0)
            assertEquals("$name h", o.third, r.origin.heightM, 0.0)
            assertEquals(GeoreferenceConfidence.HIGH, r.confidence)
            assertEquals("GLB_POSITION_WGS_LIKE", r.method)
        }
        assertTrue(GlbGeoreferenceDetector.detect(temp(all.getValue("local"))) is GlbGeoreferenceDetector.Result.NotGeoreferenced)
        assertTrue(GlbGeoreferenceDetector.detect(temp(cubeFixture())) is GlbGeoreferenceDetector.Result.NotGeoreferenced)
    }
}

/**
 * A key-order-independent digest of a GLB: every chunk's type and length plus the bytes of every
 * non-JSON chunk, then the JSON chunk with object keys sorted. Used where the org.json on the test
 * classpath orders keys differently from Android's (android-json keeps a HashMap): everything else in
 * the output, including the JSON chunk's padded length, must still match exactly.
 */
internal object GlbDigest {
    fun canonical(glb: ByteArray): String {
        val b = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(glb, 0, 12)
        var p = 12
        while (p + 8 <= glb.size) {
            val len = b.getInt(p); val type = b.getInt(p + 4)
            md.update(glb, p, 8)
            if (type == 0x4E4F534A) {
                val text = String(glb, p + 8, len, Charsets.UTF_8)
                md.update(canon(org.json.JSONObject(text)).toByteArray(Charsets.UTF_8))
            } else {
                md.update(glb, p + 8, len)
            }
            p += 8 + len
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun canon(v: Any?): String = when (v) {
        is org.json.JSONObject -> v.keys().asSequence().map { it as String }.toList().sorted()
            .joinToString(",", "{", "}") { "${str(it)}:${canon(v.get(it))}" }
        is org.json.JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> str(v)
        is Int, is Long, is Short, is Byte -> (v as Number).toLong().toString()
        is Number -> v.toDouble().toString()
        else -> v.toString()
    }

    private fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}