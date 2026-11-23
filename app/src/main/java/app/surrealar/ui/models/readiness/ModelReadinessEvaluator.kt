package app.surrealar.ui.models.readiness

import io.github.pennstatecs.geokit.ar.model.ModelBounds
import io.github.pennstatecs.geokit.ar.model.ModelPlacementOrigin
import io.github.pennstatecs.geokit.ar.model.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The readiness rules: facts in, report out. Pure and deterministic.
 *
 * Geometry findings place the model exactly the way AR does (ArFilamentRenderer): the AR scale is
 * `ns × scale` (the global model-scale slider is taken as 1), where `ns` shrinks models whose largest
 * half-extent exceeds [NORMALIZE_HALF_EXTENT_M] to 1 m; the anchor point and nudge come from
 * [ModelBounds.resolveOffsets]; yaw/pitch/roll are applied as in the renderer's correction matrix.
 *
 * Messages never contain coordinates; strings that come from outside (validation errors, file names)
 * have long decimal numbers masked as a precaution.
 */
object ModelReadinessEvaluator : ReadinessRules {

    /** Same as ArFilamentRenderer's BB_NORMALIZE_THRESHOLD_M. */
    const val NORMALIZE_HALF_EXTENT_M = 2000.0
    const val TINY_MODEL_M = 0.01
    const val PLACEMENT_TOLERANCE_MIN_M = 0.02
    const val PLACEMENT_TOLERANCE_FRACTION = 0.02
    const val MAX_PLACEMENT_CHECKS = 5
    const val FLAT_RATIO = 0.15
    const val FLAT_MIN_LARGEST_SIDE_M = 1.0
    /** A box has 12 triangles; anything simpler (a quad, a decal) is flat on purpose. */
    const val FLAT_MIN_TRIANGLES = 12L
    const val TRIANGLES_WARN = 1_500_000L
    const val TRIANGLES_FAIL = 4_000_000L
    const val TEXTURE_SIDE_WARN_PX = 4096
    const val TEXTURE_BYTES_WARN = 256L * 1024 * 1024
    const val TEXTURE_BYTES_FAIL = 512L * 1024 * 1024
    const val FILE_SIZE_WARN_BYTES = 100L * 1024 * 1024
    const val FILE_SIZE_CAP_MB = 150

    /** Required extensions Filament gltfio 1.81 handles (plus any KHR_materials_*). */
    val SUPPORTED_REQUIRED_EXTENSIONS = setOf(
        "KHR_texture_transform", "KHR_mesh_quantization", "KHR_draco_mesh_compression", "KHR_texture_basisu",
        "EXT_meshopt_compression", "KHR_lights_punctual", "KHR_materials_variants",
    )

