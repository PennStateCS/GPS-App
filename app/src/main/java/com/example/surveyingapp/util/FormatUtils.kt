package com.example.surveyingapp.util

/**
 * Converts a bearing angle into an 8-point compass direction.
 *
 * Bearings are expected in degrees clockwise from true north:
 * 0 = north, 90 = east, 180 = south, and 270 = west.
 *
 * The 22.5 degree offset centers each 45 degree compass sector around its label.
 * For example, values from 337.5° through 22.5° map to "N", values around
 * 45° map to "NE", and so on.
 */
fun bearingToCompass(deg: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((deg + 22.5) / 45.0).toInt() % 8]
}

/**
 * Formats a distance in meters for compact display in the AR overlay.
 *
 * Distances under 1000 meters are shown as whole meters, such as "34m".
 * Distances of 1000 meters or more are converted to kilometers with two decimal
 * places, such as "1.23km".
 */
fun formatDist(metres: Double): String =
    if (metres < 1000.0) "${"%.0f".format(metres)}m"
    else "${"%.2f".format(metres / 1000.0)}km"