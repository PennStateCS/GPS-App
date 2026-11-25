package io.github.pennstatecs.geokit.receiver.emlid

/**
 * Emlid Reach receivers and their antenna phase-centre offsets: the height of the L1 phase centre
 * above the antenna reference point on the bottom of the receiver, for
 * [AntennaGeometry][io.github.pennstatecs.geokit.gnss.receiver.AntennaGeometry].
 *
 * @property label display name.
 * @property phaseCenterOffsetM ARP to L1 APC, metres; null when Emlid hasn't published one separately
 *   (the user has to enter it).
 */
public enum class EmlidReachModel(public val label: String, public val phaseCenterOffsetM: Double?) {
    /** Reach RS2. Emlid RS2/RS2+ manual: ARP → APC 134 mm. */
    RS2("Reach RS2", 0.134),
    /** Reach RS2+. Same antenna as the RS2: 134 mm. */
    RS2_PLUS("Reach RS2+", 0.134),
    /** Reach RS4. Emlid RS4 specs: L1 phase-centre offset 95 mm. */
    RS4("Reach RS4", 0.095),
    /** Reach RS4 Pro. Not confirmed separately from the RS4, so unknown. */
    RS4_PRO("Reach RS4 Pro", null);

    public companion object {
        private val SEPARATORS = Regex("[\\s_\\-./]+")

        /**
         * The model a receiver *explicitly reported* (the `model` / `type` field of the Reach `/info` or
         * `/status` response), or null when it is blank, unknown or ambiguous. Matching is on exact
         * normalised tokens (case-insensitive; `+` reads as "plus"; whitespace, `_`, `-`, `.` and `/`
         * separate tokens), never on substrings:
         *
         * - "RS4 Pro" / "rs4pro" -> [RS4_PRO] (checked before RS4);
         * - "RS4" -> [RS4];
         * - "RS2+" / "RS2 Plus" / "rs2plus" -> [RS2_PLUS];
         * - a plain "RS2" -> null: the RS2+ is a revision of the RS2 and firmware may report either
         *   string, so a plain "RS2" cannot tell them apart (the app keeps the user's profile);
         * - anything else (RS3, RS+, M2, "RS40", blank, null) -> null.
         *
         * Only what the receiver reports is identification: a discovery name, an IP or a profile chosen
         * by the user never is. Identification says nothing about which features are enabled on the
         * receiver (tilt compensation, compensated NMEA output, orthometric heights).
         */
        public fun fromReportedModel(reported: String?): EmlidReachModel? {
            if (reported.isNullOrBlank()) return null
            val tokens = reported.lowercase(java.util.Locale.ROOT)
                // The Reach web service glues the family name to the model: /info device.type = "ReachRS2+"
                // (RS2+ firmware 33.1, observed 2026-11-25). Split "reach" from a following "rs".
                .replace(Regex("reach(?=rs)"), "reach ")
                .replace("+", " plus ")
                .split(SEPARATORS)
                .filter { it.isNotEmpty() }
            fun hasPair(a: String, b: String) = tokens.zipWithNext().any { (x, y) -> x == a && y == b }
            return when {
                "rs4pro" in tokens || hasPair("rs4", "pro") -> RS4_PRO
                "rs4" in tokens -> RS4
                "rs2plus" in tokens || hasPair("rs2", "plus") -> RS2_PLUS
                else -> null
            }
        }
    }
}
