package com.example.surveyingapp.ui.common

import android.location.GnssStatus
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus

import java.time.Duration
import java.time.Instant
import java.util.Locale

fun updateStatusBar(source: String, fix: Fix, gnssStatus: GnssStatus?): String {
    val srcLabel = if (source.equals("internal", true)) "Internal" else "RS2+"
    val isInternal = srcLabel == "Internal"

    // sats: prefer Fix, else GnssStatus
    val satsUsed = fix.satsUsed
    val satsVis = fix.satsVisible ?: gnssStatus?.satelliteCount
    val satsPart = if (satsUsed > 0 && satsVis != null && satsVis > 0) "$satsUsed/$satsVis sats" else null

    // Age (prefer monotonic if you carry it; wall-clock fallback here)
    val ageSec = safeAgeSeconds(fix.timeUtc, Instant.now())

    // Coordinates: adapt precision for RTK FIX
    val coordPrec = if (!isInternal && fix.rtkStatus == RtkStatus.FIX) 7 else 5
    val latStr = fmtCoord(fix.latDeg, coordPrec)
    val lonStr = fmtCoord(fix.lonDeg, coordPrec)

    // Accuracies (sanitize NaN/Inf)
    val hAcc = sanitizeDouble(fix.hAccM)
    val vAcc = sanitizeDouble(fix.vAccM)

    // Altitude
    val alt = sanitizeDouble(fix.altEllipsoidalM)

    // Internal fix classification (don't hide stale, annotate it)
    val fixTypeInternal = if (isInternal) {
        val base = when {
            hAcc == null && alt == null -> "No Fix"
            alt != null -> "3D"
            else -> "2D"
        }
        if (ageSec > 15) "$base (stale ${ageSec}s)" else base
    } else null

    // RTK status
    val rtkStatusStr = when (fix.rtkStatus) {
        RtkStatus.FIX -> "FIX"
        RtkStatus.FLOAT -> "FLOAT"
        RtkStatus.DGPS -> "DGPS"
        RtkStatus.SINGLE -> "SINGLE"
        RtkStatus.NONE -> "NONE"
        RtkStatus.INVALID -> "--"
        RtkStatus.DEAD_RECKONING -> "DR"
    }.let { s -> if (!isInternal && ageSec > 15) "$s (stale ${ageSec}s)" else s }

    // DOPs
    val dopPart = when {
        fix.pDop != null && fix.hDop != null -> "PDOP ${fmt1(fix.pDop)} / HDOP ${fmt1(fix.hDop)}"
        fix.pDop != null -> "PDOP ${fmt1(fix.pDop)}"
        fix.hDop != null -> "HDOP ${fmt1(fix.hDop)}"
        else -> null
    }

    // Accuracy strings (use same formatter for both paths)
    val accStr = when {
        hAcc != null && vAcc != null -> "±${fmtMeters(hAcc)}/${fmtMeters(vAcc)} m"
        hAcc != null -> "±${fmtMeters(hAcc)} m"
        else -> null
    }

    // Altitude strings
    val altInternal = if (isInternal) {
        when {
            alt != null && vAcc != null -> "Alt ${fmtAlt(alt)} ±${fmtMeters(vAcc)} m"
            alt != null -> "Alt ${fmtAlt(alt)} m"
            else -> null
        }
    } else null
    val altExternal = if (!isInternal && alt != null) "Alt ${fmtAlt(alt)} m" else null

    // Baseline length - not available in current Fix model, so skip for now
    val baseline: String? = null

    // Differential correction age
    val corrAge = fix.diffAgeS?.let { secs -> if (secs >= 0) "Age ${secs.toInt()}s" else null }

    val parts = mutableListOf<String>()
    parts += srcLabel
    if (isInternal) {
        parts += (fixTypeInternal ?: "No Fix")
        satsPart?.let { parts += it }
        accStr?.let { parts += it }
        parts += "$latStr,$lonStr"
        altInternal?.let { parts += it }
    } else {
        parts += rtkStatusStr
        satsPart?.let { parts += it }
        dopPart?.let { parts += it }
        accStr?.let { parts += it }
        parts += "$latStr,$lonStr"
        altExternal?.let { parts += it }
        baseline?.let { parts += it }
        corrAge?.let { parts += it }
    }
    return parts.joinToString(" • ")
}

// === helpers ===
private fun fmtAlt(v: Double) = String.format(Locale.US, "%.2f", v)
private fun fmt1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

private fun fmtMeters(v: Double): String = when {
    v < 0.1 -> String.format(Locale.US, "%.3f", v)
    v < 1   -> String.format(Locale.US, "%.2f", v)
    v < 10  -> String.format(Locale.US, "%.2f", v)
    else    -> String.format(Locale.US, "%.0f", v)
}

private fun fmtCoord(v: Double, prec: Int): String =
    String.format(Locale.US, "%.${prec}f", v)

private fun sanitizeDouble(v: Double?): Double? =
    if (v != null && v.isFinite()) v else null

private fun safeAgeSeconds(timestamp: Instant, now: Instant): Long {
    return try {
        Duration.between(timestamp, now).seconds.coerceAtLeast(0)
    } catch (e: Exception) {
        0
    }
}
