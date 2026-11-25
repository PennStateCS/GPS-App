package io.github.pennstatecs.geokit.crs

import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.projection.ProjectionMethod
import io.github.pennstatecs.geokit.status.ProductStatus

/** Definitions read from a catalog file, plus the lines that could not be loaded. */
public data class CrsCatalogLoadResult(val entries: List<CrsDefinition>, val problems: List<String>)

/**
 * Reader for machine-readable projected-CRS catalogs (the format a future SPCS2022 table is shipped in).
 *
 * UTF-8 CSV. Blank lines and lines starting with `#` are ignored. The first other line is the header,
 * exactly:
 *
 * ```
 * zone_code,epsg,name,frame,method,parameters,unit,west,south,east,north,dataset,verified_by
 * ```
 *
 * | Column | Content |
 * |---|---|
 * | `zone_code` | NGS zone code (required, unique), e.g. `3701` |
 * | `epsg` | EPSG code of the metre CRS, or empty if EPSG has none yet. Id = `EPSG:<epsg>`, else `<catalog>:<zone_code>` |
 * | `name` | display name; quote with `"` if it contains a comma (`""` = a literal quote) |
 * | `frame` | [ReferenceFrame.id], e.g. `natrf2022` |
 * | `method` | [ProjectionMethod.token] (`tm`, `lcc2sp`, `omerc_a`, `lcc1sp`, `omerc_b`) or the EPSG method code |
 * | `parameters` | `key=value` pairs separated by `;`, keys from [ProjectionMethod.parameterNames], degrees / metres |
 * | `unit` | `metre` (the only native unit supported) |
 * | `west,south,east,north` | area of use in degrees (east < west crosses the antimeridian) |
 * | `dataset` | provenance, e.g. `NGS SPCS2022 v1.0 (2025-xx)` |
 * | `verified_by` | the checked-in reference vectors that verify this zone (e.g. `crs/spcs2022/ngs_example_coordinates.csv`); **empty = unverified** |
 *
 * **Version 2** ([HEADER_V2], backward compatible: the version-1 header above is still read) appends two
 * columns:
 *
 * | Column | Content |
 * |---|---|
 * | `status` | declared [ProductStatus] when usable: `verified_legacy`, `verified_beta` or `unavailable`; empty = `verified_legacy` (the version-1 meaning) |
 * | `aliases` | extra search terms separated by `;` (e.g. zone abbreviation, zone type) |
 *
 * An entry is operational only if `verified_by` is filled **and** its method, parameters, frame
 * ellipsoid and unit are valid and its status is not `unavailable`; otherwise it loads as
 * non-operational with a [CrsDefinition.statusNote]. Only fill `verified_by` once a test in this module
 * checks the zone against those vectors. A frame id from [PENDING_FRAME_IDS] (announced NGS 2022 frames
 * GeoKit does not define yet) is accepted: the entry loads non-operational with `frame = null`
 * (method parameters are still validated on GRS80, the ellipsoid of every 2022 frame). Lines that
 * cannot be represented (wrong column count, other unknown frames, malformed numbers, duplicate id) are
 * skipped and reported in [CrsCatalogLoadResult.problems]; loading never throws on bad content.
 */
public object CrsCatalogCsv {

    /** The required header line. */
    public const val HEADER: String = "zone_code,epsg,name,frame,method,parameters,unit,west,south,east,north,dataset,verified_by"

    /** The version-2 header: [HEADER] plus `status` and `aliases`. */
    public const val HEADER_V2: String = "$HEADER,status,aliases"

    /**
     * Ids of NGS 2022 reference frames that catalog entries may name before GeoKit defines them
     * ([ReferenceFrame.fromId] returns null): entries on them load, but never operational. Since Phase 4 WP X
     * all three are [ReferenceFrame]s, so these ids resolve to frames and the set no longer affects parsing.
     */
    public val PENDING_FRAME_IDS: Set<String> = setOf("patrf2022", "catrf2022", "matrf2022")

