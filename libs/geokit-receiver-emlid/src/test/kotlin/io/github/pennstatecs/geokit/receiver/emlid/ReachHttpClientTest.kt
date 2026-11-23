package io.github.pennstatecs.geokit.receiver.emlid

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections

/** [ReachHttpClient] against an in-process loopback HTTP server (JDK HttpServer). */
class ReachHttpClientTest {

    private lateinit var server: HttpServer
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val host get() = "127.0.0.1:${server.address.port}"

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI}"
            val (code, body) = when (ex.requestURI.path) {
                "/info" -> 200 to """{"device":{"name":"Reach"}}"""
                "/empty" -> 204 to ""
                "/moved" -> { ex.responseHeaders.add("Location", "/info"); 302 to "" }
                "/boom" -> 500 to "oops"
                else -> 404 to "not found"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    @After fun tearDown() = server.stop(0)

    @Test fun get_returnsTheBody_ofA2xxResponse() = runBlocking {
        assertEquals("""{"device":{"name":"Reach"}}""", ReachHttpClient(host).get("/info"))
        assertEquals("", ReachHttpClient(host).get("/empty"))
        assertEquals(listOf("GET /info", "GET /empty"), requests.toList())
    }

    @Test fun get_followsRedirects() = runBlocking {
        assertEquals("""{"device":{"name":"Reach"}}""", ReachHttpClient(host).get("/moved"))
    }

    @Test fun get_non2xx_throwsWithCodeAndUrl() = runBlocking {
        for ((path, code) in listOf("/missing" to 404, "/boom" to 500)) {
            try {
                ReachHttpClient(host).get(path)
                fail("expected an IOException for $path")
            } catch (e: java.io.IOException) {
                assertEquals("HTTP $code for http://$host$path", e.message)
            }
        }
    }

    @Test fun get_nothingListening_throws() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val r = runCatching { ReachHttpClient("127.0.0.1:$port", connectTimeoutMs = 500, readTimeoutMs = 500).get("/info") }
        assertTrue("was $r", r.exceptionOrNull() is java.io.IOException)
    }
}
