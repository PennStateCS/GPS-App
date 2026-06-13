package com.example.surveyingapp.util

import kotlin.math.max

/**
 * Converts an Android ARGB color int to a normalized RGBA [FloatArray] suitable for OpenGL.
 * Ensures a minimum alpha of 0.2 so pins are always visible even when alpha was 0.
 */
fun argbIntToRgba(argb: Int): FloatArray {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr  8) and 0xFF
    val b = (argb       ) and 0xFF
    return floatArrayOf(r / 255f, g / 255f, b / 255f, max(0.2f, a / 255f))
}

