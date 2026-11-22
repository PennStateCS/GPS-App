package app.surrealar.ui.models.readiness

import app.surrealar.ui.openinar.Vec3
import java.io.File

/*
 * Model readiness for AR: "is this model ready to show at its surveyed point?"
 *
 * Contract shared by the evaluator, the glTF reader and the UI. The evaluator places the model the way
 * AR does (ui/openinar/ModelBounds + ModelPlacementOrigin: CENTER is the default preset) so every
 * finding describes what the user will actually see outside, not an abstract origin rule.
 */

enum class CheckStatus { PASS, INFO, WARN, FAIL }

enum class Verdict { READY, READY_WITH_WARNINGS, NOT_READY }

/** A one-tap fix the UI can offer next to a finding. */
enum class FixAction {
    NONE,
    /** Set the linked point's placement preset to BOTTOM_CENTER. */
    USE_BOTTOM_CENTER,
    /** Set the linked point's vertical offset to [ReadinessCheck.fixValue] metres. */
    SET_VERTICAL_OFFSET,
    /** Rotate the model so a Z-up export stands up (pitch -90° on the linked point). */
    ROTATE_UP_AXIS,
    /** Open the coordinate list so the user can link this model to a point. */
    LINK_POINT,
}

data class ReadinessCheck(
    /** Stable id (tests, analytics), e.g. "placement", "units", "complexity". */
    val id: String,
    val title: String,
    val status: CheckStatus,
    /** One plain sentence: what was found. Never contains coordinates. */
    val detail: String,
    /** One plain sentence: how to fix it (null when nothing to do). */
    val fix: String? = null,
    val fixAction: FixAction = FixAction.NONE,
    /** Coordinate the fix applies to (placement fixes), when the finding is about one linked point. */
    val fixCoordinateId: String? = null,
    /** Numeric argument for the fix (e.g. vertical offset metres). */
    val fixValue: Double? = null,
)

data class ReadinessReport(
    val verdict: Verdict,
    /** Ordered: FAIL first, then WARN, INFO, PASS; stable within a status. */
    val checks: List<ReadinessCheck>,
    /** e.g. "Ready for AR", "Ready with 2 warnings", "Not ready: 1 problem". */
    val summary: String,
)

/** Model-local axis-aligned bounds (metres, glTF Y-up), before any app scale. */
data class Bounds(val min: Vec3, val max: Vec3)

/** Facts read from the glTF/GLB file itself (no rendering). Null fields = could not be determined. */
data class GltfFacts(
    val fileSizeBytes: Long,
    /** Bounds of all mesh geometry with node transforms applied; null if unreadable. */
    val bounds: Bounds?,
    val triangleCount: Long?,
    val vertexCount: Long?,
    val meshCount: Int?,
    val textureCount: Int?,
    /** Largest texture side in pixels (from PNG/JPEG headers); null if unknown. */
    val maxTextureSidePx: Int?,
    /** Rough GPU memory for all textures: Σ w·h·4·1.33 (mipmaps). */
    val estimatedTextureBytes: Long?,
    val extensionsRequired: List<String>,
    val extensionsUsed: List<String>,
    /** Relative URIs a .gltf references that don't exist next to it. */
    val missingResources: List<String>,
    /** Set when the file could not be parsed at all. */
    val parseError: String? = null,
)

/** One point that links the model, with its placement settings (as AR reads them). */
data class LinkFacts(
    val coordinateId: String,
    val coordinateName: String,
    /** Stored ModelPlacementOrigin token (null/unknown = CENTER, the default). */
    val placementOrigin: String?,
    val scale: Double?,
    val yawDeg: Double?,
    val pitchDeg: Double?,
    val rollDeg: Double?,
    val verticalOffsetM: Double?,
    val originOffsetXM: Double?,
    val originOffsetYM: Double?,
    val originOffsetZM: Double?,
    val rtkStatus: String?,
    val horizontalAccuracyM: Double?,
    /** Used only to compare with the model's embedded location; never shown or logged. */
    val latDeg: Double,
    val lonDeg: Double,
)

data class ModelFacts(
    val modelId: String,
    val modelName: String,
    val fileName: String,
    val gltf: GltfFacts,
    /** Bounds from the loaded Filament asset (viewer); preferred over [GltfFacts.bounds] when present. */
    val renderedBounds: Bounds? = null,
    /** True when Filament loaded the asset (viewer); null when not attempted (card/import). */
    val loadedByRenderer: Boolean? = null,
    val embeddedLatDeg: Double?,
    val embeddedLonDeg: Double?,
    val isValid: Boolean,
    val validationErrors: List<String>,
    val units: String?,
    /** The model's default scale (used when a link has no per-point scale). */
    val defaultScale: Double?,
    val links: List<LinkFacts>,
)

/** Pure rules: facts in, report out. Implemented in ModelReadinessEvaluator.kt. */
interface ReadinessRules {
    fun evaluate(facts: ModelFacts): ReadinessReport
}

/** Reads [GltfFacts] from a .glb or .gltf file. Implemented in GltfFactsReader.kt. Never throws. */
interface GltfFactsSource {
    fun read(file: File): GltfFacts
}
