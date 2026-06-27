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
    SIMULATOR;

    /** User-friendly label for UI/export. */
    val displayName: String
        get() = when (this) {
            INTERNAL_GPS   -> "Internal GPS"
            EXTERNAL_GNSS  -> "External GNSS"
            MANUAL         -> "Manual"
            IMPORTED       -> "Imported"
            MODEL_EMBEDDED -> "Model location"
            SIMULATOR      -> "Simulator"
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
