package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
import java.util.Locale

/**
 * Pure display formatting and label mapping for the coordinate detail screen.
 *
 * Extracted from [CoordinateDetailFragment] so the user-visible strings are unit-testable and
 * defined in one place. Contains NO Android views/Context — only `String`/number formatting.
 * All wording is preserved exactly from the previous in-fragment implementation; changing any of
 * these strings is a UI-wording change.
 */
object CoordinateDetailFormatter {

    fun fmt6(v: Double): String = String.format(Locale.US, "%.6f", v)
    fun fmt8(v: Double): String = String.format(Locale.US, "%.8f", v)
    fun fmtM2(v: Double): String = String.format(Locale.US, "%.2f m", v)
    fun fmtM3(v: Double): String = String.format(Locale.US, "%.3f m", v)
    fun fmtDop(v: Double): String = String.format(Locale.US, "%.1f", v)

    fun rtkLabel(s: String?): String = when (s?.uppercase(Locale.US)) {
        "FIX"    -> "Fix (RTK)"
        "FLOAT"  -> "Float (RTK)"
        "DGPS"   -> "DGPS"
        "SINGLE" -> "Single"
        else     -> s ?: "--"
    }

    fun captureMethodLabel(m: String?): String? = when (m?.lowercase(Locale.US)) {
        "internal_gps"   -> "Internal GPS"
        "external_gnss"  -> "External GNSS"
        "external_gnss_pole_tip" -> "External GNSS (receiver tilt-compensated pole tip; app pole height not applied)"
        "external_gnss_reference_unconfirmed" -> "External GNSS (position reference unconfirmed; antenna reduction applied)"
        "rtk_receiver"   -> "RTK Receiver"
        "model_embedded" -> "Model embedded location"
        "map_tap"        -> "Map tap"
        "manual"         -> "Manual entry"
        "imported"       -> "Imported"
        "averaged"       -> "Averaged"
        null, ""         -> null
        else             -> m
    }

    fun providerLabel(p: String?): String? = when (p?.lowercase(Locale.US)) {
        "fused"        -> "Internal GPS (fused)"
        "external-tcp" -> "External GNSS (TCP)"
        "external-bt"  -> "External GNSS (Bluetooth)"
        "replay"       -> "NMEA replay"
        // Tokens written before the receiver-neutral rename: same labels as before.
        "rs2-tcp"      -> "External GNSS (TCP)"
        "rs2-bt"       -> "External GNSS (Bluetooth)"
        "rs2-external" -> "External GNSS"
        "model"        -> "Model"
        null, "", "other" -> null
        else           -> p
    }

    fun fmtDistance(m: Double): String = when {
        m < 1000.0 -> String.format(Locale.US, "%.1f m", m)
        else       -> String.format(Locale.US, "%.2f km", m / 1000.0)
    }

    fun cardinalDir(deg: Double): String = when {
        deg < 22.5 || deg >= 337.5 -> "N"
        deg < 67.5  -> "NE"
        deg < 112.5 -> "E"
        deg < 157.5 -> "SE"
        deg < 202.5 -> "S"
        deg < 247.5 -> "SW"
        deg < 292.5 -> "W"
        else        -> "NW"
    }

    /** Horizontal-accuracy badge text, e.g. "H ±0.42 m". Precision tightens for small values. */
    fun accuracyBadgeText(hAccM: Double): String = when {
        hAccM < 1.0  -> String.format(Locale.US, "H ±%.2f m", hAccM)
        hAccM < 10.0 -> String.format(Locale.US, "H ±%.1f m", hAccM)
        else         -> String.format(Locale.US, "H ±%.0f m", hAccM)
    }

    // ── Calculated display values (derived from saved data; never stored) ───────────────

    /**
     * Averaged-capture sample rate, e.g. "2.5 fixes/sec". Null when inputs are missing or the
     * duration is non-positive (avoids divide-by-zero).
     */
    fun captureRateText(samples: Int?, durationMs: Long?): String? {
        if (samples == null || durationMs == null || durationMs <= 0L) return null
        val rate = samples / (durationMs / 1000.0)
        return String.format(Locale.US, "%.1f fixes/sec", rate)
    }

