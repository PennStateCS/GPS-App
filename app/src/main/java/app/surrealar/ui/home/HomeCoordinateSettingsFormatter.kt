package app.surrealar.ui.home

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import io.github.pennstatecs.geokit.projection.SpcsZones

/**
 * Home "Coordinate settings" card: the settings that decide how coordinates are shown and exported, one short line
 * each, as they are actually USED (effective values: a beta choice saved while the preview is off shows the
 * default it is replaced by, with a note). Pure (no Android) so it is unit-tested.
 */
object HomeCoordinateSettingsFormatter {

    data class Row(val label: String, val value: String)
    data class Summary(val rows: List<Row>, val note: String?)

    fun summary(s: CoordinateSystemSettings): Summary {
        val h = s.effectiveHorizontal
        val system = when (h) {
            HorizontalSystem.STATE_PLANE ->
                SpcsZones.byEpsg(s.spcsZoneEpsg)?.let { "SPCS83 · ${it.label}" } ?: h.label
            HorizontalSystem.SPCS2022 ->
                s.spcs2022Zone?.substringAfter(':')?.takeIf { it.isNotBlank() }?.let { "SPCS2022 (beta) · zone $it" } ?: h.label
            else -> h.label
        }
        val datum = when (h) {
            // SPCS2022: the zone's own 2022 frame (NATRF/PATRF/CATRF/MATRF2022), at each position's epoch.
            HorizontalSystem.SPCS2022 -> "Zone's 2022 frame (beta)"
            else -> s.effectiveDatum.label
        }
        val rows = buildList {
            add(Row("System", system))
            add(Row("Datum", datum))
            add(Row("Units", s.unit.label))
            add(Row("Height", s.effectiveVertical.menuLabel))
            add(Row("Receiver datum", s.externalReceiverDatum.label))
            if (s.nsrs2022Preview) add(Row("NSRS 2022 preview", "On (beta)"))
        }
        val note = when {
            s.betaHorizontalSuspended || s.betaDatumSuspended || s.betaVerticalSuspended ->
                "A saved beta choice is not in use; defaults are shown (Settings → Coordinates)."
            else -> null
        }
        return Summary(rows, note)
    }
}
