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
 * - Drawables and color selectors (res/drawable*, res/color*): see [drawablePattern] / [allowedDrawables].
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

    /**
     * Drawable (incl. vectors, shapes, layer-lists, ripples, selectors, gradients) and color-selector XML: raw hex,
     * framework @android:color/ (except transparent) and @color/white / @color/black. Fully transparent hex
     * (#00…, #0000) is not a colour. A vector whose root declares android:tint is theme-controlled (the tint
     * replaces its fills), so its fill/stroke values are not checked; a hardcoded tint itself still is.
     */
    private val drawablePattern = Regex("\"#(?!00[0-9A-Fa-f]{6}\"|0[0-9A-Fa-f]{3}\")[0-9A-Fa-f]{3,8}\"|@android:color/(?!transparent\\b)[a-z_]+|@color/(white|black)\"")

    /**
     * Drawable / color resource name → why its fixed colour is intentional (exact: every entry must still contain a
     * fixed colour, and none may be missing). docs/design-system.md, "Intentional exceptions".
     */
    private val allowedDrawables: Map<String, String> = buildMap {
        // Tinted at every use (verified: app:tint / iconTint / imageTintList / backgroundTintList / chip tint at each
        // reference), so the fill is never what is drawn.
        for (n in listOf("ic_expand_more", "ic_fit_screen_24", "ic_person_pin_circle_24", "ic_stakeout_24",
            "ic_my_location_24", "ic_readiness_pass", "ic_readiness_fail", "ic_readiness_warn", "ic_viewer_info",
            "ic_viewer_ar_placement", "ic_viewer_axis_3d", "ic_viewer_bounding_box", "ic_viewer_grid",
            "ic_viewer_layers", "ic_viewer_lightbulb", "ic_viewer_origin", "ic_viewer_reset", "ic_viewer_rotate_360",
            "ic_delete", "ic_render_map", "ic_edit_24", "ic_chevron_right", "ic_no_thumbnail",
            "ic_zoom_in", "ic_zoom_out", "ic_camera_white", "bg_model_badge", "bg_field_status_dot", "battery_level"))
            put(n, "tinted at every use")
        // White on a deliberately fixed dark background: AR overlay chips over the camera (ar_overlay_bg_dark), the
        // navy GNSS strip, the battery glyph inside the strip's battery token.
        for (n in listOf("ic_ar_altitude", "ic_ar_debug", "ic_ar_menu", "ic_layers_24", "ic_refresh_24_white",
            "ic_tune_24", "ic_models_24", "ic_replay_16", "ic_bolt"))
            put(n, "white on a fixed dark AR / GNSS-strip background (tinted where used on themed surfaces)")
        put("ic_location_heading", "map graphic: live-position heading marker over map tiles")
        put("ic_menu_camera", "legacy stored marker icon key (data-tinted marker; settings-row placeholder replaced at bind)")
        put("ic_menu_slideshow", "legacy stored marker icon key (data-tinted marker)")
        put("ic_stat_location", "notification small icon (system-tinted); tinted where shown in the app")
        put("ic_launcher_background", "launcher artwork")
        put("ic_launcher_foreground", "launcher artwork")
    }

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    /** Fixed-colour hits per drawable / color resource (name → count), vectors with a root android:tint excluded. */
    private fun drawableHits(): Map<String, Int> {
        val res = File(main(), "res")
        return res.listFiles { f -> f.isDirectory && (f.name.startsWith("drawable") || f.name.startsWith("color")) }!!
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }!!.toList() }
            .associate { f ->
                val text = f.readText()
                val root = Regex("<vector\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).find(text)?.value
                val tinted = root != null && Regex("android:tint=\"(\\?attr/|@color/(?!white\"|black\"))").containsMatchIn(root)
                val n = if (tinted) drawablePattern.findAll(root!!).count() else drawablePattern.findAll(text).count()
                f.nameWithoutExtension to n
            }
            .filterValues { it > 0 }
    }

    @Test fun `drawables and colour selectors use theme or named colours`() {
        val hits = drawableHits()
        val unapproved = hits.keys - allowedDrawables.keys
        assertTrue("Hardcoded colours in drawables / color selectors (use ?attr/…, a named @color, or a root " +
            "android:tint; or add a documented exception): ${unapproved.associateWith { hits[it] }}", unapproved.isEmpty())
        // Exact: an exception whose drawable was fixed or removed must leave the list.
        val stale = allowedDrawables.keys - hits.keys
        assertTrue("Drawable allowlist entries no longer needed (remove them): $stale", stale.isEmpty())
    }

    @Test fun `drawable guard detects fixed colours and ignores theme-controlled ones`() {
        fun hits(s: String) = drawablePattern.findAll(s).count()
        assertTrue(hits("<solid android:color=\"#FFFFFF\" />") == 1)
        assertTrue(hits("<stroke android:color=\"#BDBDBD\" />") == 1)
        assertTrue(hits("<item android:color=\"@android:color/white\" />") == 1)
        assertTrue(hits("<solid android:color=\"@color/white\" />") == 1)
        assertTrue(hits("<gradient android:startColor=\"#FF000000\" />") == 1)
        assertTrue("transparent is not a colour", hits("<solid android:color=\"#00FFFFFF\" /><x c=\"@android:color/transparent\"/>") == 0)
        assertTrue("theme references pass", hits("<solid android:color=\"?attr/colorSurface\" /><stroke android:color=\"@color/app_outline\" />") == 0)
    }

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