    /** Parses catalog [text] into definitions of [catalog]. */
    public fun parse(text: String, catalog: CrsCatalogId): CrsCatalogLoadResult {
        val entries = ArrayList<CrsDefinition>()
        val problems = ArrayList<String>()
        val ids = HashSet<String>()
        var columnCount = 0
        text.removePrefix("﻿").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val lineNo = index + 1
            if (columnCount == 0) {
                columnCount = when (line.replace(" ", "")) {
                    HEADER -> HEADER.split(",").size
                    HEADER_V2 -> HEADER_V2.split(",").size
                    else -> {
                        problems += "line $lineNo: header must be \"$HEADER\" or \"$HEADER_V2\""
                        return CrsCatalogLoadResult(emptyList(), problems)
                    }
                }
                return@forEachIndexed
            }
            val f = splitCsv(line)
            if (f == null || f.size != columnCount) {
                problems += "line $lineNo: expected $columnCount columns"
                return@forEachIndexed
            }
            when (val r = parseEntry(f, catalog)) {
                is Parsed.Bad -> problems += "line $lineNo: ${r.reason}"
                is Parsed.Ok -> if (!ids.add(r.definition.id)) problems += "line $lineNo: duplicate id ${r.definition.id}"
                    else entries += r.definition
            }
        }
        return CrsCatalogLoadResult(entries, problems)
    }

    /** Reads and parses a classpath [resource]; a missing resource gives no entries and one problem. */
    public fun fromClasspath(resource: String, catalog: CrsCatalogId): CrsCatalogLoadResult {
        val stream = CrsCatalogCsv::class.java.classLoader?.getResourceAsStream(resource)
            ?: return CrsCatalogLoadResult(emptyList(), listOf("resource $resource not found"))
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return parse(text, catalog)
    }

    private sealed interface Parsed {
        class Ok(val definition: CrsDefinition) : Parsed
        class Bad(val reason: String) : Parsed
    }

    private fun parseEntry(f: List<String>, catalog: CrsCatalogId): Parsed {
        val zoneCode = f[0].trim()
        if (zoneCode.isEmpty() || zoneCode.contains(' ')) return Parsed.Bad("zone_code is required (no spaces)")
        val epsgText = f[1].trim()
        val epsg = if (epsgText.isEmpty()) null else epsgText.toIntOrNull()?.takeIf { it > 0 }
            ?: return Parsed.Bad("epsg \"$epsgText\" is not a positive integer")
        val name = f[2].trim().ifEmpty { return Parsed.Bad("name is required") }
        val frameId = f[3].trim()
        val frame = ReferenceFrame.fromId(frameId)
        if (frame == null && frameId !in PENDING_FRAME_IDS) return Parsed.Bad("unknown frame \"$frameId\"")
        val statusText = f.getOrNull(13)?.trim()?.lowercase().orEmpty()
        val declared = when (statusText) {
            "", "verified_legacy" -> ProductStatus.VERIFIED_LEGACY
            "verified_beta" -> ProductStatus.VERIFIED_BETA
            "unavailable" -> ProductStatus.UNAVAILABLE
            else -> null
        }
        val aliases = f.getOrNull(14)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val methodText = f[4].trim()
        val method = ProjectionMethod.fromToken(methodText)
        val parameters = LinkedHashMap<String, Double>()
        for (pair in f[5].split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val k = pair.substringBefore('=', "").trim()
            val v = pair.substringAfter('=', "").trim().toDoubleOrNull()
            if (k.isEmpty() || v == null || !v.isFinite()) return Parsed.Bad("parameter \"$pair\" is not key=number")
            parameters[k] = v
        }
        val bounds = (7..10).map { f[it].trim().toDoubleOrNull() }
        if (bounds.any { it == null || !it.isFinite() }) return Parsed.Bad("area of use must be four numbers")
        val (w, s, e, n) = bounds.map { it!! }
        if (s > n || s < -90 || n > 90 || w < -180 || w > 180 || e < -180 || e > 180) {
            return Parsed.Bad("area of use out of range")
        }
        val dataset = f[11].trim().ifEmpty { "unspecified" }
        val verifiedBy = f[12].trim()
        val unit = CrsUnit.entries.firstOrNull { it.token == f[6].trim().lowercase() }

        // Anything that makes the entry unusable keeps it listed, but non-operational.
        val issues = ArrayList<String>()
        if (verifiedBy.isEmpty()) issues += "no verified test vectors"
        if (method == null) issues += "unknown projection method \"$methodText\""
        if (unit != CrsUnit.METRE) issues += "unsupported unit \"${f[6].trim()}\""
        if (frame == null) issues += "frame $frameId is not defined in GeoKit yet"
        if (declared == null) issues += "unknown status \"$statusText\""
        if (declared == ProductStatus.UNAVAILABLE) issues += "catalog status is unavailable"
        // Every pending (2022) frame is on GRS80, so its parameters can be checked before the frame exists.
        val ellipsoid = frame?.ellipsoid ?: Ellipsoid.GRS80
        if (method != null) {
            runCatching { method.create(parameters, ellipsoid) }.onFailure { issues += it.message ?: "invalid parameters" }
            val extra = parameters.keys - method.parameterNames.toSet()
            if (extra.isNotEmpty()) issues += "unknown parameters ${extra.joinToString()}"
        }
        val operational = issues.isEmpty()
        val id = if (epsg != null) "EPSG:$epsg" else "${catalog.id}:$zoneCode"
        val definition = CrsDefinition(
            id = id, epsg = epsg, name = name, zoneCode = zoneCode, catalog = catalog, kind = CrsKind.PROJECTED,
            frame = frame, method = method, parameters = parameters, unit = unit ?: CrsUnit.METRE,
            areaOfUse = AreaOfUse(w, s, e, n), dataset = dataset, operational = operational,
            statusNote = if (operational) "verified by $verifiedBy" else issues.joinToString("; "),
            projectionFactory = method?.let { m -> { m.create(parameters, ellipsoid) } },
            declaredStatus = declared ?: ProductStatus.UNAVAILABLE, aliases = aliases, frameId = frameId,
        )
        return Parsed.Ok(definition)
    }

    /** Splits one CSV line; `"` quotes a field, `""` inside quotes is a quote. Null if a quote is unclosed. */
    internal fun splitCsv(line: String): List<String>? {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        if (quoted) return null
        out += sb.toString()
        return out
    }
}
