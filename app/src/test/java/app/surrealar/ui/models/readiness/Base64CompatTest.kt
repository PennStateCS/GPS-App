package app.surrealar.ui.models.readiness

import android.util.Base64
import io.github.pennstatecs.geokit.models.gltf.Base64Decoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * geokit-models decodes glTF data: URIs with its own base64 decoder (the module can't use Android, and
 * java.util.Base64 needs API 26). It must accept and reject exactly what android.util.Base64.DEFAULT,
 * the decoder the facts reader used before, did. Robolectric runs the real Android implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Base64CompatTest {

    private fun android(s: String): ByteArray? = runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull()
    private fun geokit(s: String): ByteArray? = runCatching { Base64Decoder.decode(s) }.getOrNull()

    @Test fun `same bytes or same failure as android Base64 DEFAULT`() {
        val rnd = Random(11)
        val cases = mutableListOf(
            "", "A", "AA", "AAA", "AAAA", "AAAAA", "AA=", "AA==", "AAA=", "AA= =", "AA==AA", "=AAA", "A=AA",
            "AA=A", "AAA=A", "AAA==", "AA*A~A", "AAÄAA", " \tQUJD\r\nREVG\n", "QUJD-_", "Q U J D", "QUJD====",
        )
        repeat(200) {
            val data = rnd.nextBytes(rnd.nextInt(0, 80))
            val enc = Base64.encodeToString(data, if (it % 2 == 0) Base64.DEFAULT else Base64.NO_WRAP or Base64.NO_PADDING)
            cases += enc
            // A data: URI prefix cut at an arbitrary character count, as GltfFactsReader does.
            cases += enc.substring(0, rnd.nextInt(0, enc.length + 1))
        }
        for (s in cases) {
            val a = android(s)
            val g = geokit(s)
            assertEquals("failure differs for \"$s\"", a == null, g == null)
            if (a != null) assertArrayEquals("bytes differ for \"$s\"", a, g)
        }
    }
}
