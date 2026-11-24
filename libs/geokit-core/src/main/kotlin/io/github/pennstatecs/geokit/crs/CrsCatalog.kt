package io.github.pennstatecs.geokit.crs

/** Whether a catalog can be used. */
public enum class CrsCatalogStatus {
    /** At least one operational definition. */
    SUPPORTED,

    /** No operational definition (see [CrsCatalog.explanation]). */
    UNSUPPORTED,
}

/**
 * One catalog of a [CrsRegistry].
 * @property id the catalog. @property dataset provenance of the catalog's definitions.
 * @property entries every definition, operational or not, in catalog order.
 * @property problems lines a catalog file could not load (empty for built-in catalogs).
 * @property explanation why the catalog is [CrsCatalogStatus.UNSUPPORTED], null when supported.
 */
public class CrsCatalog internal constructor(
    public val id: CrsCatalogId,
    public val dataset: String,
    public val entries: List<CrsDefinition>,
    public val problems: List<String>,
    unsupportedExplanation: String,
) {
    /** The operational definitions only. */
    public val operational: List<CrsDefinition> = entries.filter { it.operational }

    /** [CrsCatalogStatus.SUPPORTED] if the catalog has operational definitions. */
    public val status: CrsCatalogStatus =
        if (operational.isEmpty()) CrsCatalogStatus.UNSUPPORTED else CrsCatalogStatus.SUPPORTED

    public val explanation: String? = if (status == CrsCatalogStatus.UNSUPPORTED) unsupportedExplanation else null

    override fun toString(): String = "CrsCatalog(${id.id}, ${operational.size}/${entries.size} operational, $status)"
}
