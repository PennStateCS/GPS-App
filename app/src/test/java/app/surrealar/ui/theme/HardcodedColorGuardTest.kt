package app.surrealar.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ratchet against theme-bypassing colours in layouts: raw hex values, framework @android:color/ values (except
 * transparent) and @color/white / @color/black. New hits fail; the allowlist holds the known phase-2 queue
 * (docs/design-system.md, "Audit") with the count each file may still have — lower it as files are fixed, never raise
 * it. Intentional fixed colours belong in a purpose-named @color resource (e.g. ar_overlay_*), not inline.
 */
class HardcodedColorGuardTest {

    private val pattern = Regex("\"#[0-9A-Fa-f]{3,8}\"|@android:color/(?!transparent\\b)[a-z_]+|@color/(white|black)\"")

    /** file (relative to res/) → hits still allowed. */
    private val allowed = mapOf(
        // White text on coloured badges (theme-independent fills set in code) — phase 2: status on-container roles.
        "layout/fragment_coordinate_detail.xml" to 4,
        "layout/item_model.xml" to 1,
        // Coordinate marker icon tile (white behind a coloured glyph) — phase 2.
        "layout/item_point.xml" to 1,
    )

    @Test fun `layouts use theme or named colour resources`() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val counts = res.listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }!!.toList() }
            .associate { f -> "${f.parentFile.name}/${f.name}" to pattern.findAll(f.readText()).count() }
            .filterValues { it > 0 }
        val over = counts.filter { (file, n) -> n > (allowed[file] ?: 0) }
        assertTrue("Hardcoded colours in layouts (use ?attr/… or a named @color): $over", over.isEmpty())
        for ((file, n) in allowed) println("hardcoded-colour allowlist: $file ${counts[file] ?: 0}/$n")
    }
}
