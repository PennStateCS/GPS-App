package app.surrealar.gnss.external.model

/**
 * Live correction status pushed from the Reach via socket.io broadcast events
 * (parsed by [app.surrealar.gnss.external.ReachCorrectionsService], mapped to the receiver-neutral
 * `ReceiverCorrections` by `EmlidMapping`).
 *
 * The other Reach app-facing models (battery, device, storage, connection status, device commands)
 * were replaced by the neutral receiver models in gnss-core (`app.surrealar.gnss.receiver`).
 */
data class ReachCorrectionsInfo(
    val isReceiving: Boolean,
    val channel: String?,
    val satellitesInView: Int?,
    val ageSeconds: Double?,
    val baselineKm: Double?,
    val baseLatDeg: Double?,
    val baseLonDeg: Double?,
    val baseAltM: Double?,
    val solution: String?
)