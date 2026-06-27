package app.surrealar.data.health

import app.surrealar.data.local.dao.CoordinateDao
import app.surrealar.data.local.dao.ModelDao
import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.data.local.entity.ModelEntity
import app.surrealar.domain.model.CaptureMethod
import app.surrealar.domain.model.CoordinateModelLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/** Severity of a single health finding. */
enum class HealthSeverity { ERROR, WARNING }

/** One actionable health finding, including the offending record's name/id. */
data class HealthIssue(
    val severity: HealthSeverity,
    val category: String,
    val message: String
)

/** Result of a data health scan: totals plus the list of findings. */
data class DataHealthReport(
    val coordinateCount: Int,
    val modelCount: Int,
    val issues: List<HealthIssue>
) {
    val errorCount: Int get() = issues.count { it.severity == HealthSeverity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == HealthSeverity.WARNING }

    /** Human-readable, grouped report suitable for a log or a dialog. */
    fun format(): String = buildString {
        appendLine("Data Health Report")
        appendLine("Coordinates: $coordinateCount   Models: $modelCount")
        appendLine("Errors: $errorCount   Warnings: $warningCount")
        if (issues.isEmpty()) {
            appendLine()
            appendLine("No issues found. ✅")
            return@buildString
        }
        issues.groupBy { it.category }.toSortedMap().forEach { (category, items) ->
            appendLine()
            appendLine("── $category (${items.size}) ──")
            items.forEach { appendLine("  [${it.severity}] ${it.message}") }
        }
    }
}

/**
 * Developer/diagnostic scan over the coordinate and model tables plus their files on disk.
 *
 * This is **read-only** — it never modifies or deletes data. It is intended for debug/dev use
 * (the caller is responsible for gating it to debug builds).
 */
