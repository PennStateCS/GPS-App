package com.example.surveyingapp.gnss.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A snapshot of NMEA processing stats at a given moment.
 *
 * The UI consumes this as a StateFlow; values are recalculated roughly once per second.
 * Zero values are valid — they just mean nothing has been processed yet.
 */
data class DiagnosticData(
    val linesPerSecond: Double = 0.0,
    val parseErrorRate: Double = 0.0,
    val totalLinesProcessed: Long = 0,
    val totalParseErrors: Long = 0,
    val lastTwentySentences: List<String> = emptyList(),
    val lastUpdateTime: Long = System.currentTimeMillis()
)

/**
 * Tracks NMEA throughput and error rates for the Developer Tools screen.
 *
 * Both [InternalNmeaSource] and [TcpNmeaSource] feed raw NMEA lines here before
 * passing them to the fuser. The service counts lines, tracks errors, and keeps a
 * rolling list of the last 20 sentences — enough to spot obvious receiver problems
 * without the overhead of a full NMEA log.
 *
 * Metrics are recalculated once per second. Thread safety is handled through
 * atomic counters and synchronized history access.
 */
@Singleton
class DiagnosticsService @Inject constructor() {

    private val _diagnosticData = MutableStateFlow(DiagnosticData())
    val diagnosticData: StateFlow<DiagnosticData> = _diagnosticData.asStateFlow()

    private val sentenceHistory = mutableListOf<String>()
    private val lineCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private var lastCalculationTime = System.currentTimeMillis()
    private var lastLineCount = 0L

    private val maxHistorySize = 20

    /**
     * Records one successfully received NMEA line and updates throughput stats.
     * Call this for every line that reaches the fuser, regardless of whether it parses.
     */
    fun recordLine(nmeaLine: String) {
        val lines = lineCount.incrementAndGet()

        synchronized(sentenceHistory) {
            sentenceHistory.add(nmeaLine.trim())
            if (sentenceHistory.size > maxHistorySize) {
                sentenceHistory.removeAt(0)
            }
        }

        updateMetrics(lines)
    }

    /**
     * Records a line that failed to parse. Prefixes the history entry with ❌
     * so parse errors stand out in the Developer Tools sentence list.
     */
    fun recordParseError(nmeaLine: String) {
        val lines = lineCount.incrementAndGet()
        val errors = errorCount.incrementAndGet()

        synchronized(sentenceHistory) {
            sentenceHistory.add("❌ $nmeaLine".trim())
            if (sentenceHistory.size > maxHistorySize) {
                sentenceHistory.removeAt(0)
            }
        }

        updateMetrics(lines)
    }

    private fun updateMetrics(totalLines: Long) {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastCalculationTime

        if (timeDiff >= 1000) {
            val linesDiff = totalLines - lastLineCount
            val linesPerSecond = if (timeDiff > 0) {
                (linesDiff * 1000.0) / timeDiff
            } else {
                0.0
            }

            val totalErrors = errorCount.get()
            val parseErrorRate = if (totalLines > 0) {
                (totalErrors * 100.0) / totalLines
            } else {
                0.0
            }

            val recentSentences = synchronized(sentenceHistory) {
                sentenceHistory.toList()
            }

            _diagnosticData.value = DiagnosticData(
                linesPerSecond = linesPerSecond,
                parseErrorRate = parseErrorRate,
                totalLinesProcessed = totalLines,
                totalParseErrors = totalErrors,
                lastTwentySentences = recentSentences,
                lastUpdateTime = currentTime
            )

            lastCalculationTime = currentTime
            lastLineCount = totalLines
        }
    }

    /** Clears all counters and empties the sentence history. */
    fun reset() {
        lineCount.set(0)
        errorCount.set(0)
        lastCalculationTime = System.currentTimeMillis()
        lastLineCount = 0

        synchronized(sentenceHistory) {
            sentenceHistory.clear()
        }

        _diagnosticData.value = DiagnosticData()
    }
}
