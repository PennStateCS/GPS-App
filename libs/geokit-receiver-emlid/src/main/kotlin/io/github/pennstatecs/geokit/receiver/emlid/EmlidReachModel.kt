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
    RS4_PRO("Reach RS4 Pro", null),
}
