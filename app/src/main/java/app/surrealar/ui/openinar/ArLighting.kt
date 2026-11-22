package app.surrealar.ui.openinar

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lighting for AR models (pure, testable; ArFilamentRenderer applies it).
 *
 * The AR scene used to have one fixed directional light and no ambient light, so any face turned
 * away from it rendered pitch black. Now there is always an ambient (indirect, spherical-harmonics)
 * light with a floor, plus a main directional light. When ARCore's Environmental HDR estimate is
 * valid (the session already requests it), the main light's direction/colour/intensity and the
 * ambient SH follow the real scene, smoothed over time; otherwise a neutral fallback is used.
 *
 * Frames: ARCore world == Filament world here (the renderer uses ARCore's view matrix).
 */
object ArLighting {

    // ── Tunables (log line LIGHT_ESTIMATE shows the live values for tuning) ─────────────
    /** Fallback main light (lux) and direction (the direction light travels, Filament convention). */
    const val FALLBACK_SUN_LUX = 100_000f
    private val FALLBACK_SUN_DIR = normalize(floatArrayOf(0.5f, -1.0f, -0.5f))
    /** Indirect-light intensity applied to the ambient SH. */
    const val AMBIENT_INTENSITY = 30_000f
    /** Fallback ambient: uniform SH DC term (all channels). */
    const val FALLBACK_AMBIENT_DC = 1.0f
    /** Floor on the ambient DC term, so no face can go black even in a dark estimate. */
    const val MIN_AMBIENT_DC = 0.35f
    /** Lux per unit of ARCore's main-light intensity (largest channel), clamped below. */
    const val SUN_LUX_PER_UNIT = 60_000f
    const val SUN_LUX_MIN = 15_000f
    const val SUN_LUX_MAX = 110_000f
    /** Exponential smoothing per update (0..1; higher = faster). Updates arrive ~5×/s. */
    const val SMOOTHING = 0.25f

    /**
     * ARCore Environmental HDR ambient SH → Filament irradiance SH: per-coefficient factors for the 9
     * bands (as used by Sceneform with Filament).
     */
    private val SH_IRRADIANCE_FACTORS = floatArrayOf(
        0.282095f, -0.325735f, 0.325735f, -0.325735f, 0.273137f, -0.273137f, 0.078848f, -0.273137f, 0.136569f,
    )

    /** What ARCore reported this update (copied off the Frame on the GL thread). */
    class Estimate(
        val valid: Boolean,
        /** Direction TOWARD the main light (ARCore convention), world space. */
        val mainLightDirection: FloatArray?,
        /** Linear RGB intensity of the main light. */
        val mainLightIntensity: FloatArray?,
        /** 27 floats: 9 SH coefficients × RGB (ARCore order). */
        val ambientSh: FloatArray?,
    )

    /** Lighting to apply. */
    class State(
        /** Direction light travels (Filament), unit length. */
        val sunDirection: FloatArray,
        /** Linear RGB, max channel 1. */
        val sunColor: FloatArray,
        val sunLux: Float,
        /** 27 floats: Filament irradiance SH (9 bands × RGB). */
        val ambientSh: FloatArray,
        val ambientIntensity: Float,
        val fromEstimate: Boolean,
    )

    fun fallback(): State = State(
        sunDirection = FALLBACK_SUN_DIR.copyOf(),
        sunColor = floatArrayOf(1f, 1f, 1f),
        sunLux = FALLBACK_SUN_LUX,
        ambientSh = FloatArray(27).also { it[0] = FALLBACK_AMBIENT_DC; it[1] = FALLBACK_AMBIENT_DC; it[2] = FALLBACK_AMBIENT_DC },
        ambientIntensity = AMBIENT_INTENSITY,
        fromEstimate = false,
    )

    /**
     * Next lighting from [estimate], smoothed toward from [previous] (null = jump). An invalid or
     * incomplete estimate keeps the previous lighting (or the fallback when there is none).
     */
    fun next(estimate: Estimate?, previous: State?): State {
        val target = target(estimate) ?: return previous ?: fallback()
        if (previous == null || previous.fromEstimate != target.fromEstimate) return target
        val a = SMOOTHING
        return State(
            sunDirection = normalize(lerp(previous.sunDirection, target.sunDirection, a)),
            sunColor = lerp(previous.sunColor, target.sunColor, a),
            sunLux = previous.sunLux + (target.sunLux - previous.sunLux) * a,
            ambientSh = lerp(previous.ambientSh, target.ambientSh, a),
            ambientIntensity = target.ambientIntensity,
            fromEstimate = true,
        )
    }

    /** Lighting straight from a valid estimate, or null when it can't be used. */
    internal fun target(e: Estimate?): State? {
        if (e == null || !e.valid) return null
        val dir = e.mainLightDirection?.takeIf { it.size >= 3 && it.all(Float::isFinite) } ?: return null
        val rgb = e.mainLightIntensity?.takeIf { it.size >= 3 && it.all(Float::isFinite) } ?: return null
        val sh = e.ambientSh?.takeIf { it.size >= 27 && it.all(Float::isFinite) } ?: return null
        val len = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        if (len < 1e-6f) return null
        val maxC = max(rgb[0], max(rgb[1], rgb[2]))
        val color = if (maxC > 1e-6f) floatArrayOf(rgb[0] / maxC, rgb[1] / maxC, rgb[2] / maxC) else floatArrayOf(1f, 1f, 1f)
        val lux = (maxC * SUN_LUX_PER_UNIT).coerceIn(SUN_LUX_MIN, SUN_LUX_MAX)
        return State(
            // ARCore points TOWARD the light; Filament wants the direction light travels.
            sunDirection = floatArrayOf(-dir[0] / len, -dir[1] / len, -dir[2] / len),
            sunColor = color,
            sunLux = lux,
            ambientSh = toIrradiance(sh),
            ambientIntensity = AMBIENT_INTENSITY,
            fromEstimate = true,
        )
    }

    /** ARCore ambient SH → Filament irradiance SH, with the DC floor applied per channel. */
    internal fun toIrradiance(sh: FloatArray): FloatArray {
        val out = FloatArray(27)
        for (i in 0 until 27) out[i] = sh[i] * SH_IRRADIANCE_FACTORS[i / 3]
        for (c in 0 until 3) out[c] = max(out[c], MIN_AMBIENT_DC)
        return out
    }

    /** True when [a] and [b] differ enough to be worth rebuilding the indirect light. */
    fun shChangedEnough(a: FloatArray, b: FloatArray, threshold: Float = 0.02f): Boolean {
        for (i in a.indices) if (kotlin.math.abs(a[i] - b[i]) > threshold) return true
        return false
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    private fun normalize(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l < 1e-6f) floatArrayOf(0f, -1f, 0f) else floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
}
