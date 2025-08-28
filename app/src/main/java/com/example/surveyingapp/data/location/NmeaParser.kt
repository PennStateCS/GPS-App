package com.example.surveyingapp.data.location

/** Minimal NMEA parser supporting GGA & RMC with checksum verification. */
class NmeaParser {
    data class Gga(
        val timeSec: Double?,
        val lat: Double?,
        val lon: Double?,
        val fixQuality: Int?,
        val sats: Int?,
        val hdop: Double?,
        val alt: Double?
    )
    data class Rmc(
        val timeSec: Double?,
        val date: String?, // DDMMYY
        val lat: Double?,
        val lon: Double?,
        val speedKnots: Double?,
        val course: Double?
    )

    fun parse(lineRaw: String): Parsed? {
        val line = lineRaw.trim()
        if (line.length < 9 || line[0] != '$') return null
        val star = line.indexOf('*')
        if (star < 0 || star + 3 > line.length) return null
        val body = line.substring(1, star)
        val checksum = line.substring(star + 1, star + 3)
        val calc = body.fold(0) { acc, c -> acc xor c.code }
        val calcHex = calc.toString(16).uppercase().padStart(2, '0')
        if (calcHex != checksum.uppercase()) return null
        val parts = body.split(',')
        return when (parts[0]) {
            "GPGGA", "GNGGA" -> parseGga(parts)?.let { Parsed.GgaMsg(it) }
            "GPRMC", "GNRMC" -> parseRmc(parts)?.let { Parsed.RmcMsg(it) }
            else -> null
        }
    }

    private fun parseGga(p: List<String>): Gga? {
        if (p.size < 15) return null
        val timeSec = p[1].toDoubleOrNull()
        val lat = decodeLatLon(p[2], p.getOrNull(3))
        val lon = decodeLatLon(p[4], p.getOrNull(5))
        val fixQ = p[6].toIntOrNull()
        val sats = p[7].toIntOrNull()
        val hdop = p[8].toDoubleOrNull()
        val alt = p[9].toDoubleOrNull()
        return Gga(timeSec, lat, lon, fixQ, sats, hdop, alt)
    }

    private fun parseRmc(p: List<String>): Rmc? {
        if (p.size < 12) return null
        val timeSec = p[1].toDoubleOrNull()
        val status = p[2]
        if (status != "A" && status != "D") return null
        val lat = decodeLatLon(p[3], p.getOrNull(4))
        val lon = decodeLatLon(p[5], p.getOrNull(6))
        val speedKnots = p[7].toDoubleOrNull()
        val course = p[8].toDoubleOrNull()
        val date = p[9]
        return Rmc(timeSec, date, lat, lon, speedKnots, course)
    }

    private fun decodeLatLon(raw: String?, hemi: String?): Double? {
        if (raw.isNullOrBlank() || hemi.isNullOrBlank()) return null
        // Format: ddmm.mmmm (lat) or dddmm.mmmm (lon)
        val dot = raw.indexOf('.')
        if (dot < 0) return null
        val minutesStart = if (raw.length >= 5 && (raw.length - dot) >= 3) raw.length - (raw.length - (raw.takeWhile { it.isDigit() }.length - 2)) else raw.length - 7
        val degLen = if (raw.length - dot > 5) 3 else 2
        val degreesPart = raw.substring(0, degLen)
        val minutesPart = raw.substring(degLen)
        val deg = degreesPart.toDoubleOrNull() ?: return null
        val minutes = minutesPart.toDoubleOrNull() ?: return null
        var v = deg + minutes / 60.0
        if (hemi == "S" || hemi == "W") v = -v
        return v
    }

    sealed class Parsed { data class GgaMsg(val gga:Gga): Parsed(); data class RmcMsg(val rmc:Rmc): Parsed() }
}
