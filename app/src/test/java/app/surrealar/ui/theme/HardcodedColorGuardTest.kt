package app.surrealar.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ratchet against theme-bypassing colours.
 *
 * - Layouts: raw hex values, framework @android:color/ values (except transparent) and @color/white / @color/black.
 *   Phase 2 cleared the layout queue, so no layout may have any (intentional fixed colours belong in a
 *   purpose-named @color resource such as ar_overlay_*, not inline).
 * - Kotlin (src/main/java): Color.parseColor / Color.rgb / Color.argb / String.toColorInt, 8-digit 0x… ARGB
 *   literals and android.R.color.* (except transparent). Only the documented fixed exceptions below may remain
 *   (docs/design-system.md, "Intentional exceptions"); lower a count when one is removed, never raise it.
 */
class HardcodedColorGuardTest {

    private val layoutPattern = Regex("\"#[0-9A-Fa-f]{3,8}\"|@android:color/(?!transparent\\b)[a-z_]+|@color/(white|black)\"")

    private val kotlinPattern = Regex(
        "Color\\.parseColor\\(|Color\\.rgb\\(|Color\\.argb\\(|\\.toColorInt\\(|\\b0x[0-9A-Fa-f]{8}\\b|android\\.R\\.color\\.(?!transparent\\b)[a-z_]+"
    )

    /** layout file (relative to res/) → hits still allowed. Empty: every layout uses theme or named colours. */
    private val allowedLayouts = emptyMap<String, Int>()

    /** Kotlin file (relative to src/main/java/app/surrealar/) → documented fixed hits. */
    private val allowedKotlin = mapOf(
        // Stored data: default marker colour of a user coordinate (persisted in the DB / backups), not UI chrome.
        "data/export/CoordinateBackup.kt" to 1,
        "domain/usecase/ImportCoordinateBackupUseCase.kt" to 1,
        "ui/viewpoints/AddCoordinateDialogFragment.kt" to 1,
        "ui/rendermap/CoordinateInfoBottomSheet.kt" to 1,          // fallback when no marker colour is passed
        "ui/openinar/OpenInARFragment.kt" to 1,                    // test-cube coordinate's marker colour
        // Not a colour: the GLB file magic number (comment + check).
        "ui/openinar/ArModelRenderer.kt" to 2,
        // Fallback only when the theme attribute cannot be resolved.
        "ui/development/DevelopmentFragment.kt" to 1,
        // Map graphics over Google Maps tiles (theme-independent; validated on light + satellite tiles):
        // marker-bitmap border, live accuracy circle + trail, boundary lines, stakeout line.
        "ui/rendermap/CoordinateMarkerLayer.kt" to 1,
        "ui/viewpoints/CoordinateDetailFragment.kt" to 1,
        "ui/rendermap/LiveTrackingController.kt" to 3,
        "ui/rendermap/MapOverlayController.kt" to 2,
        "ui/rendermap/StakeoutController.kt" to 1,
    )

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test fun `layouts use theme or named colour resources`() {
        val res = File(main(), "res")
        val counts = res.listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }!!.toList() }
            .associate { f -> "${f.parentFile.name}/${f.name}" to layoutPattern.findAll(f.readText()).count() }
            .filterValues { it > 0 }
        val over = counts.filter { (file, n) -> n > (allowedLayouts[file] ?: 0) }
        assertTrue("Hardcoded colours in layouts (use ?attr/… or a named @color): $over", over.isEmpty())
    }

    @Test fun `kotlin uses theme or named colour resources`() {
        val root = File(main(), "java/app/surrealar")
        val counts = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { f -> f.relativeTo(root).invariantSeparatorsPath to kotlinPattern.findAll(f.readText()).count() }
            .filterValues { it > 0 }
        val over = counts.filter { (file, n) -> n > (allowedKotlin[file] ?: 0) }
        assertTrue("Hardcoded colours in Kotlin (use a theme attribute, StatusTone or a named @color): $over", over.isEmpty())
        for ((file, n) in allowedKotlin) println("hardcoded-colour allowlist: $file ${counts[file] ?: 0}/$n")
        // The allowlist is exact: a fixed exception that was removed must also leave the list.
        val stale = allowedKotlin.filter { (file, n) -> (counts[file] ?: 0) < n }
        assertTrue("Allowlist larger than needed (lower it): $stale", stale.isEmpty())
    }
}
