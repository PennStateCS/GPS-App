package com.example.surveyingapp.gnss.reach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small, focused HTTP client for RS2+ queries on port 80.
 * Centralizes timeouts and simple retries so services remain thin.
 */
class ReachHttpClient(
    private val host: String,
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 4000
) {
    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val url = URL("http://$host$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code for http://$host$path")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
