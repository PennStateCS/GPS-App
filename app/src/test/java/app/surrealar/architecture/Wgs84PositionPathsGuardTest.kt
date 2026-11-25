package app.surrealar.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4 WP C (docs/geodesy/phase4-position-references.md): the display coordinate system (Settings →
 * Coordinates: State Plane, UTM, NAD83, beta frames, height references) must never change the WGS84 positions
 * that AR anchors, mock location, map markers and stakeout use. Those paths read the stored / live WGS84
 * latitude, longitude and ellipsoidal height directly; display conversions produce text only.
 *
 * This guard scans the position-path sources for calls into the display conversion. The one allowed use is the
 * AR point sheet, which converts only to SHOW the position in the user's system (the anchor uses WGS84).
 */
class Wgs84PositionPathsGuardTest {

    private val root: File by lazy {
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("src/main/java not found from ${System.getProperty("user.dir")}")
    }

    private val positionPaths = listOf(
        "app/surrealar/ui/rendermap/StakeoutController.kt",
        "app/surrealar/ui/rendermap/CoordinateMarkerLayer.kt",
        "app/surrealar/ui/rendermap/LiveTrackingController.kt",
        "app/surrealar/gnss/mock/AndroidMockLocationPublisher.kt",
        "app/surrealar/ui/openinar/OpenInARFragment.kt",
    )

    private val displayConversion = Regex("""\b(convertCoordinate|convertFix|convertWgs84|elevationOfFix|elevationOfCoordinate)\s*\(|\bConvertedPosition\b|\.easting\b|\.northing\b""")

    /** Display-only uses (label text), by file and a substring of the line. */
    private val allowed = listOf(
        "OpenInARFragment.kt" to "coordinateSystemService.convertWgs84(coord.latitude, coord.longitude, coord.altitude, coord.timestamp)",
    )

    @Test fun positionPaths_neverUseDisplayConversions() {
        val violations = mutableListOf<String>()
        for (rel in positionPaths) {
            val f = File(root, rel)
            assertTrue("missing $rel", f.isFile)
            f.readLines().forEachIndexed { i, line ->
                if (!displayConversion.containsMatchIn(line)) return@forEachIndexed
                if (allowed.any { (file, text) -> rel.endsWith(file) && line.contains(text) }) return@forEachIndexed
                violations += "$rel:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue("Display conversions in WGS84 position paths:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test fun theArPointSheetConversion_isUsedForTextOnly() {
        val src = File(root, "app/surrealar/ui/openinar/OpenInARFragment.kt").readText()
        val i = src.indexOf("coordinateSystemService.convertWgs84(coord.latitude")
        assertTrue(i > 0)
        // The sheet receives the WGS84 lat/lon/alt themselves; the conversion result only feeds display strings.
        val sheet = src.substring(i, minOf(src.length, i + 1200))
        assertTrue(sheet.contains("lat       = coord.latitude"))
        assertTrue(sheet.contains("lon       = coord.longitude"))
        assertTrue(sheet.contains("alt       = coord.altitude"))
    }
}
