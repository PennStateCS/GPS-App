package app.surrealar.util.diagnostics.report

/** Live GNSS / receiver state at export time (no coordinates). */
data class LiveState(
    val source: String?,
    val rtk: String?,
    val receiverLink: String?,
    val correctionAgeS: Double?,
    val corrections: String?,
    val freeStorageBytes: Long?,
)
/** device-health.txt inputs (null = unknown on this device). */
data class DeviceHealth(
    val batteryPercent: Int? = null, val charging: Boolean? = null, val powerSave: Boolean? = null, val thermal: String? = null,
    val storageFreeBytes: Long? = null, val storageTotalBytes: Long? = null, val appDataBytes: Long? = null,
    val ramAvailBytes: Long? = null, val ramTotalBytes: Long? = null, val lowMemory: Boolean? = null,
    val network: String? = null, val gpsProvider: Boolean? = null, val networkProvider: Boolean? = null,
    val permissions: Map<String, Boolean> = emptyMap(),
    val batteryOptimizationIgnored: Boolean? = null, val mockLocationApp: String? = null,
)

/** gnss-stats.txt inputs. */
data class GnssStatsInput(
    val stats: app.surrealar.util.diagnostics.GnssFeedTracker.Stats?,
    val reconnects: Int?, val nmeaLines: Long?, val nmeaParseErrors: Long?, val sentenceCounts: String?,
)

/** data-health.txt inputs. */
data class DataHealth(val version: Int?, val rowCounts: Map<String, Long>, val integrity: String)