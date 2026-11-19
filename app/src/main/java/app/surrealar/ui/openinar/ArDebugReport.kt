package app.surrealar.ui.openinar

import app.surrealar.domain.coordinates.CoordinateValidator

/**
 * Builds the AR debug panel text: one line per stage of the pin/model pipeline, in dependency
 * order (session → camera → Earth → GNSS → anchor gate → anchors → render), plus a single
 * "NEXT:" line naming the FIRST stage that is blocking, with a hint for how to fix it.
 *
 * Pure (no Android/ARCore types) so it is unit-testable; the fragment extracts all ARCore state on
 * the GL thread into an [Input] and calls [build] on the main thread.
 */
internal object ArDebugReport {

    data class FixInfo(
        val provider: String,
        val rtk: String,
        val sats: Int?,
        /** Receiver-reported horizontal accuracy (GST); null when the source doesn't report it. */
        val hAccM: Double?,
        /** Accuracy the anchor gate actually uses (hAcc, else HDOP × UERE estimate). */
        val gateAccM: Double?,
        val ageMs: Long?,
        val lat: Double,
        val lon: Double,
    ) {
        val coordsValid: Boolean get() = CoordinateValidator.isValidLatLon(lat, lon)
    }

    data class AnchorRow(
        val name: String,
        val tracking: String,
        /** ARCore TerrainAnchorState name (SUCCESS, TASK_IN_PROGRESS, ERROR_…), null for WGS84 anchors. */
        val terrainState: String?,
        val distM: Double?,
        val bearingDeg: Double?,
        /** Model load state, or "PIN" for coordinates without a model. */
        val model: String,
    )

    data class TestGridInfo(
        val total: Int,
        val tracking: Int,
        /** TerrainAnchorState name → count, e.g. {TASK_IN_PROGRESS=25}. */
        val terrainStates: Map<String, Int>,
        /** Altitude mode the grid was built with: "TERRAIN" or "STORED". */
        val mode: String? = null,
    )

    /**
     * Camera optics: the camera image's field of view vs. what the screen shows. ARCore crops the
     * image to fill the display aspect, so the screen FOV is ≤ the camera FOV ("zoom").
     * Camera FOV is already rotated to match screen orientation.
     */
    data class Optics(
        val texW: Int,
        val texH: Int,
        val camHFovDeg: Double,
        val camVFovDeg: Double,
        val screenHFovDeg: Double,
        val screenVFovDeg: Double,
    ) {
        /** Fraction of the camera image visible on screen per axis (1.0 = none cropped). */
        val shownH: Double get() = tanHalf(screenHFovDeg) / tanHalf(camHFovDeg)
        val shownV: Double get() = tanHalf(screenVFovDeg) / tanHalf(camVFovDeg)
        private fun tanHalf(d: Double) = kotlin.math.tan(Math.toRadians(d / 2))
    }

    data class Input(
        val geospatialEnabled: Boolean,
        val depthEnabled: Boolean,
        val cameraTracking: String?,
        val cameraFailureReason: String?,
        /** False when the counts were not measured this frame (panel decides to show "–"). */
        val featuresCounted: Boolean,
        val planes: Int,
        val points: Int,
        val earthTracking: String?,
        val earthState: String?,
        val earthHAccM: Double?,
        val earthVAccM: Double?,
        val earthYawDeg: Double?,
        val camLat: Double?,
        val camLon: Double?,
        val camAlt: Double?,
        val camHeading: Double?,
        val fix: FixInfo?,
        val gnssGateMaxM: Double,
        val items: Int,
        val itemsWithModels: Int,
        val geoAnchorsCreated: Boolean,
        val awaitingLocalization: Boolean,
        val geoAnchors: List<AnchorRow>,
        /** True when the user tapped Test but the grid hasn't been created yet. */
        val testSpawnPending: Boolean,
        val testGrid: TestGridInfo?,
        val modelsInScene: Int,
        val modelPoses: Int,
        val optics: Optics? = null,
    )

    /** Max per-anchor rows listed; the rest are summarised so the panel can't cover the screen. */
    const val MAX_ANCHOR_ROWS = 5

    private const val OK = "✓"
    private const val WAIT = "…"
    private const val FAIL = "✗"
    private const val NA = "·"

