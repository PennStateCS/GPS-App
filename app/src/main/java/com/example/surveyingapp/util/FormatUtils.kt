package com.example.surveyingapp.util

/**
 * Converts a bearing in degrees (0 = North, clockwise) to an 8-point compass abbreviation.
 */
fun bearingToCompass(deg: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((deg + 22.5) / 45.0).toInt() % 8]
}

/**
 * Formats a metre distance as a short human-readable string ("34m" or "1.23km").
 */
fun formatDist(metres: Double): String =
    if (metres < 1000.0) "${"%.0f".format(metres)}m"
    else "${"%.2f".format(metres / 1000.0)}km"

