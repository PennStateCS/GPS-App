package app.surrealar.util.diagnostics.report

import java.util.Locale

/** device-health.txt: battery, thermal, storage, memory, network, location, permissions. */
object DeviceHealthCollector {
    private fun yn(b: Boolean?, yes: String = "yes", no: String = "no") = when (b) { true -> yes; false -> no; null -> "unknown" }
    private fun size(b: Long?) = when {
        b == null -> "unknown"
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024))
        else -> String.format(Locale.US, "%.0f MB", b / (1024.0 * 1024))
    }

    fun render(h: DeviceHealth): String = buildString {
        appendLine("=== Device health ===")
        appendLine("--- Power ---")
        appendLine("Battery                     : ${h.batteryPercent?.let { "$it%" } ?: "unknown"} (${yn(h.charging, "charging", "not charging")})")
        appendLine("Power save                  : ${yn(h.powerSave, "on", "off")}")
        appendLine("Thermal status              : ${h.thermal ?: "unknown"}")
        appendLine("Battery optimisation ignored: ${yn(h.batteryOptimizationIgnored)}" +
            if (h.batteryOptimizationIgnored == false) " (Android may pause the app in the background)" else "")
        appendLine("--- Storage and memory ---")
        appendLine("Storage free / total        : ${size(h.storageFreeBytes)} / ${size(h.storageTotalBytes)}")
        appendLine("App data                    : ${size(h.appDataBytes)}")
        appendLine("RAM available / total       : ${size(h.ramAvailBytes)} / ${size(h.ramTotalBytes)}")
        appendLine("Low memory                  : ${yn(h.lowMemory)}")
        appendLine("--- Network and location ---")
        appendLine("Network                     : ${h.network ?: "unknown"}")
        appendLine("GPS provider                : ${yn(h.gpsProvider, "on", "off")}")
        appendLine("Network location provider   : ${yn(h.networkProvider, "on", "off")}")
        appendLine("Mock location app (dev opt.): ${h.mockLocationApp ?: "unknown"}")
        appendLine("--- Permissions ---")
        if (h.permissions.isEmpty()) appendLine("(unknown)")
        h.permissions.forEach { (k, v) -> appendLine("${k.padEnd(28)}: ${if (v) "granted" else "DENIED"}") }
    }
}