package app.surrealar.domain.model

import java.util.Locale

/**
 * How a coordinate's position was acquired.
 *
 * This is distinct from the *provider* (which receiver/location source produced the data):
 * a single provider can be used by several capture methods. Stored on the coordinate as the
 * enum name; [fromStorage] parses both the canonical names and the legacy free-form strings
 * that earlier builds wrote ("internal_gps", "external_gnss", "model_embedded", …).
 */
enum class CaptureMethod {
    INTERNAL_GPS,
    EXTERNAL_GNSS,
    MANUAL,
    IMPORTED,
    MODEL_EMBEDDED,
    SIMULATOR,
    /**
     * External receiver capture whose epochs were the receiver's own tilt-compensated pole-tip positions
     * (Reach RS4 / RS4 Pro with compensated NMEA output, Phase 4 WP C). The receiver applied its pole height
     * (set in Emlid Flow); the app subtracted no pole height / phase-centre offset (antennaHeightM is null).
     */
    EXTERNAL_GNSS_POLE_TIP,
    /**
     * External receiver capture whose position reference could not be confirmed (Phase 4 stabilization WP A):
     * a Reach RS4 / RS4 Pro with "Compensated NMEA output" still Unknown, or a receiver profile that could not be
     * read when the capture started. Today's antenna reduction WAS applied (pole height + phase-centre offset,
     * antennaHeightM set), but if the receiver was streaming tilt-compensated pole-tip positions the height has
     * been reduced twice. Saved only after the user acknowledged this. Appended: stored by enum name like the
     * others; no schema change.
     */
    EXTERNAL_GNSS_REFERENCE_UNCONFIRMED;

    /** User-friendly label for UI/export. */
    val displayName: String
        get() = when (this) {
            INTERNAL_GPS   -> "Internal GPS"
            EXTERNAL_GNSS  -> "External GNSS"
            MANUAL         -> "Manual"
            IMPORTED       -> "Imported"
            MODEL_EMBEDDED -> "Model location"
            SIMULATOR      -> "Simulator"
            EXTERNAL_GNSS_POLE_TIP -> "External GNSS (receiver pole tip)"
            EXTERNAL_GNSS_REFERENCE_UNCONFIRMED -> "External GNSS (reference unconfirmed)"
        }

    /** Canonical storage token (enum name). */
    val storageValue: String get() = name

    companion object {
        /**
         * Lenient parse for persisted values, tolerant of the legacy free-form strings.
         * Returns null when the value is blank or unrecognised (caller decides the default).
         */
        fun fromStorage(raw: String?): CaptureMethod? {
            val v = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
            return when (v) {
                "internal_gps", "internal", "fused"        -> INTERNAL_GPS
                "external_gnss", "external", "rtk", "averaged" -> EXTERNAL_GNSS
                "manual"                                    -> MANUAL
                "imported", "import"                        -> IMPORTED
                "model_embedded", "model"                   -> MODEL_EMBEDDED
                "simulator", "sim"                          -> SIMULATOR
                else -> runCatching { valueOf(v.uppercase(Locale.US)) }.getOrNull()
            }
        }
    }
}

/**
 * Which physical point a saved external capture refers to, derived from its stored captureMethod (Phase 4
 * stabilization WP A). Display / export only: nothing here changes a stored value.
 *
 * @property token stable lower-case token written to exports (CSV column, JSON backup key `positionReference`).
 */
enum class PositionReferenceKind(val token: String) {
    /** The receiver antenna: the app subtracted its pole height + phase-centre offset (today's maths). */
    ANTENNA("antenna"),
    /** The receiver's own tilt-compensated pole tip: the app subtracted no pole height. */
    RECEIVER_POLE_TIP("receiver_pole_tip"),
    /** Antenna reduction applied, but the receiver may have streamed compensated positions (reduced twice). */
    UNCONFIRMED("unconfirmed");

    companion object {
        /**
         * The reference of a point with [captureMethod]; [externalProvider] = its provider is an external receiver
         * (an "external_gnss" / legacy "averaged" method only means the antenna when the provider is external, so
         * simulator rows stay unlabelled). Null for internal, manual, imported, model or unknown points.
         */
        fun of(captureMethod: String?, externalProvider: Boolean): PositionReferenceKind? =
            when (CaptureMethod.fromStorage(captureMethod)) {
                CaptureMethod.EXTERNAL_GNSS_POLE_TIP -> RECEIVER_POLE_TIP
                CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED -> UNCONFIRMED
                CaptureMethod.EXTERNAL_GNSS -> if (externalProvider) ANTENNA else null
                else -> null
            }
    }
}
