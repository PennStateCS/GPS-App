package app.surrealar.gnss.model

/**
 * How a GNSS fix reached the app: the *connection*, not the receiver vendor. Which receiver produced
 * an external fix is carried separately ([Fix.receiverLabel], [Fix.accuracyClass], set from the
 * active receiver profile).
 *
 * Stored by [name] in the coordinates table. Values written by earlier versions (`RS2_EXTERNAL`,
 * `RS2_TCP`, `RS2_BT`, `rs2-external`, …) are still read by the app's converters and mapped to the
 * matching value here.
 *
 * @property label generic display name, used when no receiver label is known.
 */
public enum class Provider(public val label: String) {
    /** The device's built-in GNSS chip (via the platform NMEA listener). */
    INTERNAL("Device GNSS"),

    /** External receiver streaming NMEA over TCP/IP (e.g. Wi-Fi hotspot). Formerly RS2_EXTERNAL / RS2_TCP. */
    EXTERNAL_TCP("External GNSS (TCP)"),

    /** External receiver over Bluetooth. Formerly RS2_BT. */
    EXTERNAL_BT("External GNSS (Bluetooth)"),

    /** Fixes replayed from an NMEA log. Never averaged into a capture. */
    REPLAY("NMEA replay"),

    /** Position taken from embedded geometry in a linked 3D model file. */
    MODEL("3D model"),

    /** Anything not covered above (unknown or unrecognised stored values). */
    OTHER("Other"),
}
