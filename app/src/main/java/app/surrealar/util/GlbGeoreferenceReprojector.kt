package app.surrealar.util

import app.surrealar.domain.model.EmbeddedModelLocation
import app.surrealar.util.diagnostics.DiagTags
import java.io.File
import io.github.pennstatecs.geokit.models.georef.GlbGeoreferenceReprojector as Reprojector

/**
 * Rewrites a GLB whose POSITION vertices are baked WGS84 coordinates into a local ENU metric frame
 * recentred on the model's origin, in place, so the thumbnail, 3D viewer and AR renderer can display
 * it. The rewrite lives in geokit-models ([Reprojector]: output convention east → −X, north → +Z,
 * marker `wgs84-enu-north+z-v2`); this wrapper does the file I/O and the app's log lines (never origin
 * coordinates) and maps the origin to [EmbeddedModelLocation].
 *
 * Must be called off the main thread. Returns null (and leaves the file untouched) when the file is not
 * a plausibly georeferenced single-buffer GLB, so ordinary models are never altered.
 */
object GlbGeoreferenceReprojector {

    /** asset.extras key/value written into reprojected GLBs (WGS84 ENU, north = model +Z). */
    const val REPROJECTION_MARKER_KEY = Reprojector.REPROJECTION_MARKER_KEY
    const val REPROJECTION_MARKER_VALUE = Reprojector.REPROJECTION_MARKER_VALUE

    fun reprojectIfGeoreferenced(file: File): EmbeddedModelLocation? {
        if (!file.exists() || !file.canRead()) return null
        if (!Reprojector.isCandidate(file.name, file.length())) return null
        return try {
            val result = Reprojector.reproject(file.readBytes())
            result.precision?.let { p ->
                DiagnosticsLogger.w(DiagTags.MODEL, "Model reprojection precision limited: float32 degree vertices " +
                    "file=${file.name} latResM=${"%.1f".format(p.latResM)} lonResM=${"%.1f".format(p.lonResM)}")
            }
            when (result) {
                is Reprojector.Result.Reprojected -> {
                    file.writeBytes(result.bytes)
                    val confidence = result.confidence.toDomain()
                    DiagnosticsLogger.i(DiagTags.MODEL, "Model reprojected to local ENU file=${file.name} " +
                        "georeferenced=true method=${result.method} confidence=$confidence")   // no origin coordinates
                    EmbeddedModelLocation(
                        latitude = result.origin.latitudeDeg,
                        longitude = result.origin.longitudeDeg,
                        altitudeMeters = result.origin.heightM,
                        confidence = confidence,
                        source = result.method
                    )
                }
                is Reprojector.Result.Failed -> {
                    DiagnosticsLogger.w(DiagTags.MODEL, "Model reprojection failed file=${file.name}", result.error)
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.MODEL, "Model reprojection failed file=${file.name}", e)
            null
        }
    }
}
