package app.surrealar.ui.models.readiness

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.Model
import app.surrealar.domain.model.linkedModelId
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.repository.ModelRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers [ModelFacts] for a model (its row, the points that link it, and what the glTF file says)
 * and runs the readiness rules on them. Also applies the one-tap placement fixes the checklist offers.
 *
 * File facts are cached by path + last-modified + size, so re-evaluating after a placement edit or
 * for the model cards does not re-read the file.
 */
@Singleton
class ModelReadinessRepository internal constructor(
    private val models: ModelRepository,
    private val coordinates: CoordinateRepository,
    private val gltfSource: GltfFactsSource,
    private val rules: ReadinessRules,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {

    @Inject
    constructor(models: ModelRepository, coordinates: CoordinateRepository) : this(
        models, coordinates, GltfFactsReader, ModelReadinessEvaluator, Dispatchers.IO, System::currentTimeMillis,
    )

    private val gltfCache = object : LinkedHashMap<String, GltfFacts>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GltfFacts>?): Boolean =
            size > GLTF_CACHE_SIZE
    }

    /** Facts for [modelId], or null when the model row is gone. */
    suspend fun facts(
        modelId: String,
        renderedBounds: Bounds? = null,
        loadedByRenderer: Boolean? = null,
    ): ModelFacts? = withContext(io) {
        val model = models.getModelById(modelId) ?: return@withContext null
        val links = coordinates.getAllCoordinatesList().filter { it.linkedModelId == modelId }
        factsFor(model, links, renderedBounds, loadedByRenderer)
    }

    /** Facts for a file with no model row (viewer opened without an id): file facts only, no links. */
    suspend fun factsForFile(
        filePath: String,
        displayName: String,
        renderedBounds: Bounds? = null,
        loadedByRenderer: Boolean? = null,
    ): ModelFacts = withContext(io) {
        val file = File(filePath)
        ModelFacts(
            modelId = "",
            modelName = displayName,
            fileName = file.name,
            gltf = gltfFacts(file),
            renderedBounds = renderedBounds,
            loadedByRenderer = loadedByRenderer,
            embeddedLatDeg = null,
            embeddedLonDeg = null,
            isValid = true,
            validationErrors = emptyList(),
            units = null,
            defaultScale = null,
            links = emptyList(),
        )
    }

    suspend fun report(
        modelId: String,
        renderedBounds: Bounds? = null,
        loadedByRenderer: Boolean? = null,
    ): ReadinessReport? = facts(modelId, renderedBounds, loadedByRenderer)?.let { rules.evaluate(it) }

    /**
     * Reports for every model in [allModels], given the full coordinate list (the model cards
     * recompute this whenever either table changes). Runs on the IO dispatcher.
     */
    suspend fun reports(allModels: List<Model>, allCoordinates: List<Coordinate>): Map<String, ReadinessReport> =
        withContext(io) {
            val byModel = allCoordinates.groupBy { it.linkedModelId }
            allModels.associate { m ->
                m.id to rules.evaluate(factsFor(m, byModel[m.id].orEmpty(), null, null))
            }
        }

    fun evaluate(facts: ModelFacts): ReadinessReport = rules.evaluate(facts)

    /**
     * Applies [check]'s one-tap fix to the point it names, or to every point linking [modelId] when it
     * names none. Returns the names of the points that changed (empty when nothing applied).
     */
    suspend fun applyFix(modelId: String?, check: ReadinessCheck): List<String> = withContext(io) {
        if (check.fixAction == FixAction.NONE || check.fixAction == FixAction.LINK_POINT) {
            return@withContext emptyList()
        }
        val targets = check.fixCoordinateId?.let { id -> listOfNotNull(coordinates.getById(id)) }
            ?: modelId?.let { id -> coordinates.getAllCoordinatesList().filter { it.linkedModelId == id } }
            ?: emptyList()
        val now = clock()
        val changed = mutableListOf<String>()
        for (c in targets) {
            val updated = when (check.fixAction) {
                FixAction.USE_BOTTOM_CENTER -> c.copy(modelPlacementOrigin = BOTTOM_CENTER)
                FixAction.SET_VERTICAL_OFFSET -> check.fixValue?.let { c.copy(modelVerticalOffsetM = it) }
                FixAction.ROTATE_UP_AXIS -> c.copy(
                    modelPitchDeg = if (c.modelPitchDeg == UPRIGHT_PITCH_DEG) 0.0 else UPRIGHT_PITCH_DEG,
                )
                FixAction.NONE, FixAction.LINK_POINT -> null
            } ?: continue
            coordinates.update(updated.copy(updatedAt = now))
            changed += c.name
        }
        changed
    }

    private fun factsFor(
        model: Model,
        links: List<Coordinate>,
        renderedBounds: Bounds?,
        loadedByRenderer: Boolean?,
    ): ModelFacts = ModelFacts(
        modelId = model.id,
        modelName = model.name,
        fileName = model.fileName,
        gltf = gltfFacts(File(model.filePath)),
        renderedBounds = renderedBounds,
        loadedByRenderer = loadedByRenderer,
        embeddedLatDeg = model.embeddedLatitude,
        embeddedLonDeg = model.embeddedLongitude,
        isValid = model.isValid,
        validationErrors = model.validationErrors,
        units = model.units,
        defaultScale = model.defaultScale,
        links = links.map { it.toLinkFacts() },
    )

    private fun gltfFacts(file: File): GltfFacts {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        synchronized(gltfCache) { gltfCache[key]?.let { return it } }
        val facts = gltfSource.read(file)
        synchronized(gltfCache) { gltfCache[key] = facts }
        return facts
    }

    internal companion object {
        const val BOTTOM_CENTER = "BOTTOM_CENTER"
        const val UPRIGHT_PITCH_DEG = -90.0
        const val GLTF_CACHE_SIZE = 16

        fun Coordinate.toLinkFacts(): LinkFacts = LinkFacts(
            coordinateId = id,
            coordinateName = name,
            placementOrigin = modelPlacementOrigin,
            scale = modelScale,
            yawDeg = modelYawDeg,
            pitchDeg = modelPitchDeg,
            rollDeg = modelRollDeg,
            verticalOffsetM = modelVerticalOffsetM,
            originOffsetXM = modelOriginOffsetXM,
            originOffsetYM = modelOriginOffsetYM,
            originOffsetZM = modelOriginOffsetZM,
            latDeg = latitude,
            lonDeg = longitude,
        )
    }
}
