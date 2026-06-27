package app.surrealar.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted metadata for one imported 3D model (table `models`) — not the model file itself, which
 * lives on disk and is referenced by `fileName`. Column names and types are a storage contract: a
 * coordinate links to a model by this row's `id`, so changing or dropping columns needs a matching
 * migration. Nullable columns added in later schema versions (thumbnail, embedded-location, health,
 * default placement) may be absent on rows written by older builds.
 */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val dateAdded: Long,
    val description: String? = null,

    // Thumbnail fields (nullable for backward compatibility)
    val thumbnailFileName: String? = null,
    val thumbnailFilePath: String? = null,

    // Embedded geographic origin extracted from georeferenced GLBs at import time.
    // Persisted because reprojection recenters the geometry, erasing the in-file signal —
    // these let the coordinate-linking flow still offer "Use Model Location".
    val embeddedLatitude: Double? = null,
    val embeddedLongitude: Double? = null,
    val embeddedAltitudeM: Double? = null,

    // ─── v10: model health / validation (supports a future model-health tool) ───
    val checksum: String? = null,
    @ColumnInfo(defaultValue = "1") val isValid: Boolean = true,
    val validationErrorsJson: String? = null,

    // ─── v10: default placement metadata (safe defaults; refined when computed at import) ───
    @ColumnInfo(defaultValue = "1.0") val defaultScale: Double = 1.0,
    @ColumnInfo(defaultValue = "0.0") val defaultYawDeg: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val originOffsetXM: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val originOffsetYM: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val originOffsetZM: Double = 0.0,
    val boundingBoxJson: String? = null,
    val units: String? = null
)
