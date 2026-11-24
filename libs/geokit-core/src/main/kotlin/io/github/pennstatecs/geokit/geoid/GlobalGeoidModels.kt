package io.github.pennstatecs.geokit.geoid

/**
 * The global geoid models bundled with geokit-core, built from NGA's grids as distributed by PROJ-data
 * (generators: libs/geokit-core/tools/geoid). Both are on WGS84; heights are "EGM2008" / "EGM96" heights,
 * never NAVD88.
 *
 * - [egm2008]: EGM2008 2.5′ in 1° tiles. Bundled: US-region packs only ([EGM2008_BUNDLED_REGIONS]); every
 *   other tile is [GeoidUnavailableReason.TILE_NOT_AVAILABLE] unless the app supplies it through
 *   `extraSources` (e.g. SHA-256-verified pack files from [GeoidTilePack.fromManifest]).
 * - [egm96]: EGM96 15′, the whole globe (5° tiles). A coarse fallback: use it only when explicitly chosen,
 *   and label heights "H (EGM96)".
 *
 * Every call returns a new model with its own cache; create one per process and share it.
 */
public object GlobalGeoidModels {

    /** Bundled EGM2008 packs: classpath resource → SHA-256 (verified when loaded). */
    public val EGM2008_BUNDLED_PACKS: Map<String, String> = linkedMapOf(
        "geoid/egm2008_conus.srgp" to "ff0b3bada4988c0b27d20db93a169b2219e18a166b9d00778eff8d06e00ecdda",
        "geoid/egm2008_alaska.srgp" to "ea6088cdbac0d2b493fb591d9b97d40253abfcfe55b479fcfda3beae7354cf2e",
        "geoid/egm2008_hawaii.srgp" to "020e022ceedce965167aef8e20bffc2121da3720e8f9f9c70e8bcfe84eea3052",
        "geoid/egm2008_prvi.srgp" to "4a428de34413eae5b23909a4080b0a596c43e4396d68959d0e6f2a801dee64ca",
        "geoid/egm2008_guam_cnmi.srgp" to "ed697f8d186745ee656cd73f7e162ca69764795055146a852396ac3454166000",
    )

    /** Human-readable extent of the bundled EGM2008 tiles (whole 1° tiles). */
    public const val EGM2008_BUNDLED_REGIONS: String =
        "CONUS 24–50 °N, 125–66 °W; Alaska incl. Aleutians 51–72 °N, 172 °E–129 °W (across 180°); " +
            "Hawaii (main islands) 18–23 °N, 161–154 °W; Puerto Rico / USVI 17–19 °N, 68–64 °W; " +
            "Guam / CNMI 13–21 °N, 144–146 °E"

    /** Bundled EGM96 pack (whole globe) and its SHA-256. */
    public const val EGM96_RESOURCE: String = "geoid/egm96_global.srgp"
    public const val EGM96_SHA256: String = "b1d924d9d14bbb5a431375ddeeccff941ff09fce0a3078a0a2c5b156b3f62364"

    private const val EGM2008_SOURCE_SHA256 = "4191d471eefebf24091b56dbc604353cb3b8cf8cc70e448bb9ae56a272bef17a"
    private const val EGM96_SOURCE_SHA256 = "db493027562c9b004d7220fa881f5603adada4e1c5029b933fa7de4547b0e78d"
    private const val NGA_LICENCE = "NGA, Public Domain (PROJ-data us_nga_README.txt)"

    /** Metadata of the EGM2008 model. */
    public val EGM2008_INFO: GeoidModelInfo = GeoidModelInfo(
        id = GeoidModelId.EGM2008,
        dataVersion = "EGM2008 2.5' us_nga_egm08_25 (SRGP v1, 1° tiles)",
        label = "EGM2008",
        horizontalFrame = "WGS84",
        verticalDatum = "EGM2008",
        verticalCrsEpsg = 3855,
        resolutionArcMinutes = 2.5,
        interpolation = GeoidInterpolation.BILINEAR,
        coverage = "Global model; bundled tiles: $EGM2008_BUNDLED_REGIONS",
        provenance = GeoidProvenance(
            sourceUrl = "https://cdn.proj.org/us_nga_egm08_25.tif",
            sourceFile = "us_nga_egm08_25.tif",
            sourceSha256 = EGM2008_SOURCE_SHA256,
            licence = NGA_LICENCE,
            bundledSha256 = EGM2008_BUNDLED_PACKS,
        ),
        isFallback = false,
    )

    /** Metadata of the EGM96 fallback model. */
    public val EGM96_INFO: GeoidModelInfo = GeoidModelInfo(
        id = GeoidModelId.EGM96,
        dataVersion = "EGM96 15' us_nga_egm96_15 (SRGP v1, 5° tiles)",
        label = "EGM96",
        horizontalFrame = "WGS84",
        verticalDatum = "EGM96",
        verticalCrsEpsg = 5773,
        resolutionArcMinutes = 15.0,
        interpolation = GeoidInterpolation.BILINEAR,
        coverage = "Global (bundled in full)",
        provenance = GeoidProvenance(
            sourceUrl = "https://cdn.proj.org/us_nga_egm96_15.tif",
            sourceFile = "us_nga_egm96_15.tif",
            sourceSha256 = EGM96_SOURCE_SHA256,
            licence = NGA_LICENCE,
            bundledSha256 = mapOf(EGM96_RESOURCE to EGM96_SHA256),
        ),
        isFallback = true,
    )

    /**
     * EGM2008 2.5′ from the bundled US packs plus [extraSources] (tiles the app installed; they must be
     * EGM2008 SRGP packs with 24 nodes/degree and 1° tiles). Data loads on the first lookup.
     */
    public fun egm2008(
        extraSources: List<GeoidTileSource> = emptyList(),
        cacheLimits: GeoidTileCacheLimits = GeoidTileCacheLimits(),
    ): TiledGeoidModel = TiledGeoidModel(EGM2008_INFO, cacheLimits) {
        CompositeGeoidTileSource(EGM2008_BUNDLED_PACKS.map { (path, sha) -> classpathPack(path, sha) } + extraSources)
    }

    /** EGM96 15′ over the whole globe — the explicitly labelled fallback. Data loads on the first lookup. */
    public fun egm96(cacheLimits: GeoidTileCacheLimits = GeoidTileCacheLimits()): TiledGeoidModel =
        TiledGeoidModel(EGM96_INFO, cacheLimits) { classpathPack(EGM96_RESOURCE, EGM96_SHA256) }

    /** GEOID18 from the bundled grid (loaded on the first lookup); numbers identical to [Geoid18Grid]. */
    public fun geoid18(): Geoid18Model = Geoid18Model { Geoid18Grid.fromClasspath() }

    private fun classpathPack(path: String, sha256: String): GeoidTilePack {
        val bytes = GlobalGeoidModels::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
            ?: throw IllegalStateException("geoid resource $path not found on the classpath (check packaging excludes)")
        return GeoidTilePack.fromBytes(bytes, sha256)
    }
}
