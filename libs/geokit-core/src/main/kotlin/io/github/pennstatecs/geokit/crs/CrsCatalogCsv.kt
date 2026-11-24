package io.github.pennstatecs.geokit.crs

import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.projection.ProjectionMethod

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
 * | `method` | [ProjectionMethod.token] (`tm`, `lcc2sp`, `omerc_a`) or the EPSG method code |
 * | `parameters` | `key=value` pairs separated by `;`, keys from [ProjectionMethod.parameterNames], degrees / metres |
 * | `unit` | `metre` (the only native unit supported) |
 * | `west,south,east,north` | area of use in degrees (east < west crosses the antimeridian) |
 * | `dataset` | provenance, e.g. `NGS SPCS2022 v1.0 (2025-xx)` |
 * | `verified_by` | the checked-in reference vectors that verify this zone (e.g. `coords/spcs2022_vectors.csv`); **empty = unverified** |
 *
 * An entry is operational only if `verified_by` is filled **and** its method, parameters, frame
 * ellipsoid and unit are valid; otherwise it loads as non-operational with a [CrsDefinition.statusNote].
 * Only fill `verified_by` once a test in this module checks the zone against those vectors. Lines that
 * cannot be represented (wrong column count, unknown frame, malformed numbers, duplicate id) are skipped
 * and reported in [CrsCatalogLoadResult.problems]; loading never throws on bad content.
 */
public object CrsCatalogCsv {

    /** The required header line. */
    public const val HEADER: String = "zone_code,epsg,name,frame,method,parameters,unit,west,south,east,north,dataset,verified_by"

    private val columns = HEADER.split(",")

    /** Parses catalog [text] into definitions of [catalog]. */
    public fun parse(text: String, catalog: CrsCatalogId): CrsCatalogLoadResult {
        val entries = ArrayList<CrsDefinition>()
        val problems = ArrayList<String>()
        val ids = HashSet<String>()
        var headerSeen = false
        text.removePrefix("﻿").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val lineNo = index + 1
            if (!headerSeen) {
                headerSeen = true
                if (line.replace(" ", "") != HEADER) {
                    problems += "line $lineNo: header must be \"$HEADER\""
                    return CrsCatalogLoadResult(emptyList(), problems)
                }
                return@forEachIndexed
            }
            val f = splitCsv(line)
            if (f == null || f.size != columns.size) {
                problems += "line $lineNo: expected ${columns.size} columns"
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
        val frame = ReferenceFrame.fromId(f[3].trim()) ?: return Parsed.Bad("unknown frame \"${f[3].trim()}\"")
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
        if (method != null) {
            runCatching { method.create(parameters, frame.ellipsoid) }.onFailure { issues += it.message ?: "invalid parameters" }
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
            projectionFactory = method?.let { m -> { m.create(parameters, frame.ellipsoid) } },
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
