package com.example.surveyingapp.gnss.model

/**
 * Indicates where the timestamp for a GNSS fix originated.
 */
enum class TimestampSource {
    /** From the Android system clock (elapsedRealtime or wall time). */
    DEVICE,

    /** From the NMEA ZDA sentence (receiver-provided UTC). */
    NMEA_ZDA,

    /** From the GNSS provider metadata (e.g., GnssClock, GpsTime). */
    GNSS_PROVIDER,

    /** Unknown or unspecified source. */
    UNKNOWN
}
