package app.surrealar.util

import kotlin.math.max

/**
 * Converts an Android ARGB color integer into normalized OpenGL RGBA components.
 *
 * Android stores color channels as ARGB:
 *
 * A = bits 24-31
 * R = bits 16-23
 * G = bits 8-15
 * B = bits 0-7
 *
 * OpenGL rendering expects the channels as floating-point RGBA values in the
 * range 0.0 to 1.0, so each 8-bit channel is divided by 255.
 *
 * A minimum alpha of 0.2 is enforced so AR pins remain visible even if the stored
 * Android color is fully transparent.
 */
fun argbIntToRgba(argb: Int): FloatArray {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr  8) and 0xFF
    val b = (argb       ) and 0xFF
    return floatArrayOf(r / 255f, g / 255f, b / 255f, max(0.2f, a / 255f))
}