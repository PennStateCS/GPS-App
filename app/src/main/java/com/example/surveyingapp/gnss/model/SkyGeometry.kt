// file: app/src/main/java/com/example/surveyingapp/gnss/model/SkyGeometry.kt
package com.example.surveyingapp.gnss.model

enum class Constellation { GPS, GLONASS, GALILEO, BEIDOU, QZSS, SBAS, IRNSS, UNKNOWN }

/** Normalized satellite geometry used by charts and skyplot. */
data class SkyGeometry(
    val svid: Int,                 // PRN/SVID
    val constellation: Constellation,
    val azDeg: Double?,            // azimuth degrees [0..360)
    val elDeg: Double?,            // elevation degrees [-10..90]
    val snrDbHz: Double?,          // SNR in dB-Hz
    val usedInFix: Boolean
)