    /**
     * Satellite usage summary: "18 used / 24 visible", or "18 used" / "24 visible" when only one is
     * known. Null when neither is available.
     */
    fun satelliteSummaryText(satsUsed: Int?, satsVisible: Int?): String? = when {
        satsUsed != null && satsVisible != null -> "$satsUsed used / $satsVisible visible"
        satsUsed != null  -> "$satsUsed used"
        satsVisible != null -> "$satsVisible visible"
        else -> null
    }

    /**
     * Differential/RTK correction freshness, e.g. "Fresh, 1.2 s old" for recent corrections or
     * "12.5 s old" for older ones. Null when missing or negative.
     */
    fun correctionFreshnessText(correctionAgeS: Double?): String? {
        if (correctionAgeS == null || correctionAgeS < 0.0) return null
        val age = String.format(Locale.US, "%.1f s old", correctionAgeS)
        return if (correctionAgeS <= FRESH_CORRECTION_AGE_S) "Fresh, $age" else age
    }

    /**
     * One-line survey-quality summary from fix status (primary) and accuracy (fallback), e.g.
     * "RTK fix · survey grade", "RTK float · sub-meter", or "Approximate". Null when nothing is known.
     */
    fun surveyQualitySummaryText(rtkStatus: String?, horizontalAccuracyM: Double?, hdop: Double?): String? {
        if (rtkStatus.isNullOrBlank() && horizontalAccuracyM == null && hdop == null) return null
        return when (rtkStatus?.uppercase(Locale.US)) {
            "FIX"    -> "RTK fix · survey grade"
            "FLOAT"  -> "RTK float · sub-meter"
            "DGPS"   -> "DGPS · meter-level"
            "SINGLE" -> "Single · approximate"
            else -> when {
                horizontalAccuracyM != null && horizontalAccuracyM < 0.05 -> "Survey grade"
                horizontalAccuracyM != null && horizontalAccuracyM < 0.5  -> "Sub-meter"
                horizontalAccuracyM != null && horizontalAccuracyM < 2.0  -> "Meter-level"
                else -> "Approximate"
            }
        }
    }

