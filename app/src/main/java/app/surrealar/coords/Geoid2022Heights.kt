package app.surrealar.coords

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.geoid.Geoid2022Model
import io.github.pennstatecs.geokit.geoid.GeoidModelInfo
import io.github.pennstatecs.geokit.geoid.GeoidResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.floor

/**
 * A time-dependent geoid model (GEOID2022, NGS beta): N at a position given in the model's frame
 * ([GeoidModelInfo.horizontalFrameId], ITRF2020 for GEOID2022) at a coordinate epoch.
 */
interface TimeDependentGeoid {
    val info: GeoidModelInfo

    /** N at ([latDeg], [lonDeg]) for the coordinate epoch [epochYear] (decimal year), or why it is unavailable. */
    fun undulationAt(latDeg: Double, lonDeg: Double, epochYear: Double): GeoidResult
}

/**
 * GEOID2022 (beta) as a [TimeDependentGeoid]: NGS's N(t) = SGEOID2022 + DGEOID2022·(mjd − 58849)/365.25
 * ([Geoid2022Model.undulationAt]) on the UTC calendar day of the coordinate epoch ([Geoid2022Epoch.utcDate]).
 * An unavailable rate makes the height unavailable: the static 2020.0 value is never used instead.
 *
 * @param guard wraps every lookup (the app passes [NonBlockingGeoidModel.guarded], so the UI thread never
 *   loads the packs); identity by default (tests, background work).
 */
class Geoid2022TimeDependent(
    private val model: Geoid2022Model,
    private val guard: (() -> GeoidResult) -> GeoidResult = { it() },
) : TimeDependentGeoid {
    override val info: GeoidModelInfo get() = model.info

    override fun undulationAt(latDeg: Double, lonDeg: Double, epochYear: Double): GeoidResult {
        val d = Geoid2022Epoch.utcDate(epochYear)
        return guard { model.undulationAt(latDeg, lonDeg, d.year, d.monthValue, d.dayOfMonth) }
    }
}

/** Coordinate epoch → calendar day, for NGS's day-based GEOID2022 time formula. */
object Geoid2022Epoch {
    /**
     * The UTC calendar day of the decimal year [epochYear], the inverse of [Itrf2014ToNad83.decimalYear]
     * (which turns the capture/fix time into the app's coordinate epochs): fraction of the UTC year → instant →
     * UTC date. Exact to the day for every epoch the app produces.
     */
    fun utcDate(epochYear: Double): LocalDate {
        require(epochYear.isFinite()) { "epoch must be finite, was $epochYear" }
        val year = floor(epochYear).toInt()
        val start = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ms = start + floor((epochYear - year) * (end - start)).toLong()
        return Instant.ofEpochMilli(ms.coerceIn(start, end - 1)).atZone(ZoneOffset.UTC).toLocalDate()
    }
}

/** Labels of GEOID2022 (beta) heights: always beta, always with the NGS dataset version and the epoch of N. */
object Geoid2022Labels {
    /** Vertical datum label next to a height (toolbar, height text): "NAPGD2022 beta". */
    const val VERTICAL_LABEL: String = "${Geoid2022Model.VERTICAL_DATUM} beta"

    /** Model and NGS dataset version: "GEOID2022 (beta beta_v0a)". */
    const val MODEL: String = "GEOID2022 (beta ${Geoid2022Model.NGS_VERSION})"

    /** Height provenance in system summaries / CSV: "GEOID2022 (beta beta_v0a) epoch 2026.886". */
    fun note(epochYear: Double): String = String.format(Locale.US, "%s epoch %.3f", MODEL, epochYear)

    /** Short caution shown with beta results. */
    const val CAUTION: String = "Preliminary NGS beta product, not for authoritative survey results."
}
