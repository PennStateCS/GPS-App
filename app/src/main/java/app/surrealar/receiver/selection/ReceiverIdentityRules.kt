package app.surrealar.receiver.selection

import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ProfileOrigin
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel
import java.util.Locale

/**
 * Pure identification and profile-mismatch rules (Phase 5). Identification comes ONLY from what the active receiver
 * reports through ReceiverDeviceManager (Emlid HTTP device info). Discovery names, IP addresses and profiles chosen by
 * the user are never proof of a model, and a model never proves a feature is enabled.
 */
object ReceiverIdentityRules {

    /** The app profile for an identified model; null for models without one (RS2: can't be told from an RS2+). */
    fun profileFor(model: EmlidReachModel?): ExternalReceiverProfile? = when (model) {
        EmlidReachModel.RS2_PLUS -> ExternalReceiverProfile.REACH_RS2_PLUS
        EmlidReachModel.RS4 -> ExternalReceiverProfile.REACH_RS4
        EmlidReachModel.RS4_PRO -> ExternalReceiverProfile.REACH_RS4_PRO
        EmlidReachModel.RS2, null -> null
    }

    /**
     * What the receiver at [savedHost] reported, or null when nothing applies: the external source is not active,
     * there is no device info, the info is for another host (a stale poll from the previous receiver), or it reports
     * nothing identifying (a failed poll).
     */
    fun identify(info: ReceiverDeviceInfo?, savedHost: String?, externalActive: Boolean): ReceiverIdentity? {
        if (!externalActive || info == null) return null
        val host = savedHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val infoHost = info.host?.trim() ?: return null
        if (!infoHost.equals(host, ignoreCase = true)) return null
        if (info.model == null && info.serial == null && info.firmware == null && info.name == null) return null
        val model = EmlidReachModel.fromReportedModel(info.model)
        return ReceiverIdentity(
            host = host,
            reportedModel = info.model,
            model = model,
            recognizedProfile = profileFor(model),
            name = info.name,
            serial = info.serial,
            firmware = info.firmware,
        )
    }

    /** What to do about the stored profile given the identity. */
    sealed interface Decision {
        data object None : Decision
        /** Replace the profile with [profile] (origin RECEIVER); connection settings stay as they are. */
        data class AutoApply(val profile: ExternalReceiverProfile) : Decision
        /** Show [mismatch]; change nothing. */
        data class Mismatch(val mismatch: ProfileMismatch) : Decision
    }

    /** Key for a dismissed ("Keep") mismatch: the same receiver report against the same profile. */
    fun dismissKey(host: String, reportedModel: String, currentProfile: ExternalReceiverProfile): String =
        "${host.lowercase(Locale.ROOT)}|${reportedModel.trim().lowercase(Locale.ROOT)}|${currentProfile.prefKey}"

    /**
     * - No verified model, or no app profile for it, or it equals [currentProfile] → [Decision.None] (an unknown or
     *   unrecognised receiver keeps the manual profile).
     * - A capture is running → [Decision.Mismatch] (a profile change stops a capture: never automatic).
     * - [origin] is USER → [Decision.Mismatch], unless the user already chose "Keep" for this report ([dismissed]).
     * - Otherwise (origin DEFAULT or RECEIVER) → [Decision.AutoApply].
     */
    fun decide(
        identity: ReceiverIdentity?,
        currentProfile: ExternalReceiverProfile,
        origin: ProfileOrigin,
        captureActive: Boolean,
        dismissed: Set<String> = emptySet(),
    ): Decision {
        val id = identity ?: return Decision.None
        val reported = id.recognizedProfile ?: return Decision.None
        val reportedModel = id.reportedModel ?: return Decision.None
        if (!id.modelVerified || reported == currentProfile) return Decision.None
        fun mismatch(reason: MismatchReason) =
            Decision.Mismatch(ProfileMismatch(id.host, reportedModel, reported, currentProfile, reason))
        return when {
            captureActive -> mismatch(MismatchReason.CAPTURE_ACTIVE)
            origin == ProfileOrigin.USER ->
                if (dismissKey(id.host, reportedModel, currentProfile) in dismissed) Decision.None
                else mismatch(MismatchReason.PROFILE_SET_BY_USER)
            else -> Decision.AutoApply(reported)
        }
    }
}