    fun build(i: Input): String = buildString {
        appendLine("▶ NEXT: ${nextBlocker(i) ?: "no blockers — pins should be visible"}")

        // Session
        val sessMark = if (i.geospatialEnabled) OK else FAIL
        appendLine("$sessMark Session  geo=${onOff(i.geospatialEnabled)} depth=${onOff(i.depthEnabled)}")

        // Camera + feature tracking
        val camOk = i.cameraTracking == "TRACKING"
        val reason = i.cameraFailureReason?.takeIf { !camOk && it != "NONE" }?.let { " ($it)" } ?: ""
        val feats = if (i.featuresCounted) "planes ${i.planes} · points ${i.points}" else "planes – · points –"
        appendLine("${if (camOk) OK else FAIL} Camera   ${i.cameraTracking ?: "n/a"}$reason  $feats")
        i.optics?.let { o ->
            appendLine("$NA Optics   tex ${o.texW}x${o.texH} · cam FOV ${"%.0f".format(o.camHFovDeg)}°×" +
                "${"%.0f".format(o.camVFovDeg)}° · screen ${"%.0f".format(o.screenHFovDeg)}°×" +
                "${"%.0f".format(o.screenVFovDeg)}° (shows ${pct(o.shownH)}×${pct(o.shownV)} of image)")
        }

        // Earth (ARCore geospatial)
        val earthErr = i.earthState != null && i.earthState != "ENABLED"
        val earthMark = when {
            earthErr -> FAIL
            i.earthTracking == "TRACKING" -> OK
            else -> WAIT
        }
        appendLine("$earthMark Earth    ${i.earthTracking ?: "n/a"} [${i.earthState ?: "n/a"}]  " +
            "H${acc(i.earthHAccM)} V${acc(i.earthVAccM)} yaw${deg(i.earthYawDeg)}")

        // GNSS
        val f = i.fix
        if (f == null) {
            appendLine("$WAIT GNSS     no fix yet")
        } else {
            val gateBad = f.gateAccM != null && f.gateAccM > i.gnssGateMaxM
            val mark = when {
                !f.coordsValid -> FAIL
                gateBad -> FAIL
                else -> OK
            }
            val accText = when {
                f.hAccM != null -> acc(f.hAccM)
                f.gateAccM != null -> "~${"%.1f".format(f.gateAccM)}m (HDOP est.)"
                else -> "±?"
            }
            val age = f.ageMs?.let { " age ${"%.1f".format(it / 1000.0)}s" } ?: ""
            appendLine("$mark GNSS     ${f.provider} ${f.rtk} ${f.sats ?: "?"} sats $accText$age")
            appendLine("           ${"%.7f".format(f.lat)}, ${"%.7f".format(f.lon)}" +
                if (f.coordsValid) "" else "  ⚠ INVALID COORDS")
        }

        // Anchor gate + coordinate anchors
        val tracked = i.geoAnchors.count { it.tracking == "TRACKING" }
        val gateText = when {
            i.items == 0 -> "no coordinates in AR"
            i.geoAnchorsCreated -> "placed"
            i.awaitingLocalization -> "waiting for AR accuracy"
            else -> "waiting"
        }
        val anchorMark = when {
            i.items == 0 -> NA
            i.geoAnchorsCreated && i.geoAnchors.isNotEmpty() && tracked == i.geoAnchors.size -> OK
            i.geoAnchorsCreated && i.geoAnchors.isEmpty() -> FAIL
            else -> WAIT
        }
        appendLine("$anchorMark Anchors  ${i.items} coords (${i.itemsWithModels} w/ model) · gate: $gateText · " +
            "tracking $tracked/${i.geoAnchors.size}")

        // Test grid
        val tg = i.testGrid
        when {
            tg != null -> {
                val errors = tg.terrainStates.keys.any { it.startsWith("ERROR") }
                val mark = when {
                    errors -> FAIL
                    tg.tracking == tg.total && tg.total > 0 -> OK
                    else -> WAIT
                }
                // WGS84 (Stored) anchors report terrain state NONE — omit it there.
                val states = tg.terrainStates.filterKeys { it != "NONE" }
                    .entries.joinToString(" ") { "${it.key}×${it.value}" }
                val stateText = if (states.isEmpty()) "" else "  [$states]"
                appendLine("$mark Test     ${tg.mode ?: "?"} · tracking ${tg.tracking}/${tg.total}$stateText")
            }
            i.testSpawnPending -> appendLine("$WAIT Test     requested — waiting for Earth TRACKING + valid fix")
        }

        // Render
        val renderMark = when {
            i.modelPoses == 0 -> NA
            i.modelsInScene == i.modelPoses -> OK
            else -> WAIT
        }
        appendLine("$renderMark Render   models in scene ${i.modelsInScene}/${i.modelPoses}")

        if (i.camLat != null && i.camLon != null) {
            appendLine("📍 Cam ${"%.7f".format(i.camLat)}, ${"%.7f".format(i.camLon)}" +
                (i.camAlt?.let { "  alt ${"%.1f".format(it)}m" } ?: "") +
                (i.camHeading?.let { "  hdg ${"%.0f".format(it)}°" } ?: ""))
        }

        i.geoAnchors.take(MAX_ANCHOR_ROWS).forEachIndexed { idx, a ->
            val where = if (a.distM != null && a.distM >= 0)
                "${"%.1f".format(a.distM)}m @ ${"%.0f".format(a.bearingDeg ?: 0.0)}°" else "?m"
            val terrain = a.terrainState?.let { " terrain=$it" } ?: ""
            appendLine("  [$idx] ${a.name.take(18)}  ${a.tracking}  $where$terrain  ${a.model}")
        }
        if (i.geoAnchors.size > MAX_ANCHOR_ROWS) {
            appendLine("  … +${i.geoAnchors.size - MAX_ANCHOR_ROWS} more")
        }
    }.trimEnd()

