package com.example.surveyingapp.domain.model

/**
 * Single compatibility layer for resolving a coordinate's model link and display icon.
 *
 * Since v10 the link is stored in the explicit [Coordinate.modelId] / [Coordinate.iconKey]
 * columns. Rows written before v10 only carry the legacy `icon = "model:<id>"` convention.
 * All call sites (AR, map, list, detail) must go through the helpers here instead of parsing
 * the icon string themselves, so the legacy fallback lives in exactly one place.
 */
object CoordinateModelLink {

    const val LEGACY_MODEL_ICON_PREFIX = "model:"

    /**
     * The linked model id, preferring the explicit [modelId] column and falling back to the
     * legacy icon convention. Returns null when no model is linked.
     */
    fun resolveModelId(modelId: String?, legacyIcon: String?): String? =
        modelId?.takeIf { it.isNotBlank() }
            ?: legacyIcon
                ?.takeIf { it.startsWith(LEGACY_MODEL_ICON_PREFIX) }
                ?.removePrefix(LEGACY_MODEL_ICON_PREFIX)
                ?.takeIf { it.isNotBlank() }

    /**
     * The built-in/simple icon key to display, preferring the explicit [iconKey] column and
     * falling back to the legacy icon when it is not a model reference. Returns null when a
     * model is linked (the model thumbnail is shown instead).
     */
    fun resolveIconKey(modelId: String?, iconKey: String?, legacyIcon: String?): String? {
        if (resolveModelId(modelId, legacyIcon) != null) return null
        iconKey?.takeIf { it.isNotBlank() }?.let { return it }
        return legacyIcon?.takeIf { it.isNotBlank() && !it.startsWith(LEGACY_MODEL_ICON_PREFIX) }
    }

    /** Formats a model id back into the legacy icon token (for rows that still write `icon`). */
    fun toLegacyIcon(modelId: String): String = LEGACY_MODEL_ICON_PREFIX + modelId
}

// ── Convenience accessors on the domain model ──────────────────────────────────

/** The linked model id (new column or legacy icon), or null when no model is linked. */
val Coordinate.linkedModelId: String?
    get() = CoordinateModelLink.resolveModelId(modelId, icon)

/** True when this coordinate links a 3D model (via the new column or legacy icon). */
val Coordinate.hasLinkedModel: Boolean
    get() = linkedModelId != null

/** The built-in icon key to display, or null when a model is linked. */
val Coordinate.displayIconKey: String?
    get() = CoordinateModelLink.resolveIconKey(modelId, iconKey, icon)
