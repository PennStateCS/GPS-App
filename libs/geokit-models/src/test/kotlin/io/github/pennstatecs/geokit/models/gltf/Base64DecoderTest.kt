package io.github.pennstatecs.geokit.models.gltf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class Base64DecoderTest {

    @Test fun `matches java util Base64 for every length`() {
        val rnd = Random(7)
        for (n in 0..300) {
            val data = rnd.nextBytes(n)
            val padded = Base64.getEncoder().encodeToString(data)
            val unpadded = Base64.getEncoder().withoutPadding().encodeToString(data)
            assertArrayEquals("n=$n", data, Base64Decoder.decode(padded))
            assertArrayEquals("n=$n unpadded", data, Base64Decoder.decode(unpadded))
        }
    }

    @Test fun `whitespace, line breaks and other non-alphabet bytes are skipped like Android DEFAULT`() {
        val data = Random(3).nextBytes(200)
        val mime = Base64.getMimeEncoder().encodeToString(data)          // CRLF every 76 chars
        assertArrayEquals(data, Base64Decoder.decode(mime))
        assertArrayEquals(data, Base64Decoder.decode(" \t" + mime.replace("\r\n", "\n  ") + "\n"))
        assertArrayEquals(byteArrayOf(0, 0, 0), Base64Decoder.decode("AA*A~A"))
        assertArrayEquals(byteArrayOf(0, 0, 0), Base64Decoder.decode("AAÄAA"))
        assertArrayEquals(byteArrayOf(0), Base64Decoder.decode("AA= ="))
        assertArrayEquals(ByteArray(0), Base64Decoder.decode(""))
    }

    @Test fun `bad base64 throws`() {
        for (bad in listOf("A", "AAAAA", "=AAA", "A=AA", "AA=A", "AAA=A", "AA==AA", "AA=")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Base64Decoder.decode(bad) }
        }
    }
}
