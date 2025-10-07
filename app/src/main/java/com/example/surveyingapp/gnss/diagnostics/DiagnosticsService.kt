package com.example.surveyingapp.gnss.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostic data for NMEA processing performance and current status
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
 * Service to track NMEA processing diagnostics including throughput and errors
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
     * Records a successfully processed NMEA line
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
     * Records a parse error for an NMEA line
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

        // Update metrics every second
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

    /**
     * Resets all diagnostic counters
     */
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
