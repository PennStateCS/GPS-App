package io.github.pennstatecs.geokit.gnss.nmea.parse

/**
 * An NMEA numeric field as a finite Double, or null when it is blank, unparseable, NaN or ±Infinity.
 * Shared by every parser so "null means not reported" holds for all of them.
 */
internal fun String?.toFiniteDoubleOrNull(): Double? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

/**
 * As [toFiniteDoubleOrNull], but also null when ≤ 0: for quantities that can't be zero or negative
 * (standard deviations, DOP). A receiver sending σ = 0.000 means "not computed", not "perfect".
 */
internal fun String?.toPositiveDoubleOrNull(): Double? = toFiniteDoubleOrNull()?.takeIf { it > 0.0 }
