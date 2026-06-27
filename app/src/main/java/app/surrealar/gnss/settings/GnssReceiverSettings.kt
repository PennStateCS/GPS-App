package app.surrealar.gnss.settings

/** Receiver tuning preferences. `highAccuracy` requests the high-accuracy location mode where available. */
data class GnssReceiverSettings(
    val highAccuracy: Boolean = true
)
