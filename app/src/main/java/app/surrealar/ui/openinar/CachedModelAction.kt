package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.ar.model.ModelPlacement

/** What a new preload request means for a model that is already cached for the same coordinate. */
internal enum class CachedModelAction {
    /** Same file, same placement: nothing to do. */
    NONE,
    /** Same file, new placement (scale / rotation / origin / offsets): recompute the transform, no reload. */
    UPDATE_PLACEMENT,
    /** A different file (the coordinate was relinked to another model): drop the cached GLB and load the new one. */
    RELOAD,
}

/**
 * Decides how [ArFilamentRenderer.preload] treats a coordinate whose model is already cached. The cache
 * used to be keyed by coordinate id alone, so a relinked point kept rendering its old model and a
 * placement edit kept the old transform until AR was reopened.
 */
internal fun cachedModelAction(
    cachedPath: String, cachedPlacement: ModelPlacement,
    newPath: String, newPlacement: ModelPlacement,
): CachedModelAction = when {
    cachedPath != newPath -> CachedModelAction.RELOAD
    cachedPlacement != newPlacement -> CachedModelAction.UPDATE_PLACEMENT
    else -> CachedModelAction.NONE
}
