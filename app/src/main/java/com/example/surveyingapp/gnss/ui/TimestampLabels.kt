package com.example.surveyingapp.gnss.ui

import com.example.surveyingapp.gnss.model.TimestampSource
import java.text.SimpleDateFormat
import java.util.*

fun TimestampSource.label(): String = when (this) {
    TimestampSource.DEVICE -> "Device"
    TimestampSource.NMEA_ZDA -> "ZDA"
    TimestampSource.GNSS_PROVIDER -> "GNSS"
    TimestampSource.UNKNOWN -> "Unknown"
}

fun TimestampSource.symbol(): String = when (this) {
    TimestampSource.DEVICE -> "🕒"
    TimestampSource.NMEA_ZDA -> "🛰️"
    TimestampSource.GNSS_PROVIDER -> "📡"
    TimestampSource.UNKNOWN -> "❔"
}

/**
 * Formats a timestamp in milliseconds with a suffix badge showing the timestamp source.
 *
 * @param timestampMillis The timestamp in milliseconds since epoch
 * @param source The source of the timestamp
 * @param dateFormat Optional date format pattern (defaults to "HH:mm:ss.SSS")
 * @param includeSymbol Whether to include the emoji symbol in the badge (defaults to true)
 * @return Formatted timestamp string with source badge (e.g., "14:32:15.123 [GNSS 📡]")
 */
fun formatTimestampWithSourceBadge(
    timestampMillis: Long,
    source: TimestampSource,
    dateFormat: String = "HH:mm:ss.SSS",
    includeSymbol: Boolean = true
): String {
    val formatter = SimpleDateFormat(dateFormat, Locale.getDefault())
    val timestamp = formatter.format(Date(timestampMillis))

    val badge = if (includeSymbol) {
        "[${source.label()} ${source.symbol()}]"
    } else {
        "[${source.label()}]"
    }

    return "$timestamp $badge"
}

/**
 * Formats a timestamp with a compact source badge using only the symbol.
 *
 * @param timestampMillis The timestamp in milliseconds since epoch
 * @param source The source of the timestamp
 * @param dateFormat Optional date format pattern (defaults to "HH:mm:ss.SSS")
 * @return Formatted timestamp string with compact badge (e.g., "14:32:15.123 📡")
 */
fun formatTimestampWithCompactBadge(
    timestampMillis: Long,
    source: TimestampSource,
    dateFormat: String = "HH:mm:ss.SSS"
): String {
    val formatter = SimpleDateFormat(dateFormat, Locale.getDefault())
    val timestamp = formatter.format(Date(timestampMillis))
    return "$timestamp ${source.symbol()}"
}
