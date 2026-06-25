package com.example.surveyingapp.ui.common

import com.example.surveyingapp.gnss.model.TimestampSource
import java.text.SimpleDateFormat
import java.util.*

// Short human-readable label for each timestamp source
fun TimestampSource.label(): String = when (this) {
    TimestampSource.DEVICE -> "Device"
    TimestampSource.NMEA_ZDA -> "ZDA"
    TimestampSource.GNSS_PROVIDER -> "GNSS"
    TimestampSource.UNKNOWN -> "Unknown"
}

// Emoji icon used alongside the label in the UI
fun TimestampSource.symbol(): String = when (this) {
    TimestampSource.DEVICE -> "🕒"
    TimestampSource.NMEA_ZDA -> "🛰️"
    TimestampSource.GNSS_PROVIDER -> "📡"
    TimestampSource.UNKNOWN -> "❔"
}

/**
 * Formats a timestamp with a trailing badge showing where the time came from.
 * Example output: "14:32:15.123 [GNSS 📡]"
 *
 * @param timestampMillis Wall-clock timestamp in milliseconds.
 * @param source Which GNSS source produced this timestamp.
 * @param dateFormat Format pattern passed to [SimpleDateFormat].
 * @param includeSymbol Whether to append the emoji symbol after the label.
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
 * Formats a timestamp with a compact badge showing only the source emoji.
 * Example output: "14:32:15.123 📡"
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
