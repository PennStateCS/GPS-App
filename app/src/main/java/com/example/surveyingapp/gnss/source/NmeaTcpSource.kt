package com.example.surveyingapp.gnss.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * GNSS source implementation for RS2+ receivers over TCP connection.
 * Connects to a TCP socket, frames NMEA lines using \r\n delimiter, and emits as Flow<String>.
 */
class NmeaTcpSource(
    private val host: String,
    private val port: Int,
    override val name: String = "RS2+ TCP ($host:$port)"
) : GnssSource {

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val LINE_BUFFER_SIZE = 1024
    }

    override fun lines(): Flow<String> = flow {
        var socket: Socket? = null
        var reader: BufferedReader? = null

        try {
            // Create socket connection with timeout
            socket = Socket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                connect(java.net.InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            }

            // Create buffered reader for line-based framing
            reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.UTF_8),
                LINE_BUFFER_SIZE
            )

            // Read lines until cancelled or connection closes
            while (coroutineContext.isActive) {
                try {
                    val line = reader.readLine()
                    if (line == null) {
                        // End of stream - connection closed by remote
                        break
                    }

                    // Trim CRLF and emit clean NMEA line
                    val cleanLine = line.trim()
                    if (cleanLine.isNotEmpty()) {
                        emit(cleanLine)
                    }
                } catch (_: SocketTimeoutException) {
                    // Socket timeout - check if still active and continue
                    if (!coroutineContext.isActive) {
                        break
                    }
                    // Continue reading after timeout
                }
            }
        } finally {
            // Clean up resources
            withContext(Dispatchers.IO) {
                try {
                    reader?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
