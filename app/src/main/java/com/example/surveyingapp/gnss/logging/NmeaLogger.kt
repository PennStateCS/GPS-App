package com.example.surveyingapp.gnss.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Statistics for NMEA logging operations
 */
data class NmeaLogStats(
    val totalLines: Long = 0,
    val linesPerSecond: Double = 0.0,
    val parseErrors: Long = 0,
    val bufferSize: Int = 0
)

/**
 * Ring buffer entry for NMEA data
 */
data class NmeaLogEntry(
    val timestamp: Long,
    val nmeaLine: String,
    val isError: Boolean = false
)

/**
 * NMEA Logger with ring buffer and statistics tracking
 *
 * Maintains a ring buffer of the last 2000 NMEA lines and provides
 * real-time statistics including lines per second and parse error counts.
 */
@Singleton
class NmeaLogger @Inject constructor() {

    companion object {
        private const val MAX_BUFFER_SIZE = 2000
        private const val STATS_UPDATE_INTERVAL_MS = 1000L
    }

    private val buffer = RingBuffer<NmeaLogEntry>(MAX_BUFFER_SIZE)
    private val mutex = Mutex()

    private var totalLinesCount = 0L
    private var parseErrorCount = 0L
    private var lastStatsUpdate = System.currentTimeMillis()
    private var linesInLastSecond = 0
    private var currentLinesPerSecond = 0.0

    private val _stats = MutableStateFlow(NmeaLogStats())
    val stats: StateFlow<NmeaLogStats> = _stats.asStateFlow()

    /**
     * Log a successful NMEA line
     */
    suspend fun logNmeaLine(nmeaLine: String) {
        mutex.withLock {
            val entry = NmeaLogEntry(
                timestamp = System.currentTimeMillis(),
                nmeaLine = nmeaLine,
                isError = false
            )

            buffer.add(entry)
            totalLinesCount++
            linesInLastSecond++

            updateStatsIfNeeded()
        }
    }

    /**
     * Log a parse error
     */
    suspend fun logParseError(errorLine: String, error: String? = null) {
        mutex.withLock {
            val entry = NmeaLogEntry(
                timestamp = System.currentTimeMillis(),
                nmeaLine = "$errorLine${if (error != null) " [ERROR: $error]" else " [PARSE ERROR]"}",
                isError = true
            )

            buffer.add(entry)
            parseErrorCount++
            linesInLastSecond++

            updateStatsIfNeeded()
        }
    }

    /**
     * Get the current buffer contents (most recent first)
     */
    suspend fun getRecentLines(maxLines: Int = MAX_BUFFER_SIZE): List<NmeaLogEntry> {
        mutex.withLock {
            return buffer.getAll().takeLast(maxLines).reversed()
        }
    }

    /**
     * Get all error entries from the buffer
     */
    suspend fun getErrorLines(): List<NmeaLogEntry> {
        mutex.withLock {
            return buffer.getAll().filter { it.isError }
        }
    }

    /**
     * Clear the buffer and reset counters
     */
    suspend fun clear() {
        mutex.withLock {
            buffer.clear()
            totalLinesCount = 0L
            parseErrorCount = 0L
            linesInLastSecond = 0
            currentLinesPerSecond = 0.0
            lastStatsUpdate = System.currentTimeMillis()

            _stats.value = NmeaLogStats()
        }
    }

    private fun updateStatsIfNeeded() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastStatsUpdate

        if (timeSinceLastUpdate >= STATS_UPDATE_INTERVAL_MS) {
            currentLinesPerSecond = (linesInLastSecond * 1000.0) / timeSinceLastUpdate
            linesInLastSecond = 0
            lastStatsUpdate = currentTime

            _stats.value = NmeaLogStats(
                totalLines = totalLinesCount,
                linesPerSecond = currentLinesPerSecond,
                parseErrors = parseErrorCount,
                bufferSize = buffer.size()
            )
        }
    }
}

/**
 * Ring buffer implementation for NMEA log entries
 */
private class RingBuffer<T>(private val maxSize: Int) {
    private val buffer = Array<Any?>(maxSize) { null }
    private var head = 0
    private var tail = 0
    private var size = 0

    fun add(item: T) {
        buffer[tail] = item
        tail = (tail + 1) % maxSize

        if (size < maxSize) {
            size++
        } else {
            head = (head + 1) % maxSize
        }
    }

    fun getAll(): List<T> {
        val result = mutableListOf<T>()

        if (size == 0) return result

        var current = head
        repeat(size) {
            @Suppress("UNCHECKED_CAST")
            result.add(buffer[current] as T)
            current = (current + 1) % maxSize
        }

        return result
    }

    fun size(): Int = size

    fun clear() {
        head = 0
        tail = 0
        size = 0
        buffer.fill(null)
    }
}
