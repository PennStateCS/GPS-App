package com.example.surveyingapp.util

import kotlin.math.*

/**
 * Computes the great-circle distance between two WGS84 latitude/longitude points.
 *
 * Uses the haversine formula, which treats the Earth as a sphere.
 * Inputs are decimal degrees. The returned distance is in meters.
 */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Computes the initial true bearing from the first point to the second point.
 *
 * The result is degrees clockwise from true north:
 * 0 = north, 90 = east, 180 = south, 270 = west.
 */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * Converts a bearing angle into an 8-point compass direction.
 *
 * Bearings are expected in degrees clockwise from true north.
 * The 22.5 degree offset centers each 45 degree compass sector around its label.
 */
fun bearingToCompass(deg: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((deg + 22.5) / 45.0).toInt() % 8]
}

/**
 * Formats a distance in meters for compact display.
 *
 * Distances under 1000 m are shown as whole meters (e.g. "34m").
 * Distances of 1000 m or more are converted to kilometers with two decimal places (e.g. "1.23km").
 */
fun formatDist(metres: Double): String =
    if (metres < 1000.0) "${"%.0f".format(metres)}m"
    else "${"%.2f".format(metres / 1000.0)}km"
