package com.example.surveyingapp.ui.common

import android.location.GnssStatus
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.RtkStatus
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds a compact status bar string based on source ("Internal" or "RS2+") and Fix.
 * Internal:  Source • FixType • used/visible • ±HAcc m • lat,lon • Alt alt ±vAcc m
 * RS2+:      Source • RTK • used/visible • (P/HDOP) • ±hAcc/vAcc m • lat,lon • Alt alt m • BL x.x m • Age xs
 */
fun updateStatusBar(source: String, fix: Fix, gnssStatus: GnssStatus?): String {
    val srcLabel = if (source.equals("internal", true)) "Internal" else "RS2+"
    val isInternal = srcLabel == "Internal"

    // Satellites: prefer Fix fields; fallback to GnssStatus
    val satsUsed = fix.satsUsed ?: gnssStatus?.let { s -> (0 until s.satelliteCount).count { s.usedInFix(it) } }
    val satsVis = fix.satsVisible ?: gnssStatus?.satelliteCount
    val satsPart = if (satsUsed != null && satsVis != null && satsVis > 0) "$satsUsed/$satsVis sats" else null

    // Coordinates (short format)
    val latStr = String.format(Locale.US, "%.5f", fix.lat)
    val lonStr = String.format(Locale.US, "%.5f", fix.lon)

    // Accuracies
    @Suppress("DEPRECATION")
    val hAcc = fix.hAccM ?: fix.accuracyM
    val vAcc = fix.vAccM

    // Altitude
    val alt = fix.altEllipsoidalM

    // Fix age (seconds)
    val ageSec = (Instant.now().epochSecond - fix.timestamp.epochSecond).toInt().coerceAtLeast(0)

    // Internal fix classification heuristic
    val fixTypeInternal = if (isInternal) when {
        ageSec > 15 -> "No Fix"            // stale
        hAcc == null && alt == null -> "No Fix" // no accuracy & no altitude yet
        alt != null -> "3D"
        else -> "2D"
    } else null

    // RTK status (external)
    val rtkStatusStr = when (fix.rtkStatus) {
        RtkStatus.FIX -> "FIX"
        RtkStatus.FLOAT -> "FLOAT"
        RtkStatus.DGPS -> "DGPS"
        RtkStatus.SINGLE -> "SINGLE"
        RtkStatus.INVALID, null -> "--"
    }

    // DOPs
    val dopPart = when {
        fix.pdop != null && fix.hdop != null -> "PDOP ${fmt1(fix.pdop)} / HDOP ${fmt1(fix.hdop)}"
        fix.pdop != null -> "PDOP ${fmt1(fix.pdop)}"
        fix.hdop != null -> "HDOP ${fmt1(fix.hdop)}"
        else -> null
    }

    // Accuracy strings
    val accInternal = hAcc?.let { "±${it.roundToInt()} m" }
    val accExternal = when {
        hAcc != null && vAcc != null -> "±${fmtMeters(hAcc)}/${fmtMeters(vAcc)} m"
        hAcc != null -> "±${fmtMeters(hAcc)} m"
        else -> null
    }

    // Altitude strings
    val altInternal = if (alt != null && vAcc != null) {
        "Alt ${fmtAlt(alt)} ±${vAcc.roundToInt()} m"
    } else if (alt != null) {
        "Alt ${fmtAlt(alt)} m"
    } else null
    val altExternal = alt?.let { "Alt ${fmtAlt(it)} m" }

    // Baseline length (external only)
    val baseline = fix.baselineLengthM?.let { if (it > 0.1) "BL ${fmt1(it)} m" else null }

    // Differential correction age if available (Duration -> seconds)
    val corrAge = fix.diffAge?.inWholeSeconds?.let { secs -> if (secs >= 0) "Age ${secs}s" else null }

    val parts = mutableListOf<String>()
    parts += srcLabel
    if (isInternal) {
        parts += (fixTypeInternal ?: "No Fix")
        satsPart?.let { parts += it }
        accInternal?.let { parts += it }
        parts += "$latStr,$lonStr"
        altInternal?.let { parts += it }
    } else {
        parts += rtkStatusStr
        satsPart?.let { parts += it }
        dopPart?.let { parts += it }
        accExternal?.let { parts += it }
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
