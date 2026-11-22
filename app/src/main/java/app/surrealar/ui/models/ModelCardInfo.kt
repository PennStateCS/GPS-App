package app.surrealar.ui.models

import app.surrealar.domain.model.Model
import app.surrealar.ui.models.readiness.ReadinessReport
import app.surrealar.ui.models.readiness.Verdict
import java.util.Locale

/** Pure helpers behind the badges and subtitle on a model card. */
object ModelCardInfo {

    private const val LEGACY_ICON_PREFIX = "model:"

    /**
     * Counts, per model id, the coordinates that link it — through the `modelId` column or the
     * legacy `icon = "model:<id>"` value. Matches `CoordinateDao.countByModelId` (a coordinate that
     * names the same model both ways counts once).
     */
    fun linkedCounts(links: List<Pair<String?, String?>>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for ((modelId, icon) in links) {
            val fromIcon = icon?.takeIf { it.startsWith(LEGACY_ICON_PREFIX) }
                ?.removePrefix(LEGACY_ICON_PREFIX)?.takeIf { it.isNotEmpty() }
            val ids = setOfNotNull(modelId?.takeIf { it.isNotEmpty() }, fromIcon)
            for (id in ids) counts[id] = (counts[id] ?: 0) + 1
        }
        return counts
    }

    /** True when the user kept the default name, i.e. the file name without its extension. */
    fun nameRepeatsFileName(model: Model): Boolean =
        model.name.trim().equals(model.fileName.substringBeforeLast('.'), ignoreCase = false)

    /** "GLB · 119.4 KB" for a model file name + size. */
    fun typeAndSizeLabel(fileName: String, sizeBytes: Long): String {
        val ext = fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val size = formatSize(sizeBytes)
        return if (ext.isEmpty()) size else "$ext · $size"
    }

    fun formatSize(sizeBytes: Long): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$sizeBytes B"
        }
    }

    /** Model id → verdict, for the readiness chip. */
    fun verdicts(reports: Map<String, ReadinessReport>): Map<String, Verdict> =
        reports.mapValues { it.value.verdict }

    fun isGeoreferenced(model: Model): Boolean =
        model.embeddedLatitude != null && model.embeddedLongitude != null
}