    override fun evaluate(facts: ModelFacts): ReadinessReport {
        val checks = mutableListOf<ReadinessCheck>()
        checks += load(facts)
        validation(facts)?.let { checks += it }

        val bounds = facts.renderedBounds ?: facts.gltf.bounds
        if (bounds == null) {
            checks += ReadinessCheck(
                "geometry", "Geometry", CheckStatus.FAIL, "Couldn't read the model's geometry.",
                fix = "Re-export the model as a glTF 2.0 / GLB file with its meshes included.",
            )
        } else {
            val geo = Geo(bounds)
            checks += units(facts, geo)
            checks += placement(facts, geo)
            origin(facts, geo)?.let { checks += it }
            upAxis(facts, geo)?.let { checks += it }
        }

        checks += ReadinessCheck(
            "north", "North", CheckStatus.INFO,
            "In AR the model's +Z axis points north; rotate with Yaw if it faces the wrong way.",
        )
        checks += complexity(facts)
        checks += linked(facts)

        val ordered = checks.sortedBy { rank(it.status) }
        val fails = ordered.count { it.status == CheckStatus.FAIL }
        val warns = ordered.count { it.status == CheckStatus.WARN }
        return when {
            fails > 0 -> ReadinessReport(Verdict.NOT_READY, ordered, "Not ready: ${plural(fails, "problem")}")
            warns > 0 -> ReadinessReport(Verdict.READY_WITH_WARNINGS, ordered, "Ready with ${plural(warns, "warning")}")
            else -> ReadinessReport(Verdict.READY, ordered, "Ready for AR")
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    private class Geo(b: Bounds) {
        val mb = ModelBounds(b.min, b.max)
        val sx = (b.max.x - b.min.x).toDouble()
        val sy = (b.max.y - b.min.y).toDouble()
        val sz = (b.max.z - b.min.z).toDouble()
        val maxHalfExtent = maxOf(sx, sy, sz) / 2.0
        /** AR's unit normalization factor `ns`. */
        val ns = if (maxHalfExtent > NORMALIZE_HALF_EXTENT_M) 1.0 / (maxHalfExtent * 2.0) else 1.0
    }

    private fun scaleOf(facts: ModelFacts, link: LinkFacts?): Double {
        val s = link?.scale ?: facts.defaultScale ?: 1.0
        return if (s.isFinite() && s > 0) s else 1.0
    }

    private fun effScale(facts: ModelFacts, geo: Geo, link: LinkFacts?) = geo.ns * scaleOf(facts, link)

    // ------------------------------------------------------------------ 1. load

    private fun isSupportedRequired(ext: String) =
        ext.startsWith("KHR_materials_") || ext in SUPPORTED_REQUIRED_EXTENSIONS

    private fun load(facts: ModelFacts): ReadinessCheck {
        val g = facts.gltf
        val problems = mutableListOf<String>()
        g.parseError?.let { problems += "Couldn't read the file: ${scrub(it).trimEnd('.')}." }
        if (facts.loadedByRenderer == false) problems += "The viewer couldn't load it."
        if (g.missingResources.isNotEmpty()) {
            problems += "Missing files: ${list(g.missingResources.map(::scrub), 5)}."
        }
        if (problems.isNotEmpty()) {
            val fix = if (g.missingResources.isNotEmpty() && g.parseError == null)
                "Re-import it as a single GLB, or put the missing files next to the .gltf."
            else "Re-export the model as a glTF 2.0 / GLB file."
            return ReadinessCheck("load", "Loads", CheckStatus.FAIL, problems.joinToString(" "), fix = fix)
        }
        val unsupported = g.extensionsRequired.filterNot(::isSupportedRequired)
        if (unsupported.isNotEmpty()) {
            return ReadinessCheck(
                "load", "Loads", CheckStatus.WARN,
                "It requires ${list(unsupported.map(::scrub), 5)}, which the viewer doesn't support, so it may not load.",
                fix = "Re-export without ${if (unsupported.size == 1) "that extension" else "those extensions"}.",
            )
        }
        val detail = if (facts.loadedByRenderer == true) "Loads in the viewer." else "The file reads correctly."
        return ReadinessCheck("load", "Loads", CheckStatus.PASS, detail)
    }

    // ------------------------------------------------------------------ 2. validation

    private fun validation(facts: ModelFacts): ReadinessCheck? {
        val errs = facts.validationErrors.map(::scrub).filter { it.isNotBlank() }
        if (facts.isValid && errs.isEmpty()) return null
        val shown = errs.take(3).joinToString("; ") { it.trimEnd('.') }
        val more = if (errs.size > 3) " (and ${errs.size - 3} more)" else ""
        return if (!facts.isValid) ReadinessCheck(
            "validation", "Import check", CheckStatus.FAIL,
            if (errs.isEmpty()) "Import marked the model as invalid." else "Import found problems: $shown$more.",
            fix = "Fix the model in its authoring tool and import it again.",
        ) else ReadinessCheck("validation", "Import check", CheckStatus.WARN, "Import noted: $shown$more.")
    }

    // ------------------------------------------------------------------ 3. units

    private fun units(facts: ModelFacts, geo: Geo): ReadinessCheck {
        val link = facts.links.firstOrNull()
        val scale = scaleOf(facts, link)
        if (geo.maxHalfExtent > NORMALIZE_HALF_EXTENT_M) {
            return ReadinessCheck(
                "units", "Size and units", CheckStatus.FAIL,
                "It is ${len(geo.maxHalfExtent * 2)} across, so AR will shrink it to 1 m — probably millimetre units.",
                fix = "Re-export the model in metres.",
            )
        }
        val w = geo.sx * scale; val d = geo.sz * scale; val h = geo.sy * scale
        val largest = maxOf(w, d, h)
        if (largest < TINY_MODEL_M) {
            return ReadinessCheck(
                "units", "Size and units", CheckStatus.WARN,
                "It is only ${String.format(Locale.US, "%.1f", largest * 1000)} mm across in AR — probably the wrong units.",
                fix = "Re-export the model in metres, or raise its scale.",
            )
        }
        return ReadinessCheck(
            "units", "Size and units", CheckStatus.PASS,
            "About ${num(w)} × ${num(d)} × ${num(h)} m in AR (width × depth × height).",
        )
    }

    // ------------------------------------------------------------------ 4. placement

    private class Placed(val bottom: Double, val top: Double, val verticalOffset: Double)

    /** Vertical extent of the model relative to the ground point, exactly as AR's correction matrix places it. */
    private fun place(facts: ModelFacts, geo: Geo, link: LinkFacts?): Placed {
        val origin = ModelPlacementOrigin.from(link?.placementOrigin)
        val custom = Vec3(
            (link?.originOffsetXM ?: 0.0).toFloat(), (link?.originOffsetYM ?: 0.0).toFloat(), (link?.originOffsetZM ?: 0.0).toFloat(),
        )
        val vo = link?.verticalOffsetM ?: 0.0
        val offsets = geo.mb.resolveOffsets(origin, custom, vo.toFloat())
        val ap = offsets.anchorPoint
        val eff = effScale(facts, geo, link)
        val pitch = Math.toRadians(link?.pitchDeg ?: 0.0)
        val roll = Math.toRadians(link?.rollDeg ?: 0.0)
        // cm = R(180°Y)·S(eff)·T(-ap) maps p to (-eff(px-apx), eff(py-apy), -eff(pz-apz)); then R = Ry·Rx·Rz.
        // Yaw (about Y) never changes height, so only roll then pitch matter for Y.
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        val min = geo.mb.min; val max = geo.mb.max
        for (c in 0 until 8) {
            val px = if (c and 1 == 0) min.x else max.x
            val py = if (c and 2 == 0) min.y else max.y
            val pz = if (c and 4 == 0) min.z else max.z
            val x = -eff * (px - ap.x); val y = eff * (py - ap.y); val z = -eff * (pz - ap.z)
            val y1 = x * sin(roll) + y * cos(roll)
            val y2 = y1 * cos(pitch) - z * sin(pitch)
            lo = minOf(lo, y2); hi = maxOf(hi, y2)
        }
        val n = offsets.nudge.y.toDouble()
        return Placed(lo + n, hi + n, vo)
    }

    private fun placement(facts: ModelFacts, geo: Geo): List<ReadinessCheck> {
        val links = facts.links
        if (links.isEmpty()) return listOf(placementCheck(facts, geo, null, named = false))
        val named = links.size > 1
        val all = links.map { placementCheck(facts, geo, it, named) }
        if (all.size <= MAX_PLACEMENT_CHECKS) return all
        val rest = all.drop(MAX_PLACEMENT_CHECKS)
        val bad = rest.count { it.status == CheckStatus.WARN }
        val detail = if (bad == 0) "${plural(rest.size, "more point")}: the model sits on each."
        else "${plural(rest.size, "more point")}: the model sinks or floats at $bad of them."
        return all.take(MAX_PLACEMENT_CHECKS) + ReadinessCheck(
            "placement", "Placement at other points", if (bad == 0) CheckStatus.PASS else CheckStatus.WARN, detail,
            fix = if (bad == 0) null else "Use Bottom Center placement on those points.",
        )
    }

    private fun placementCheck(facts: ModelFacts, geo: Geo, link: LinkFacts?, named: Boolean): ReadinessCheck {
        val p = place(facts, geo, link)
        val height = p.top - p.bottom
        val tol = maxOf(PLACEMENT_TOLERANCE_MIN_M, PLACEMENT_TOLERANCE_FRACTION * height)
        val title = if (named) "Placement at ${q(link!!.coordinateName)}" else "Placement"
        val lead = when {
            link == null -> "With the default Center placement it "
            named -> "At ${q(link.coordinateName)} it "
            else -> "It "
        }
        return when {
            abs(p.bottom) <= tol -> ReadinessCheck("placement", title, CheckStatus.PASS, "${lead}sits on the point.")
            p.bottom < 0 -> {
                val below = -p.bottom
                val pct = if (height > 0) (minOf(below, height) / height * 100).roundToInt() else 100
                val detail = "${lead}sinks ${len(below)} below the point ($pct% of the model is underground)."
                // Bottom Center only helps when it isn't already set and the model isn't pitched/rolled
                // (AR picks the anchor before rotating). Otherwise offer the exact offset, so the two
                // one-tap fixes can never loop.
                val rotated = abs(link?.pitchDeg ?: 0.0) > 1.0 || abs(link?.rollDeg ?: 0.0) > 1.0
                val alreadyBottom = ModelPlacementOrigin.from(link?.placementOrigin) == ModelPlacementOrigin.BOTTOM_CENTER
                if (link != null && (alreadyBottom || rotated)) {
                    val target = p.verticalOffset - p.bottom
                    ReadinessCheck(
                        "placement", title, CheckStatus.WARN, detail,
                        fix = "Set the vertical offset to ${len(target)} so it sits on the point.",
                        fixAction = FixAction.SET_VERTICAL_OFFSET,
                        fixCoordinateId = link.coordinateId,
                        fixValue = target,
                    )
                } else ReadinessCheck(
                    "placement", title, CheckStatus.WARN, detail,
                    fix = "Use Bottom Center so it sits on the point.",
                    fixAction = FixAction.USE_BOTTOM_CENTER,
                    fixCoordinateId = link?.coordinateId,
                )
            }
            else -> {
                val target = p.verticalOffset - p.bottom
                ReadinessCheck(
                    "placement", title, CheckStatus.WARN,
                    "${lead}floats ${len(p.bottom)} above the point.",
                    fix = "Set the vertical offset to ${len(target)} so it sits on the point.",
                    fixAction = FixAction.SET_VERTICAL_OFFSET,
                    fixCoordinateId = link?.coordinateId,
                    fixValue = target,
                )
            }
        }.let { c -> c.copy(detail = c.detail.replaceFirstChar { it.uppercase() }) }
    }

    // ------------------------------------------------------------------ 5. origin

    private fun origin(facts: ModelFacts, geo: Geo): ReadinessCheck? {
        val far = geo.mb.originToBottomCenterM
        if (far <= ModelBounds.FAR_FROM_ORIGIN_M) return null
        val usesOrigin = facts.links.firstOrNull {
            ModelPlacementOrigin.from(it.placementOrigin).let { o -> o == ModelPlacementOrigin.ORIGIN || o == ModelPlacementOrigin.CUSTOM }
        }
        val dist = len(far * effScale(facts, geo, usesOrigin ?: facts.links.firstOrNull()))
        return if (usesOrigin != null) ReadinessCheck(
            "origin", "Origin", CheckStatus.WARN,
            "The model's geometry is $dist from its origin, so it will appear away from the point" +
                (if (facts.links.size > 1) " at ${q(usesOrigin.coordinateName)}." else "."),
            fix = "Use Bottom Center so the geometry sits on the point.",
            fixAction = FixAction.USE_BOTTOM_CENTER,
            fixCoordinateId = usesOrigin.coordinateId,
        ) else ReadinessCheck(
            "origin", "Origin", CheckStatus.INFO,
            "The geometry is $dist from its origin, but Center/Bottom Center placement handles it.",
        )
    }

    // ------------------------------------------------------------------ 6. up axis

    private fun upAxis(facts: ModelFacts, geo: Geo): ReadinessCheck? {
        val tris = facts.gltf.triangleCount ?: return null
        if (tris < FLAT_MIN_TRIANGLES) return null
        val link = facts.links.firstOrNull()
        // Already rotated on the point (e.g. the Z-up fix was applied): AR stands it up, nothing to say.
        if (abs(link?.pitchDeg ?: 0.0) > 1.0 || abs(link?.rollDeg ?: 0.0) > 1.0) return null
        val scale = effScale(facts, geo, link)
        val w = geo.sx * scale; val d = geo.sz * scale; val h = geo.sy * scale
        if (maxOf(w, d, h) < FLAT_MIN_LARGEST_SIDE_M || h >= FLAT_RATIO * minOf(w, d)) return null
        return ReadinessCheck(
            "upAxis", "Up axis", CheckStatus.WARN,
            "Very flat (${len(h)} tall, ${num(w)} × ${num(d)} m across). Fine for terrain or a slab, but if it " +
                "should stand up it was probably exported Z-up.",
            fix = "Rotate it to stand up.",
            fixAction = FixAction.ROTATE_UP_AXIS,
            fixCoordinateId = link?.coordinateId,
        )
    }

    // ------------------------------------------------------------------ 8. complexity

    private fun complexity(facts: ModelFacts): ReadinessCheck {
        val g = facts.gltf
        val problems = mutableListOf<Pair<CheckStatus, String>>()
        val fixes = linkedSetOf<String>()
        val unknown = mutableListOf<String>()

        val tris = g.triangleCount
        when {
            tris == null -> unknown += "triangle count"
            tris > TRIANGLES_FAIL -> {
                problems += CheckStatus.FAIL to "${count(tris)} triangles is more than the tablet can draw (limit ${count(TRIANGLES_FAIL)})"
                fixes += "Reduce the polygon count (decimate) before exporting."
            }
            tris > TRIANGLES_WARN -> {
                problems += CheckStatus.WARN to "${count(tris)} triangles may make AR stutter"
                fixes += "Reduce the polygon count (decimate) before exporting."
            }
        }
        val side = g.maxTextureSidePx
        if (side != null && side > TEXTURE_SIDE_WARN_PX) {
            problems += CheckStatus.WARN to "a ${count(side.toLong())} px texture is larger than the tablet handles well"
            fixes += "Resize textures to $TEXTURE_SIDE_WARN_PX px or less."
        }
        val texBytes = g.estimatedTextureBytes
        when {
            texBytes == null -> if ((g.textureCount ?: 1) > 0) unknown += "texture sizes"
            texBytes > TEXTURE_BYTES_FAIL -> {
                problems += CheckStatus.FAIL to "textures need about ${mb(texBytes)} of graphics memory (limit ${mb(TEXTURE_BYTES_FAIL)})"
                fixes += "Resize textures to $TEXTURE_SIDE_WARN_PX px or less."
            }
            texBytes > TEXTURE_BYTES_WARN -> {
                problems += CheckStatus.WARN to "textures need about ${mb(texBytes)} of graphics memory"
                fixes += "Resize textures to $TEXTURE_SIDE_WARN_PX px or less."
            }
        }
        if (g.fileSizeBytes > FILE_SIZE_WARN_BYTES) {
            problems += CheckStatus.WARN to "the file is ${mb(g.fileSizeBytes)} (the import limit is $FILE_SIZE_CAP_MB MB)"
            fixes += "Compress the model (e.g. Draco or meshopt) to make the file smaller."
        }

        if (problems.isNotEmpty()) {
            val status = if (problems.any { it.first == CheckStatus.FAIL }) CheckStatus.FAIL else CheckStatus.WARN
            val detail = problems.joinToString("; ") { it.second }.replaceFirstChar { it.uppercase() } + "."
            return ReadinessCheck("complexity", "Complexity", status, detail, fix = fixes.joinToString(" "))
        }
        val texPart = when (val n = g.textureCount) {
            null -> "unknown textures"
            0 -> "no textures"
            else -> plural(n, "texture") + (side?.let { " (max ${count(it.toLong())} px)" } ?: "")
        }
        val triPart = tris?.let { plural(it, "triangle") } ?: "Unknown triangle count"
        val summary = "$triPart, $texPart."
        if (unknown.isNotEmpty() || g.textureCount == null) {
            val what = (unknown + listOfNotNull(if (g.textureCount == null) "texture count" else null)).distinct()
            return ReadinessCheck(
                "complexity", "Complexity", CheckStatus.INFO, "$summary Couldn't read the ${list(what, 3)}.",
            )
        }
        return ReadinessCheck("complexity", "Complexity", CheckStatus.PASS, summary)
    }

    // ------------------------------------------------------------------ 9. linked

    private fun linked(facts: ModelFacts): ReadinessCheck {
        val links = facts.links
        if (links.isEmpty()) return ReadinessCheck(
            "linked", "Linked points", CheckStatus.WARN, "Not linked to any point, so it won't appear in AR.",
            fix = "Link it to a surveyed point.", fixAction = FixAction.LINK_POINT,
        )
        return ReadinessCheck(
            "linked", "Linked points", CheckStatus.PASS,
            "Linked to ${plural(links.size, "point")}: ${list(links.map { q(it.coordinateName) }, 5)}.",
        )
    }

    // ------------------------------------------------------------------ formatting

    private fun rank(s: CheckStatus) = when (s) {
        CheckStatus.FAIL -> 0; CheckStatus.WARN -> 1; CheckStatus.INFO -> 2; CheckStatus.PASS -> 3
    }

    /** 1 decimal at or above 10, 2 decimals below. */
    internal fun num(v: Double): String =
        if (abs(v) >= 10) String.format(Locale.US, "%,.1f", v) else String.format(Locale.US, "%.2f", v)

    /** A length with unit: metres, or km from 1000 m. */
    internal fun len(m: Double): String =
        if (abs(m) >= 1000) String.format(Locale.US, "%,.1f km", m / 1000) else "${num(m)} m"

    private fun count(n: Long) = String.format(Locale.US, "%,d", n)
    private fun mb(bytes: Long) = "${(bytes / (1024.0 * 1024.0)).roundToInt()} MB"
    private fun plural(n: Int, word: String) = plural(n.toLong(), word)
    private fun plural(n: Long, word: String) = "${count(n)} ${if (n == 1L) word else word + "s"}"
    private fun q(name: String) = "‘${scrub(name)}’"

    private fun list(items: List<String>, max: Int): String {
        val shown = items.take(max).joinToString(", ")
        return if (items.size > max) "$shown and ${items.size - max} more" else shown
    }

    /** Masks long decimals (anything that could be a latitude/longitude) in strings from outside. */
    internal fun scrub(s: String): String = s.replace(Regex("-?\\d+\\.\\d{4,}"), "…")
}
