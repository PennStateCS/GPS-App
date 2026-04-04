package com.example.surveyingapp.gnss.nmea.parse

import com.example.surveyingapp.gnss.nmea.sentence.GST

/**
 * Parses $..GST (GPS Pseudorange Error Statistics) sentences.
 *
 * Fields (without leading '$' or trailing *CS):
 * [0] TALKERTAG (e.g., GPGST, GLGST, GAGST, etc.)
 * [1] UTC time (hhmmss[.sss])
 * [2] RMS standard deviation of pseudorange residuals
 * [3] Standard deviation of semi-major axis (meters)
 * [4] Standard deviation of semi-minor axis (meters)
 * [5] Orientation of semi-major axis (degrees from true north)
 * [6] Standard deviation of latitude error (meters)
 * [7] Standard deviation of longitude error (meters)
 * [8] Standard deviation of altitude error (meters)
 */
class GstParser : SentenceParser<GST> {
    override val tag: String = "GST"

    override fun parse(talker: String, fields: List<String>): GST? {
        if (fields.isEmpty()) return null

        val timeRaw = fields.getOrNull(1).orEmpty().ifBlank { null }
        val rmsStdDev = fields.getOrNull(2).toDoubleOrNullSafe()
        val stdDevMajor = fields.getOrNull(3).toDoubleOrNullSafe()
        val stdDevMinor = fields.getOrNull(4).toDoubleOrNullSafe()
        val orientationDeg = fields.getOrNull(5).toDoubleOrNullSafe()
        val stdDevLat = fields.getOrNull(6).toDoubleOrNullSafe()
        val stdDevLon = fields.getOrNull(7).toDoubleOrNullSafe()
        val stdDevAlt = fields.getOrNull(8).toDoubleOrNullSafe()

        return GST(
            talker = talker,
            timeRaw = timeRaw,
            rmsStdDev = rmsStdDev,
            stdDevMajor = stdDevMajor,
            stdDevMinor = stdDevMinor,
            orientationDeg = orientationDeg,
            stdDevLat = stdDevLat,
            stdDevLon = stdDevLon,
            stdDevAlt = stdDevAlt
        )
    }

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
}