    /**
     * Compact model-placement summary, including only the fields that are present, e.g.
     * "scale 1.50× · yaw 90° CCW · v-offset 1.50 m · origin (0.10, 0.20, 0.30) m". Null when no placement
     * override is set.
     */
    fun modelPlacementSummaryText(
        scale: Double?, yawDeg: Double?, pitchDeg: Double?, rollDeg: Double?,
        verticalOffsetM: Double?,
        originOffsetXM: Double?, originOffsetYM: Double?, originOffsetZM: Double?
    ): String? {
        val parts = buildList {
            scale?.let { add(String.format(Locale.US, "scale %.2f×", it)) }
            // Yaw is counterclockwise from north (viewed from above) — say so where it is shown.
            yawDeg?.let { add(String.format(Locale.US, "yaw %.0f° CCW", it)) }
            pitchDeg?.let { add(String.format(Locale.US, "pitch %.0f°", it)) }
            rollDeg?.let { add(String.format(Locale.US, "roll %.0f°", it)) }
            verticalOffsetM?.let { add(String.format(Locale.US, "v-offset %.2f m", it)) }
            if (originOffsetXM != null || originOffsetYM != null || originOffsetZM != null) {
                add(String.format(
                    Locale.US, "origin (%.2f, %.2f, %.2f) m",
                    originOffsetXM ?: 0.0, originOffsetYM ?: 0.0, originOffsetZM ?: 0.0
                ))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    // ── Location card labels ───────────────────────────────────────────────────────────

    /** "Lat / Lon · WGS84 (ITRF2014)" — a dot separator, so labels never nest brackets. */
    fun qualifiedLabel(name: String, reference: String): String = "$name · $reference"

    /** The separate receiver-orthometric-height row is redundant when the Height row already shows it. */
    fun showReceiverMslRow(vertical: app.surrealar.settings.model.VerticalReference): Boolean =
        vertical != app.surrealar.settings.model.VerticalReference.RECEIVER_MSL

    /**
     * "Height model" row for a beta height (GEOID2022), null for every other height: model, NGS dataset
     * version, epoch, vertical datum, the ITRF2020 basis and the beta caution.
     */
    fun betaHeightModelText(p: app.surrealar.coords.ConvertedPosition): String? {
        val note = p.heightModelNote ?: return null
        return "$note · NAPGD2022 · H = h(ITRF2020) − N at that epoch. ${app.surrealar.coords.Geoid2022Labels.CAUTION}"
    }

    /** Label of the beta height-model row. */
    const val BETA_HEIGHT_MODEL = "Height model (beta)"

    /** A saved point's heights are reduced to the ground mark at capture (pole + phase-centre offset). */
    const val HEIGHT_AT_MARK = "Height at mark"
    /** The receiver's orthometric height (NMEA "MSL"); NMEA does not say which geoid model it used. */
    const val RECEIVER_ORTHOMETRIC_HEIGHT = "H at mark (receiver geoid, model unknown)"
    /** The receiver's geoid undulation N (h = H + N), from the same unknown model. */
    const val RECEIVER_GEOID_SEPARATION = "N (receiver geoid, model unknown)"

    /**
     * Label of the receiver's H row: [RECEIVER_ORTHOMETRIC_HEIGHT] while the model is unknown (NULL, all
     * receiver captures today), "H at mark (receiver geoid, EGM96)" once a model is recorded.
     */
    fun receiverOrthometricHeightLabel(orthometricModel: String?): String =
        orthometricModel?.let { "H at mark (receiver geoid, ${geoidModelName(it)})" } ?: RECEIVER_ORTHOMETRIC_HEIGHT

    /** Label of the receiver's N row, like [receiverOrthometricHeightLabel]. */
    fun receiverGeoidSeparationLabel(orthometricModel: String?): String =
        orthometricModel?.let { "N (receiver geoid, ${geoidModelName(it)})" } ?: RECEIVER_GEOID_SEPARATION

    // ── Coordinate provenance (v14 columns). NULL = unknown: shown as "unknown", never guessed. ───

    const val UNKNOWN = "unknown"

    /** A stored frame token as its name ("WGS84 (ITRF2014)", "NAD83(2011)"); NULL → "unknown". */
    fun frameLabel(token: String?): String = when (token) {
        null -> UNKNOWN
        else -> io.github.pennstatecs.geokit.datum.ReferenceFrame.fromId(token)?.displayName ?: "$UNKNOWN ($token)"
    }

    /** A coordinate epoch as a decimal year ("2026.886"); NULL → "unknown". */
    fun coordinateEpochLabel(epoch: Double?): String =
        epoch?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.3f", it) } ?: UNKNOWN

    /** Where the stored orthometric height (altitudeMsl) came from; NULL → "unknown". */
    fun orthometricHeightSourceLabel(source: String?): String = when (source) {
        null -> UNKNOWN
        app.surrealar.domain.model.OrthometricHeightSource.RECEIVER_GGA -> "External receiver (NMEA GGA)"
        app.surrealar.domain.model.OrthometricHeightSource.DEVICE_GNSS -> "Device GNSS"
        app.surrealar.domain.model.OrthometricHeightSource.COMPUTED -> "Computed in the app"
        else -> "$UNKNOWN ($source)"
    }

    /** The geoid model of the stored orthometric height; NULL → "model unknown" (receivers do not report it). */
    fun orthometricModelLabel(model: String?): String = model?.let { geoidModelName(it) } ?: "model unknown"

    private fun geoidModelName(token: String): String =
        io.github.pennstatecs.geokit.geoid.GeoidModelId.fromToken(token)?.token ?: "$UNKNOWN ($token)"

    /** One provenance row (label, value). */
    data class ProvenanceRow(val label: String, val value: String)

    /** Label of the Point Details "Position reference" row. */
    const val POSITION_REFERENCE = "Position reference"

    /**
     * Compact "Position reference" value for an external capture (Phase 4 stabilization WP A), or null when the
     * point is not an external receiver capture (internal, manual, imported, model, simulator). States which
     * physical point the stored position is and what the app applied, plus the receiver's compensation status
     * where it is known. Never claims a verified pole tip or survey-grade accuracy.
     */
    fun positionReferenceText(captureMethod: String?, provider: String?): String? =
        when (app.surrealar.domain.model.PositionReferenceKind.of(
            captureMethod, app.surrealar.data.local.db.ProviderTokens.isExternal(provider))) {
            app.surrealar.domain.model.PositionReferenceKind.ANTENNA -> "Antenna (pole height applied)"
            app.surrealar.domain.model.PositionReferenceKind.RECEIVER_POLE_TIP ->
                "Receiver pole tip (compensated, no pole height applied) · receiver tilt compensation was active; pole height from Emlid Flow"
            app.surrealar.domain.model.PositionReferenceKind.UNCONFIRMED ->
                "Unconfirmed — may be reduced twice · antenna reduction applied; receiver compensated output not confirmed"
            null -> null
        }

    /** [provenanceRows] preceded by the [POSITION_REFERENCE] row when the point has one. */
    fun provenanceRowsWithReference(c: Coordinate): List<ProvenanceRow> =
        listOfNotNull(positionReferenceText(c.captureMethod, c.provider)?.let { ProvenanceRow(POSITION_REFERENCE, it) }) +
            provenanceRows(c)

    /**
     * Provenance rows for the detail screen: source frame, coordinate epoch, receiver frame, orthometric
     * height source and its geoid model. Every row is always present, with "unknown" for NULL (legacy,
     * manual or imported points), so absence of metadata is visible rather than guessed.
     */
    fun provenanceRows(c: Coordinate): List<ProvenanceRow> = listOf(
        ProvenanceRow("Source frame", frameLabel(c.sourceFrame)),
        ProvenanceRow("Coordinate epoch", coordinateEpochLabel(c.coordinateEpoch)),
        ProvenanceRow("Receiver frame", frameLabel(c.receiverFrame)),
        ProvenanceRow("Orthometric height source", orthometricHeightSourceLabel(c.orthometricHeightSource)),
        ProvenanceRow("Orthometric height model", orthometricModelLabel(c.orthometricModel)),
    )

    // ── Compact values for the Survey/GNSS stat tiles ──────────────────────────────────

    /** Short fix-status label for a stat tile: "Fix", "Float", "DGPS", "Single", … Null when absent. */
    fun fixShortLabel(rtkStatus: String?): String? = when (rtkStatus?.uppercase(Locale.US)) {
        null, ""  -> null
        "FIX"     -> "Fix"
        "FLOAT"   -> "Float"
        "DGPS"    -> "DGPS"
        "SINGLE"  -> "Single"
        "NONE"    -> "No fix"
        "INVALID" -> "Invalid"
        else      -> rtkStatus
    }

    /** Fix-tile value: "RTK Fix" for a fixed RTK solution, otherwise the short label. Null when absent. */
    fun fixTileValue(rtkStatus: String?): String? {
        val short = fixShortLabel(rtkStatus) ?: return null
        return if (rtkStatus?.uppercase(Locale.US) == "FIX") "RTK Fix" else short
    }

    /**
     * Compact accuracy value for a stat tile, e.g. "±0.42 m", or "±0.42 m (68%)" with a
     * [confidence] tag. Null when absent.
     */
    fun accuracyTileText(m: Double?, confidence: String? = null): String? {
        val v = when {
            m == null    -> return null
            m < 1.0      -> String.format(Locale.US, "±%.2f m", m)
            m < 10.0     -> String.format(Locale.US, "±%.1f m", m)
            else         -> String.format(Locale.US, "±%.0f m", m)
        }
        return if (confidence.isNullOrBlank()) v else "$v ($confidence)"
    }

    /** hAcc is a 68% horizontal radius. */
    const val HORIZONTAL_CONFIDENCE = "68%"
    /** Vertical accuracy is 1σ. */
    const val VERTICAL_CONFIDENCE = "1σ"

    /** Compact satellite count for a stat tile, e.g. "18/24", "18", or "24". Null when neither is known. */
    fun satellitesTileText(satsUsed: Int?, satsVisible: Int?): String? = when {
        satsUsed != null && satsVisible != null -> "$satsUsed/$satsVisible"
        satsUsed != null    -> "$satsUsed"
        satsVisible != null -> "$satsVisible"
        else -> null
    }

    /**
     * Note shown under the Capture card for points from the device's own GNSS. Decided by the
     * provider (an averaged capture can come from an external receiver); a legacy "internal_gps"
     * capture method counts too unless the provider says external.
     */
    fun internalGpsNote(provider: String?, captureMethod: String?): String? {
        val prov = app.surrealar.data.local.db.ProviderTokens.fromDomain(provider)
        val internal = prov == io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL ||
            (captureMethod?.trim()?.lowercase(Locale.US) == "internal_gps" &&
                !app.surrealar.data.local.db.ProviderTokens.isExternal(provider))
        return if (internal) "Captured with Internal GPS. Accuracy may be lower than with an external GNSS receiver." else null
    }

    /** Satellites tile label: "Satellites" over "used/visible", or "Satellites used" when only that is known. */
    fun satellitesTileLabel(satsUsed: Int?, satsVisible: Int?): String =
        if (satsUsed != null && satsVisible == null) "Satellites used" else "Satellites"

    /** Human label for the stored timestamp source (TimestampSource enum name). */
    fun timeSourceLabel(raw: String?): String = when (raw?.trim()?.uppercase(Locale.US)) {
        "NMEA_ZDA"      -> "Receiver UTC (ZDA)"
        "GNSS_PROVIDER" -> "Device GNSS clock"
        "DEVICE"        -> "Device clock"
        else            -> "Unknown"
    }

    /**
     * Read-only multi-line location summary for the Edit dialog: latitude, longitude, altitude, and
     * UTM (only when available). Falls back to a friendly message rather than showing "null"/"NaN"
     * when the position is not finite.
     */
    fun locationSummary(c: Coordinate): String {
        if (!c.latitude.isFinite() || !c.longitude.isFinite()) {
            return "No saved location details available"
        }
        val lines = mutableListOf(
            "Latitude: ${fmt6(c.latitude)}°",
            "Longitude: ${fmt6(c.longitude)}°",
        )
        if (c.altitude.isFinite()) lines += "Ellipsoidal height h: ${fmtM2(c.altitude)}"
        val e = c.easting; val n = c.northing; val zone = c.utmZone
        if (e != null && n != null && !zone.isNullOrBlank()) {
            lines += "UTM: $zone ${String.format(Locale.US, "%.3f", e)} E, ${String.format(Locale.US, "%.3f", n)} N"
        }
        return lines.joinToString("\n")
    }

    /** Whether the audit "Record history" rows add information beyond the captured timestamp. */
    data class RecordHistory(val showCreated: Boolean, val showUpdated: Boolean) {
        val anyShown: Boolean get() = showCreated || showUpdated
    }

    /**
     * Decides whether Created / Last-updated should be shown, hiding values that merely duplicate the
     * captured time (epoch-minute comparison, timezone-independent):
     *  - Created hidden when it is the same minute as Captured (shown as a fallback when there is no
     *    captured timestamp).
     *  - Last updated shown only when it is later than Created and not in the same minute.
     */
    fun recordHistoryVisibility(capturedMs: Long, createdAt: Long, updatedAt: Long): RecordHistory {
        fun sameMinute(a: Long, b: Long): Boolean = a > 0L && b > 0L && a / 60_000L == b / 60_000L
        val showCreated = createdAt > 0L && !sameMinute(createdAt, capturedMs)
        val showUpdated = updatedAt > 0L && updatedAt > createdAt && !sameMinute(updatedAt, createdAt)
        return RecordHistory(showCreated, showUpdated)
    }

    /** Corrections at or below this age (seconds) are labeled "Fresh". */
    private const val FRESH_CORRECTION_AGE_S = 5.0
}
