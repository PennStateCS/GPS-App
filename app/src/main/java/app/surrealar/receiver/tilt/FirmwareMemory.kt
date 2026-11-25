package app.surrealar.receiver.tilt

import app.surrealar.settings.model.ExternalReceiverProfile
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo

/**
 * The receiver firmware the tilt rules use: the last non-null firmware reported for the current receiver host and
 * profile.
 *
 * Why: the Emlid device re-polls its device info every 30 s and publishes `firmware = null` when one poll fails
 * (Wi-Fi hiccup). Reading the firmware live would turn an ENABLED-output RS4 epoch from "pole tip" into "firmware
 * unknown → may be compensated" for 30 s and stop a pole-tip occupation. Firmware does not change while the
 * receiver runs, so a failed poll keeps the last known value. It is forgotten when the receiver host reported by the
 * device info changes (another receiver) or the profile changes. A null device info (device stopped / restarting)
 * keeps it too; the next info from another host resets it.
 *
 * Not thread-safe: call from one collector (ReceiverModule combines the flows).
 */
class FirmwareMemory {
    private var profile: ExternalReceiverProfile? = null
    private var host: String? = null
    private var firmware: String? = null

    /** Feeds the latest [info] and [profile]; returns the firmware to use (null = not known for this receiver). */
    fun update(info: ReceiverDeviceInfo?, profile: ExternalReceiverProfile?): String? {
        if (profile != this.profile) {
            this.profile = profile
            host = null
            firmware = null
        }
        if (info != null) {
            val h = info.host?.trim()?.takeIf { it.isNotEmpty() }
            if (h != null && h != host) {
                host = h
                firmware = null
            }
            info.firmware?.trim()?.takeIf { it.isNotEmpty() }?.let { firmware = it }
        }
        return firmware
    }
}
