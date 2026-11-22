package app.surrealar.util.diagnostics

import app.surrealar.util.DiagnosticsLogger
import java.io.File
import java.util.Locale

/**
 * ar-track.csv (debug builds only): ARCore's own camera pose next to the GNSS fix while the AR screen
 * is open, for working out how ARCore's local frame lines up with RTK (heading and offset alignment).
 *
 * One row per new fix (at most every [MIN_INTERVAL_MS]), plus a camera-only row at least every
 * [MAX_STALE_MS] when fixes stop. Positions are metres east/north/up from the first fix of the AR
 * session, never latitude/longitude, so the file stays coordinate-free like the rest of the report.
 * Every number has at most 5 decimals, so [app.surrealar.util.diagnostics.report.ReportRedactor]
 * leaves it intact.
 */
object ArTrackLog {
    const val FILE = "ar-track.csv"
    const val ROTATED = "ar-track-1.csv"
    const val MAX_BYTES = 3L * 1024 * 1024
    /** At most 10 rows a second (the receiver's fix rate is 5-10 Hz). */
    const val MIN_INTERVAL_MS = 100L
    /** A row at least once a second even with no new fix, so camera-only stretches are visible. */
    const val MAX_STALE_MS = 1_000L

    val COLUMNS: List<String> = listOf(
        "mono_ms", "frame_ns", "cam_state",
        "cam_x", "cam_y", "cam_z", "cam_qx", "cam_qy", "cam_qz", "cam_qw",
        "earth_state", "geo_e", "geo_n", "geo_u", "geo_heading_deg", "geo_hacc_m", "geo_vacc_m", "geo_yawacc_deg",
        "fix_new", "fix_age_ms", "fix_utc_ms", "provider", "rtk",
        "fix_e", "fix_n", "fix_u", "fix_hacc_m", "fix_vacc_m", "sats", "diff_age_s", "speed_mps", "course_deg",
        "antenna_above_cam_m",
    )
    val HEADER: String = COLUMNS.joinToString(",")

    fun file(dir: File? = DiagnosticsLogger.diagnosticsDir()): File? = dir?.let { File(it, FILE) }

    /** Current file then the rotated one, those that exist and have content. */
    fun files(dir: File? = DiagnosticsLogger.diagnosticsDir()): List<File> =
        listOfNotNull(dir?.let { File(it, FILE) }, dir?.let { File(it, ROTATED) })
            .filter { it.exists() && it.length() > 0 }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ar-track-log").apply { isDaemon = true }
    }

    /**
     * Marks the start of an AR session (ARCore's local frame and the ENU origin restart here).
     * A comment line, so a CSV reader can split sessions on lines starting with '#'.
     */
    fun startSession(
        antennaAboveCameraM: Double,
        receiver: String,
        device: String,
        dir: File? = DiagnosticsLogger.diagnosticsDir(),
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val line = "# session_start ${LogLineFormat.timestamp(nowMs)} receiver=${receiver.replace(',', ' ')} " +
            "device=${device.replace(',', ' ')} antenna_above_cam_m=${fmt(antennaAboveCameraM, 3)} " +
            "origin=first fix of this session (not recorded); cam_* in ARCore world metres (y up)"
        append(line, dir)
    }

    fun append(row: ArTrackRow, dir: File? = DiagnosticsLogger.diagnosticsDir()) = append(row.toCsv(), dir)

    private fun append(line: String, dir: File?) {
        val f = file(dir) ?: return
        runCatching { writer.execute { write(f, line) } }
    }

    /** Waits until every queued line is written (before export, and in tests). False on timeout. */
    fun flush(timeoutMs: Long = 5_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        if (runCatching { writer.execute { done.countDown() } }.isFailure) return false
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun write(f: File, line: String) {
        runCatching {
            if (f.length() > MAX_BYTES) {
                File(f.parentFile, ROTATED).delete()
                f.renameTo(File(f.parentFile, ROTATED))
            }
            if (f.length() == 0L) f.appendText(HEADER + "\n", Charsets.UTF_8)
            f.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    internal fun fmt(v: Double?, decimals: Int): String =
        if (v == null || !v.isFinite()) "" else String.format(Locale.US, "%.${decimals}f", v)
}

/**
 * One ar-track.csv row. Nulls are written as empty cells. Camera pose is ARCore's `Camera.getPose()`
 * (the physical camera, not display-oriented) in ARCore world metres; geo_* is ARCore's Earth
 * `cameraGeospatialPose` and fix_* the GNSS fix, both as metres east/north/up from the session origin.
 */
data class ArTrackRow(
    val monoMs: Long,
    val frameNs: Long,
    val camState: String,
    val camPos: FloatArray?,        // x, y, z
    val camQuat: FloatArray?,       // qx, qy, qz, qw
    val earthState: String?,
    val geoEnu: DoubleArray?,       // e, n, u
    val geoHeadingDeg: Double?,
    val geoHAccM: Double?,
    val geoVAccM: Double?,
    val geoYawAccDeg: Double?,
    val fixNew: Boolean,
    val fixAgeMs: Long?,
    val fixUtcMs: Long?,
    val provider: String?,
    val rtk: String?,
    val fixEnu: DoubleArray?,       // e, n, u (u may be NaN when the fix has no height)
    val fixHAccM: Double?,
    val fixVAccM: Double?,
    val sats: Int?,
    val diffAgeS: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val antennaAboveCameraM: Double,
) {
    fun toCsv(): String {
        val f = ArTrackLog::fmt
        val cells = listOf(
            monoMs.toString(), frameNs.toString(), camState,
            f(camPos?.get(0)?.toDouble(), 4), f(camPos?.get(1)?.toDouble(), 4), f(camPos?.get(2)?.toDouble(), 4),
            f(camQuat?.get(0)?.toDouble(), 5), f(camQuat?.get(1)?.toDouble(), 5),
            f(camQuat?.get(2)?.toDouble(), 5), f(camQuat?.get(3)?.toDouble(), 5),
            earthState.orEmpty(),
            f(geoEnu?.get(0), 3), f(geoEnu?.get(1), 3), f(geoEnu?.get(2), 3),
            f(geoHeadingDeg, 2), f(geoHAccM, 3), f(geoVAccM, 3), f(geoYawAccDeg, 2),
            if (fixNew) "1" else "0", fixAgeMs?.toString().orEmpty(), fixUtcMs?.toString().orEmpty(),
            provider.orEmpty(), rtk.orEmpty(),
            f(fixEnu?.get(0), 4), f(fixEnu?.get(1), 4), f(fixEnu?.get(2), 4),
            f(fixHAccM, 3), f(fixVAccM, 3), sats?.toString().orEmpty(),
            f(diffAgeS, 1), f(speedMps, 3), f(courseDeg, 2),
            f(antennaAboveCameraM, 3),
        )
        return cells.joinToString(",") { it.replace(',', ' ') }
    }
}
