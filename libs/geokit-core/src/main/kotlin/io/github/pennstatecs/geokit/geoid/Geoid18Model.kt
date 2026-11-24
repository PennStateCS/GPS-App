package io.github.pennstatecs.geokit.geoid

/**
 * [GeoidModel] adapter over the existing [Geoid18Grid]: the numbers are exactly
 * [Geoid18Grid.undulation]'s (same code path, no re-interpolation). Look N up at the NAD83(2011)
 * position, as the conversion engine does.
 *
 * @param loader supplies the grid on first lookup (e.g. `{ Geoid18Grid.fromClasspath() }`); a failure is
 *   remembered and reported as [GeoidUnavailableReason.LOAD_FAILED].
 */
public class Geoid18Model(loader: () -> Geoid18Grid) : GeoidModel {

    /** Wraps an already loaded grid. */
    public constructor(grid: Geoid18Grid) : this({ grid }) {
        data.preset(grid)
    }

    private val data = LazyLoad(loader)

    override val info: GeoidModelInfo get() = INFO

    override val availability: GeoidAvailability
        get() = when {
            data.isLoaded -> GeoidAvailability.AVAILABLE
            data.isFailed -> GeoidAvailability.FAILED
            else -> GeoidAvailability.NOT_LOADED
        }

    /** The documented GEOID18 extent: CONUS 24–58 °N, 130–60 °W and PR/VI 15–21 °N, 69–64 °W. */
    override fun covers(latDeg: Double, lonDeg: Double): Boolean {
        if (!isValidPosition(latDeg, lonDeg)) return false
        val lon = normalizeLon(lonDeg)
        return (latDeg in 24.0..58.0 && lon in -130.0..-60.0) || (latDeg in 15.0..21.0 && lon in -69.0..-64.0)
    }

    override fun undulation(latDeg: Double, lonDeg: Double): GeoidResult {
        if (!isValidPosition(latDeg, lonDeg)) {
            return GeoidResult.Unavailable(GeoidUnavailableReason.INVALID_POSITION, GeoidModelId.GEOID18,
                "invalid position $latDeg, $lonDeg")
        }
        val grid = data.get().getOrElse {
            return GeoidResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, GeoidModelId.GEOID18,
                "GEOID18 grid could not be loaded: ${it.message}")
        }
        val n = grid.undulation(latDeg, lonDeg)
            ?: return GeoidResult.Unavailable(GeoidUnavailableReason.OUTSIDE_COVERAGE, GeoidModelId.GEOID18,
                "$latDeg, $lonDeg is outside GEOID18 (CONUS, PR/VI)")
        return GeoidResult.Value(n, GeoidModelId.GEOID18, verticalDatumName(latDeg, lonDeg))
    }

    override fun verticalDatumName(latDeg: Double, lonDeg: Double): String =
        Geoid18Grid.verticalDatumName(latDeg, lonDeg)

    public companion object {
        /** SHA-256 of the bundled packed grid [Geoid18Grid.RESOURCE_PATH]. */
        public const val RESOURCE_SHA256: String =
            "74be9cef3305285cd9e573cc7c094f00b9cf274bf7632f4a08c2faddfd13f3b8"

        /** Metadata of GEOID18 (unchanged data: g2018u0 + g2018p0 packed as SRGT v1). */
        public val INFO: GeoidModelInfo = GeoidModelInfo(
            id = GeoidModelId.GEOID18,
            dataVersion = "GEOID18 g2018u0+g2018p0 (SRGT v1)",
            label = "GEOID18",
            horizontalFrame = "NAD83(2011)",
            verticalDatum = "NAVD88 / PRVD02 / VIVD09",
            verticalCrsEpsg = null,
            resolutionArcMinutes = 1.0,
            interpolation = GeoidInterpolation.BILINEAR,
            coverage = "CONUS 24–58 °N, 130–60 °W; Puerto Rico / US Virgin Islands 15–21 °N, 69–64 °W",
            provenance = GeoidProvenance(
                sourceUrl = "https://geodesy.noaa.gov/PC_PROD/GEOID18/Format_unix/",
                sourceFile = "g2018u0.bin, g2018p0.bin",
                sourceSha256 = null,
                licence = "NOAA/NGS, US Government work (public domain)",
                bundledSha256 = mapOf(Geoid18Grid.RESOURCE_PATH to RESOURCE_SHA256),
            ),
            isFallback = false,
        )
    }
}

/** Longitude in [-180, 180). */
internal fun normalizeLon(lonDeg: Double): Double {
    val l = (lonDeg + 180.0) % 360.0
    return (if (l < 0) l + 360.0 else l) - 180.0
}