    /**
     * The first stage, in pipeline order, that prevents pins from appearing — with a fix hint —
     * or null when nothing is blocking.
     */
    fun nextBlocker(i: Input): String? {
        if (!i.geospatialEnabled) {
            return "Geospatial not enabled — device unsupported or session config failed (see log)"
        }
        if (i.earthState != null && i.earthState != "ENABLED") {
            return "Earth ${i.earthState} — ${earthStateHint(i.earthState)}"
        }
        if (i.cameraTracking != "TRACKING") {
            val r = i.cameraFailureReason?.takeIf { it != "NONE" }
            return "Camera ${i.cameraTracking ?: "not started"}" +
                (r?.let { " ($it) — ${cameraHint(it)}" } ?: " — hold steady, aim at a textured surface")
        }
        if (i.earthTracking != "TRACKING") {
            return "Earth localizing — go outdoors, point camera at buildings/street (needs Street View coverage)"
        }
        val f = i.fix ?: return "No GNSS fix yet — check GNSS source / receiver connection"
        if (!f.coordsValid) {
            return "GNSS fix has invalid coords (${"%.5f".format(f.lat)}, ${"%.5f".format(f.lon)}) — " +
                "source/NMEA parse problem; anchors can't be placed"
        }
        val tg = i.testGrid
        if (i.items == 0 && tg == null) {
            return if (i.testSpawnPending) "Test grid requested — waiting to spawn"
                   else "No coordinates in AR — check visibility filter, or tap Test for a grid"
        }
        if (i.items > 0 && !i.geoAnchorsCreated) {
            if (f.gateAccM != null && f.gateAccM > i.gnssGateMaxM) {
                return "GNSS accuracy ${"%.1f".format(f.gateAccM)}m worse than ${"%.0f".format(i.gnssGateMaxM)}m gate"
            }
            if (i.awaitingLocalization) {
                return "Waiting for AR accuracy (H${acc(i.earthHAccM)} yaw${deg(i.earthYawDeg)}) — " +
                    "scan nearby buildings; places on timeout"
            }
        }
        if (tg != null) {
            tg.terrainStates.keys.firstOrNull { it.startsWith("ERROR") }?.let {
                return "Test terrain anchors $it — ${terrainHint(it)}"
            }
            if (tg.tracking == 0 && tg.total > 0) {
                return if (tg.mode == "STORED") "Test anchors not tracking yet — keep scanning"
                       else "Test anchors resolving terrain (${tg.terrainStates.keys.joinToString()}) — keep scanning"
            }
        }
        i.geoAnchors.firstOrNull { it.terrainState?.startsWith("ERROR") == true }?.let {
            return "Terrain anchor ${it.terrainState} — ${terrainHint(it.terrainState!!)}"
        }
        if (i.geoAnchorsCreated && i.geoAnchors.isNotEmpty() && i.geoAnchors.none { it.tracking == "TRACKING" }) {
            return "Anchors created but none tracking yet — keep scanning"
        }
        if (i.modelPoses > i.modelsInScene) {
            return "Models still loading (${i.modelsInScene}/${i.modelPoses})"
        }
        return null
    }

    internal fun earthStateHint(state: String): String = when (state) {
        "ERROR_NOT_AUTHORIZED" -> "check GOOGLE_MAPS_API_KEY, ARCore API enabled, key's package/SHA-1 restriction"
        "ERROR_RESOURCE_EXHAUSTED" -> "API key quota exceeded"
        "ERROR_APK_VERSION_TOO_OLD" -> "update Google Play Services for AR"
        "ERROR_GEOSPATIAL_MODE_DISABLED" -> "geospatial mode not enabled in session config"
        else -> "ARCore Earth error; see log"
    }

    internal fun cameraHint(reason: String): String = when (reason) {
        "INSUFFICIENT_LIGHT" -> "too dark"
        "EXCESSIVE_MOTION" -> "move slower"
        "INSUFFICIENT_FEATURES" -> "aim at a textured surface"
        "CAMERA_UNAVAILABLE" -> "another app is using the camera"
        "BAD_STATE" -> "ARCore bad state; restart AR"
        else -> "see log"
    }

    internal fun terrainHint(state: String): String = when (state) {
        "ERROR_NOT_AUTHORIZED" -> "API key not authorized for ARCore API"
        "ERROR_UNSUPPORTED_LOCATION" -> "no terrain data at this location (bad lat/lon?)"
        "ERROR_INTERNAL" -> "ARCore internal error; often bad input coords"
        else -> "see log"
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"
    private fun pct(f: Double) = "${"%.0f".format((f * 100).coerceIn(0.0, 999.0))}%"
    private fun acc(m: Double?) = m?.takeIf { it.isFinite() }?.let { "±${"%.1f".format(it)}m" } ?: "±?"
    private fun deg(d: Double?) = d?.takeIf { it.isFinite() }?.let { "±${"%.0f".format(it)}°" } ?: "±?"
}