class DataHealthChecker @Inject constructor(
    private val coordinateDao: CoordinateDao,
    private val modelDao: ModelDao
) {

    /**
     * Reads all coordinates and models and returns a [DataHealthReport] of detected issues. Runs on
     * the IO dispatcher and only reads — it never repairs or deletes anything; acting on the findings
     * is the caller's responsibility.
     */
    suspend fun check(): DataHealthReport = withContext(Dispatchers.IO) {
        analyze(coordinateDao.getAllCoordinatesList(), modelDao.getAllModelsList())
    }

    /**
     * Pure, read-only analysis over already-loaded records. Extracted from [check] so it can be
     * unit-tested without DAOs. Touches the filesystem only to check model file/thumbnail existence.
     */
    fun analyze(coords: List<CoordinateEntity>, models: List<ModelEntity>): DataHealthReport {
        val modelIds = models.mapTo(HashSet()) { it.id }
        val now = System.currentTimeMillis()
        val issues = mutableListOf<HealthIssue>()

        for (c in coords) {
            val tag = "'${c.name}' (${c.id})"

            val latOk = c.latitude.isFinite() && c.latitude in -90.0..90.0
            val lonOk = c.longitude.isFinite() && c.longitude in -180.0..180.0
            if (!latOk || !lonOk) {
                issues += HealthIssue(HealthSeverity.ERROR, "Coordinate position",
                    "$tag has out-of-range lat/lon (${c.latitude}, ${c.longitude})")
            } else if (abs(c.latitude) < NULL_ISLAND_EPS && abs(c.longitude) < NULL_ISLAND_EPS) {
                issues += HealthIssue(HealthSeverity.ERROR, "Coordinate position",
                    "$tag is at 0,0 (null island)")
            }

            // altitude sanity (ellipsoidal height, metres)
            if (!c.altitude.isFinite()) {
                issues += HealthIssue(HealthSeverity.ERROR, "Coordinate altitude",
                    "$tag has a non-finite altitude (${c.altitude})")
            } else if (c.altitude == 0.0 && c.altitudeMsl == null && c.geoidSeparationM == null) {
                // Exact 0.0 with no MSL/geoid metadata is the classic "missing altitude saved as zero"
                // placeholder rather than a real sea-level measurement.
                issues += HealthIssue(HealthSeverity.WARNING, "Coordinate altitude",
                    "$tag has exactly 0.0 m altitude and no MSL/geoid metadata (possible missing-altitude placeholder)")
            }
            // h = H + N consistency when all three are present.
            val msl = c.altitudeMsl; val sep = c.geoidSeparationM
            if (c.altitude.isFinite() && msl != null && sep != null && msl.isFinite() && sep.isFinite()) {
                if (abs(c.altitude - (msl + sep)) > GEOID_TOLERANCE_M) {
                    issues += HealthIssue(HealthSeverity.WARNING, "Coordinate altitude",
                        "$tag fails h = H + N: altitude=${c.altitude}, MSL=$msl, geoidSep=$sep")
                }
            }

            // captureMethod present and recognized
            if (c.captureMethod.isNullOrBlank()) {
                issues += HealthIssue(HealthSeverity.WARNING, "Coordinate provenance",
                    "$tag has no captureMethod")
            } else if (CaptureMethod.fromStorage(c.captureMethod) == null) {
                issues += HealthIssue(HealthSeverity.WARNING, "Coordinate provenance",
                    "$tag has unrecognized captureMethod '${c.captureMethod}'")
            }
            // provider that couldn't be classified
            if (c.provider.name == "OTHER") {
                issues += HealthIssue(HealthSeverity.WARNING, "Coordinate provenance",
                    "$tag has unclassified provider (OTHER)")
            }

            // timestamps
            if (c.timestamp <= 0L) {
                issues += HealthIssue(HealthSeverity.WARNING, "Coordinate timestamp",
                    "$tag has a missing/zero timestamp")
            } else if (c.timestamp > now + FUTURE_SKEW_MS) {
                issues += HealthIssue(HealthSeverity.WARNING, "Coordinate timestamp",
                    "$tag has a future timestamp (${c.timestamp})")
            }

            // model link integrity
            val linkedId = CoordinateModelLink.resolveModelId(c.modelId, c.icon)
            if (linkedId != null && linkedId !in modelIds) {
                issues += HealthIssue(HealthSeverity.ERROR, "Model link",
                    "$tag links missing model id '$linkedId'")
            }
            if (c.modelId.isNullOrBlank() && c.icon.startsWith(CoordinateModelLink.LEGACY_MODEL_ICON_PREFIX)) {
                issues += HealthIssue(HealthSeverity.WARNING, "Model link",
                    "$tag still uses legacy icon=\"model:…\" (modelId not set)")
            }

            // suspicious accuracy
            c.horizontalAccuracyM?.let { acc ->
                if (acc < 0.0 || acc > SUSPICIOUS_ACCURACY_M) {
                    issues += HealthIssue(HealthSeverity.WARNING, "Coordinate quality",
                        "$tag has suspicious horizontal accuracy ${acc}m")
                }
            }

            // per-coordinate model placement sanity
            c.modelScale?.let { s ->
                if (!s.isFinite() || s <= 0.0) {
                    issues += HealthIssue(HealthSeverity.WARNING, "Coordinate placement",
                        "$tag has invalid modelScale ($s); a non-positive scale hides the model")
                }
            }
            val angles = listOf(
                "modelYawDeg" to c.modelYawDeg, "modelPitchDeg" to c.modelPitchDeg, "modelRollDeg" to c.modelRollDeg
            )
            for ((label, v) in angles) v?.let {
                if (!it.isFinite()) issues += HealthIssue(HealthSeverity.WARNING, "Coordinate placement",
                    "$tag has a non-finite $label ($it)")
            }
            val offsets = listOf(
                "modelVerticalOffsetM" to c.modelVerticalOffsetM,
                "modelOriginOffsetXM" to c.modelOriginOffsetXM,
                "modelOriginOffsetYM" to c.modelOriginOffsetYM,
                "modelOriginOffsetZM" to c.modelOriginOffsetZM
            )
            for ((label, v) in offsets) v?.let {
                if (!it.isFinite()) {
                    issues += HealthIssue(HealthSeverity.WARNING, "Coordinate placement",
                        "$tag has a non-finite $label ($it)")
                } else if (abs(it) > SUSPICIOUS_OFFSET_M) {
                    issues += HealthIssue(HealthSeverity.WARNING, "Coordinate placement",
                        "$tag has a suspicious $label (${it}m)")
                }
            }
        }

        for (m in models) {
            val tag = "'${m.name}' (${m.id})"
            if (m.filePath.isBlank()) {
                issues += HealthIssue(HealthSeverity.ERROR, "Model file",
                    "$tag has a blank file path")
            } else if (!File(m.filePath).exists()) {
                issues += HealthIssue(HealthSeverity.ERROR, "Model file",
                    "$tag file is missing on disk: ${m.filePath}")
            }
            if (m.thumbnailFilePath.isNullOrBlank()) {
                issues += HealthIssue(HealthSeverity.WARNING, "Model thumbnail",
                    "$tag has no thumbnail")
            } else if (!File(m.thumbnailFilePath).exists()) {
                issues += HealthIssue(HealthSeverity.WARNING, "Model thumbnail",
                    "$tag thumbnail is missing on disk: ${m.thumbnailFilePath}")
            }
            if (m.defaultScale <= 0.0 || m.defaultScale > SUSPICIOUS_SCALE) {
                issues += HealthIssue(HealthSeverity.WARNING, "Model placement",
                    "$tag has a suspicious defaultScale (${m.defaultScale})")
            }
            if (abs(m.originOffsetXM) > SUSPICIOUS_OFFSET_M ||
                abs(m.originOffsetYM) > SUSPICIOUS_OFFSET_M ||
                abs(m.originOffsetZM) > SUSPICIOUS_OFFSET_M) {
                issues += HealthIssue(HealthSeverity.WARNING, "Model placement",
                    "$tag has a suspicious origin offset " +
                        "(${m.originOffsetXM}, ${m.originOffsetYM}, ${m.originOffsetZM})")
            }
        }

        return DataHealthReport(coords.size, models.size, issues)
    }

    private companion object {
        const val NULL_ISLAND_EPS = 1e-7
        const val FUTURE_SKEW_MS = 24L * 60 * 60 * 1000   // 1 day clock-skew allowance
        const val SUSPICIOUS_ACCURACY_M = 50.0
        const val GEOID_TOLERANCE_M = 1.0                 // h = H + N agreement tolerance
        const val SUSPICIOUS_SCALE = 1000.0
        const val SUSPICIOUS_OFFSET_M = 10_000.0
    }
}
