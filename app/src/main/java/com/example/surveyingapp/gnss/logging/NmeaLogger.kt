package com.example.surveyingapp.gnss.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Rolling stats snapshot published by [NmeaLogger]. */
data class NmeaLogStats(
    val totalLines: Long = 0,
    val linesPerSecond: Double = 0.0,
    val parseErrors: Long = 0,
    val bufferSize: Int = 0
)

/** One entry in the NMEA ring buffer, with a wall-clock timestamp. */
data class NmeaLogEntry(
    val timestamp: Long,
    val nmeaLine: String,
    val isError: Boolean = false
)

/**
 * Thread-safe NMEA ring buffer with live throughput statistics.
 *
 * The buffer holds up to 2000 entries. When it fills up, the oldest entry is
 * dropped automatically. Throughput stats are recalculated once per second.
 *
 * All public methods are suspend functions protected by a [Mutex], so they are
 * safe to call from multiple coroutines without external synchronisation.
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

    /** Adds a successfully received NMEA line to the buffer and updates stats. */
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

    /** Adds a parse error to the buffer. The error detail is appended inline for easy reading. */
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

    /** Returns up to [maxLines] entries from the buffer, most recent first. */
    suspend fun getRecentLines(maxLines: Int = MAX_BUFFER_SIZE): List<NmeaLogEntry> {
        mutex.withLock {
            return buffer.getAll().takeLast(maxLines).reversed()
        }
    }

    /** Returns only the entries that recorded parse errors. */
    suspend fun getErrorLines(): List<NmeaLogEntry> {
        mutex.withLock {
            return buffer.getAll().filter { it.isError }
        }
    }

    /** Empties the buffer and resets all counters back to zero. */
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
 * Fixed-capacity circular buffer. When full, adding a new item overwrites the oldest one.
 * Head tracks the oldest entry; tail tracks where the next write goes.
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
