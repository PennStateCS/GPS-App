package com.example.surveyingapp.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.surveyingapp.data.location.nmea.parser.NmeaParser
import com.example.surveyingapp.gnss.model.SkyGeometry
import com.example.surveyingapp.gnss.model.Constellation
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/**
 * Skyplot polar chart for GNSS satellites with optional trails.
 *
 * - Azimuth around the circle (0° = North/up, clockwise)
 * - Elevation as concentric circles (edge = 0°, center = 90°)
 * - Fill color encodes SNR strength (green/yellow/red) for USED sats, gray for NOT USED
 * - Stroke color encodes constellation (GPS/GLO/GAL/Bei/QZSS/SBAS)
 * - White halo indicates satellites used in the current fix
 * - Trails show recent satellite motion, fading with age
 * - Smarter, selective labeling to avoid clutter (used || strong || high elevation)
 */
class SkyplotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusable = true
    }

    // ==== Public API ====
    var onSatelliteClick: ((satellite: NmeaParser.Satellite, usedInFix: Boolean) -> Unit)? = null

    /** Show legend at the top-left gutter. */
    var showLegend: Boolean = true
        set(v) { field = v; invalidate() }

    /** Elevation rings to draw (degrees). */
    var elevationRings: List<Int> = listOf(0, 30, 60)
        set(v) { field = v.sorted(); invalidate() }

    /** SNR thresholds (dB-Hz) used for USED satellites. */
    var snrGreen: Int = 35
    var snrYellow: Int = 30

    /** Trails configuration. */
    var trailsEnabled: Boolean = true
        set(v) { field = v; invalidate() }
    var trailWindowMs: Long = 5 * 60 * 1000L // 5 minutes
        set(v) { field = v; pruneAllTrails(); invalidate() }
    var trailMinGapMs: Long = 900L
    var trailMaxPointsPerSat: Int = 600
    var trailStrokeWidthDp: Float = 1.25f

    @Deprecated("Use setGeometry() from SkyBus")
    fun setSatelliteData(list: List<NmeaParser.Satellite>, usedIds: Set<Int>) {
        satellites = list
        used = usedIds
        // --- Normalize SBAS both directions: 120..158 <-> 33..64 (NMEA mapping) ---
        usedNorm = usedIds.flatMap { id ->
            when (id) {
                in 120..158 -> listOf(id, id - 87)
                in 33..64   -> listOf(id, id + 87)
                else        -> listOf(id)
            }
        }.toSet()

        if (trailsEnabled) updateTrails(list)
        invalidate()
    }

    fun setGeometry(geoms: List<SkyGeometry>) {
        // Store geometry for touch handling
        skyGeometry = geoms

        // Convert SkyGeometry to NmeaParser.Satellite format for existing rendering
        satellites = geoms.mapNotNull { geom ->
            // Only include satellites with position data (azDeg and elDeg)
            if (geom.azDeg != null && geom.elDeg != null) {
                NmeaParser.Satellite(
                    prn = geom.svid,
                    constellation = translateConstellationToNmea(geom.constellation),
                    elevationDeg = geom.elDeg.toInt(),
                    azimuthDeg = geom.azDeg.toInt(),
                    snrDb = geom.snrDbHz?.toInt()
                )
            } else null
        }

        // Extract used satellite IDs from usedInFix
        used = geoms.filter { it.usedInFix }.map { it.svid }.toSet()

        // Normalize SBAS IDs
        usedNorm = used.flatMap { id ->
            when (id) {
                in 120..158 -> listOf(id, id - 87)
                in 33..64   -> listOf(id, id + 87)
                else        -> listOf(id)
            }
        }.toSet()

        if (trailsEnabled) updateTrails(satellites)
        invalidate()
    }

    // ==== Data ====
    private var satellites: List<NmeaParser.Satellite> = emptyList()
    private var used: Set<Int> = emptySet()
    private var usedNorm: Set<Int> = emptySet()
    private var skyGeometry: List<SkyGeometry> = emptyList() // Store for touch handling

    // Trails storage: per-satellite ring buffer of recent samples
    private data class TrailSample(
        val az: Float,
        val el: Float,
        val t: Long,
        val snr: Int,
        val used: Boolean,
        val constellation: NmeaParser.Constellation
    )
    private val trails: MutableMap<Int, ArrayDeque<TrailSample>> = mutableMapOf()

    // ==== Paints ====
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF") // 50% white
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF") // 20% white
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDFFFFFF")
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val satFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val satStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val usedHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
        alpha = 190
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(trailStrokeWidthDp)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // ==== Colors (SNR) ====
    private val colorGreen = Color.parseColor("#4CAF50")
    private val colorYellow = Color.parseColor("#FFC107")
    private val colorRed = Color.parseColor("#F44336")
    private val colorGray = Color.parseColor("#9E9E9E")

    // ==== Touch state ====
    private data class HitDot(
        val x: Float,
        val y: Float,
        val hitR: Float,
        val sat: NmeaParser.Satellite,
        val isUsed: Boolean
    )
    private val hitDots = mutableListOf<HitDot>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isMaybeClick = false

    // ==== Measure ====
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(800f).toInt() // tablet-friendly default
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ==== Draw ====
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Theme-aware colors for light/dark
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isNight) {
            axisPaint.color = Color.parseColor("#80FFFFFF")
            gridPaint.color = Color.parseColor("#33FFFFFF")
            textPaint.color = Color.parseColor("#DDFFFFFF")
        } else {
            axisPaint.color = Color.parseColor("#80000000")
            gridPaint.color = Color.parseColor("#33000000")
            textPaint.color = Color.parseColor("#DD000000")
        }

        val padding = dp(18f)
        val plotTop = padding
        val availW = width - padding * 2
        val availH = height - plotTop - padding

        hitDots.clear()
        if (availW <= 0f || availH <= 0f) return

        val off = dp(14f)
        val topRoom = off + textPaint.textSize
        val bottomRoom = off + textPaint.textSize
        val halfH = availH / 2f
        val heightRadius = max(0f, min(halfH - topRoom, halfH - bottomRoom))
        val widthRadius = availW / 2f
        val radius = min(widthRadius, heightRadius)
        val cx = width / 2f
        val cy = plotTop + halfH

        // Legend in left gutter
        if (showLegend) drawLegendLeft(canvas, cx, cy, radius, padding)

        drawSkyplot(canvas, cx, cy, radius)
    }

    private fun drawLegendLeft(canvas: Canvas, cx: Float, cy: Float, radius: Float, padding: Float) {
        val leftGutterLeft = padding
        val leftGutterRight = cx - radius
        val gutterW = (leftGutterRight - leftGutterLeft).coerceAtLeast(0f)
        if (gutterW <= dp(24f)) return

        val entries = listOf(
            colorGreen to "≥35 dB-Hz",
            colorYellow to "30–34",
            colorRed to "<30",
            colorGray to "not used"
        )

        val box = dp(10f)
        val gap = dp(6f)
        val itemGapY = dp(8f)

        val oldSize = textPaint.textSize
        val oldAlign = textPaint.textAlign
        textPaint.textAlign = Paint.Align.LEFT
        val maxLabelW = entries.maxOf { (_, label) -> textPaint.measureText(label) }
        val availableTextW = (gutterW - box - gap).coerceAtLeast(dp(16f))
        if (maxLabelW > availableTextW) {
            val scale = (availableTextW / maxLabelW).coerceIn(0.6f, 1f)
            textPaint.textSize = oldSize * scale
        }

        val lineH = max(box, textPaint.textSize)
        val totalH = entries.size * lineH + (entries.size - 1) * itemGapY
        val yStart = (cy - totalH / 2f).coerceAtLeast(padding)
        val xBox = leftGutterLeft
        val xText = xBox + box + gap

        var y = yStart
        entries.forEach { (color, label) ->
            satFill.color = color
            val yBox = y + (lineH - box) / 2f
            canvas.drawRect(xBox, yBox, xBox + box, yBox + box, satFill)
            canvas.drawRect(xBox, yBox, xBox + box, yBox + box, axisPaint)
            val yText = y + lineH - dp(2f)
            canvas.drawText(label, xText, yText, textPaint)
            y += lineH + itemGapY
        }

        textPaint.textSize = oldSize
        textPaint.textAlign = oldAlign
    }

    private fun drawSkyplot(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (radius <= 0f) return

        // Elevation rings
        elevationRings.forEach { elev ->
            val r = radius * (90f - elev) / 90f
            canvas.drawCircle(cx, cy, r, gridPaint)
            if (elev != 0) {
                canvas.drawText("${elev}°", cx, cy - r + textPaint.textSize, textPaint)
            }
        }
        // Center marker (90°)
        canvas.drawCircle(cx, cy, dp(2f), axisPaint)
        canvas.drawText("90°", cx, cy - dp(4f), textPaint)

        // Radial azimuth lines every 30°
        for (az in 0 until 360 step 30) {
            val rad = Math.toRadians(az.toDouble())
            val x = cx + radius * sin(rad).toFloat() // az=0 up
            val y = cy - radius * cos(rad).toFloat()
            canvas.drawLine(cx, cy, x, y, axisPaint)
        }
        drawCardinals(canvas, cx, cy, radius)

        // Trails behind dots
        if (trailsEnabled) drawTrails(canvas, cx, cy, radius)

        // Dots + labels
        val placedLabels = mutableListOf<RectF>()

        // Separate satellites into those with and without position data
        val satsWithPosition = mutableListOf<NmeaParser.Satellite>()
        val satsWithoutPosition = mutableListOf<NmeaParser.Satellite>()

        satellites.forEach { sat ->
            if (sat.azimuthDeg != null && sat.elevationDeg != null) {
                satsWithPosition.add(sat)
            } else {
                satsWithoutPosition.add(sat)
            }
        }

        // Draw satellites with known positions on skyplot
        satsWithPosition.forEach { sat ->
            val az = sat.azimuthDeg!!
            val el = sat.elevationDeg!!
            val snrRaw = sat.snrDb
            val snr = (snrRaw ?: 0).coerceIn(0, 50)

            val azRad = Math.toRadians(az.toDouble())
            val r = radius * (90.0 - el.toDouble()) / 90.0
            val sx = cx + r.toFloat() * sin(azRad).toFloat()
            val sy = cy - r.toFloat() * cos(azRad).toFloat()

            val usedNow = isUsedSat(sat)
            if (usedNow) canvas.drawCircle(sx, sy, dp(3.5f) + dp(2.5f) * (snr / 50f) + dp(1.5f), usedHalo)

            // Fill color: UNUSED => gray; USED => SNR color
            val fillColor = if (!usedNow) {
                colorGray
            } else {
                if (snrRaw == null || snr <= 0) colorGray else when {
                    snr >= snrGreen -> colorGreen
                    snr >= snrYellow -> colorYellow
                    else -> colorRed
                }
            }
            satFill.color = fillColor
            satFill.alpha = (120 + (snr * 2)).coerceIn(120, 255)

            // Stroke by constellation
            satStroke.color = constellationStrokeColor(sat.constellation)

            // Dot radius grows slightly with SNR (3.5dp..6dp)
            val dotR = dp(3.5f) + dp(2.5f) * (snr / 50f)

            // Draw dot + stroke
            canvas.drawCircle(sx, sy, dotR, satFill)
            canvas.drawCircle(sx, sy, dotR, satStroke)

            // Touch target
            val hitR = dp(20f)
            hitDots.add(HitDot(sx, sy, hitR, sat, usedNow))

            // Selective labeling
            val shouldLabel = usedNow || snr >= snrGreen || (el >= 45)
            if (shouldLabel) {
                placeLabel(canvas, placedLabels, prnLabel(sat), sx, sy - dotR - dp(4f))
            }
        }

        // Draw satellites without position data in a side panel or status area
        if (satsWithoutPosition.isNotEmpty()) {
            drawSatellitesWithoutPosition(canvas, cx, cy, radius, satsWithoutPosition)
        }
    }

    // Draw satellites that don't have azimuth/elevation in a side area
    private fun drawSatellitesWithoutPosition(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        satellites: List<NmeaParser.Satellite>
    ) {
        // Position the list to the right of the skyplot
        val listX = cx + radius + dp(20f)
        val listY = cy - radius + dp(20f)
        val itemHeight = dp(20f)

        // Draw header
        val oldAlign = textPaint.textAlign
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("No Position:", listX, listY, textPaint)

        satellites.forEachIndexed { index, sat ->
            val y = listY + (index + 1) * itemHeight
            val snrRaw = sat.snrDb
            val snr = (snrRaw ?: 0).coerceIn(0, 50)
            val usedNow = isUsedSat(sat)

            // Small dot for satellite
            val fillColor = if (!usedNow) {
                colorGray
            } else {
                if (snrRaw == null || snr <= 0) colorGray else when {
                    snr >= snrGreen -> colorGreen
                    snr >= snrYellow -> colorYellow
                    else -> colorRed
                }
            }

            satFill.color = fillColor
            satStroke.color = constellationStrokeColor(sat.constellation)

            val dotR = dp(4f)
            canvas.drawCircle(listX + dotR, y - dotR, dotR, satFill)
            canvas.drawCircle(listX + dotR, y - dotR, dotR, satStroke)

            // Label
            val label = "${prnLabel(sat)} ${if (snrRaw != null) "${snrRaw}dB" else "?"}"
            canvas.drawText(label, listX + dotR * 3, y, textPaint)

            // Touch target
            hitDots.add(HitDot(listX + dotR, y - dotR, dp(15f), sat, usedNow))
        }

        textPaint.textAlign = oldAlign
    }

    private fun drawCardinals(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val off = dp(14f)
        canvas.drawText("N", cx, cy - r - off, textPaint)
        canvas.drawText("S", cx, cy + r + off, textPaint)
        canvas.drawText("E", cx + r + off, cy + textPaint.textSize / 3f, textPaint)
        canvas.drawText("W", cx - r - off, cy + textPaint.textSize / 3f, textPaint)
    }

    // --- Trails implementation ---
    private fun updateTrails(list: List<NmeaParser.Satellite>) {
        val now = SystemClock.elapsedRealtime()
        list.forEach { s ->
            val az = s.azimuthDeg ?: return@forEach
            val el = s.elevationDeg ?: return@forEach
            val key = uniqueKey(s)
            val dq = trails.getOrPut(key) { ArrayDeque() }
            val lastT = dq.lastOrNull()?.t ?: Long.MIN_VALUE
            if (now - lastT < trailMinGapMs) return@forEach

            val snr = (s.snrDb ?: -1).coerceAtLeast(-1)
            val sample = TrailSample(
                az = az.toFloat(),
                el = el.toFloat(),
                t = now,
                snr = snr,
                used = isUsedSat(s),
                constellation = s.constellation
            )
            dq.addLast(sample)
            while (dq.size > trailMaxPointsPerSat) dq.removeFirst()
            trimDequeByTime(dq, now)
        }
        val cutoff = now - trailWindowMs
        trails.entries.removeAll { it.value.lastOrNull()?.t ?: Long.MIN_VALUE < cutoff }
    }

    private fun pruneAllTrails() {
        val now = SystemClock.elapsedRealtime()
        trails.values.forEach { trimDequeByTime(it, now) }
        val cutoff = now - trailWindowMs
        trails.entries.removeAll { it.value.lastOrNull()?.t ?: Long.MIN_VALUE < cutoff }
    }

    private fun trimDequeByTime(dq: ArrayDeque<TrailSample>, now: Long) {
        val cutoff = now - trailWindowMs
        while (dq.isNotEmpty() && dq.first().t < cutoff) dq.removeFirst()
    }

    private fun drawTrails(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (trails.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val breakGap = trailMinGapMs * 4
        trailPaint.strokeWidth = dp(trailStrokeWidthDp)

        trails.values.forEach { dq ->
            if (dq.size < 2) return@forEach
            var prev = dq.first()
            var prevX: Float? = null
            var prevY: Float? = null

            dq.forEach { cur ->
                val ageFrac = ((now - cur.t).toFloat() / trailWindowMs).coerceIn(0f, 1f)
                val t = (1f - ageFrac)
                val gamma = 1.4f
                val fade = Math.pow(t.toDouble(), gamma.toDouble()).toFloat()

                val baseColor = when {
                    cur.snr < 0 -> colorGray
                    cur.snr >= snrGreen -> colorGreen
                    cur.snr >= snrYellow -> colorYellow
                    else -> colorRed
                }
                val alpha = (40 + (160 * fade) + if (cur.used) 20 else 0).toInt().coerceIn(0, 255)
                trailPaint.color = baseColor
                trailPaint.alpha = alpha

                if (cur.t - prev.t > breakGap) {
                    prev = cur
                    prevX = null
                    prevY = null
                    return@forEach
                }

                val prevRad = Math.toRadians(prev.az.toDouble())
                val prevR = radius * (90.0 - prev.el.toDouble()) / 90.0
                val x1 = cx + prevR.toFloat() * sin(prevRad).toFloat()
                val y1 = cy - prevR.toFloat() * cos(prevRad).toFloat()

                val curRad = Math.toRadians(cur.az.toDouble())
                val curR = radius * (90.0 - cur.el.toDouble()) / 90.0
                val x2 = cx + curR.toFloat() * sin(curRad).toFloat()
                val y2 = cy - curR.toFloat() * cos(curRad).toFloat()

                if (prevX != null && prevY != null) {
                    canvas.drawLine(prevX!!, prevY!!, x2, y2, trailPaint)
                }

                prev = cur
                prevX = x2
                prevY = y2
            }
        }
    }

    // ==== Labeling helpers ====
    private fun placeLabel(canvas: Canvas, placed: MutableList<RectF>, text: String, tx: Float, ty: Float) {
        val w = textPaint.measureText(text)
        val h = textPaint.textSize
        var bounds = RectF(tx - w / 2f, ty - h, tx + w / 2f, ty + dp(2f))

        fun collides(b: RectF) = placed.any { RectF.intersects(it, b) }

        if (collides(bounds)) {
            val offsets = arrayOf(
                0f to dp(10f),     // below
                dp(10f) to 0f,     // right
                -dp(10f) to 0f,    // left
                dp(10f) to dp(10f) // diag
            )
            for ((ox, oy) in offsets) {
                val b2 = RectF(bounds).apply { offset(ox, oy) }
                if (!collides(b2)) {
                    bounds = b2
                    break
                }
            }
        }
        canvas.drawText(text, (bounds.left + bounds.right) / 2f, bounds.bottom - dp(2f), textPaint)
        placed += bounds
    }

    private fun prnLabel(s: NmeaParser.Satellite): String {
        val prefix = when (s.constellation) {
            NmeaParser.Constellation.GPS -> "G"
            NmeaParser.Constellation.GLONASS -> "R"
            NmeaParser.Constellation.GALILEO -> "E"
            NmeaParser.Constellation.BEIDOU -> "B"
            NmeaParser.Constellation.QZSS -> "Q"
            NmeaParser.Constellation.SBAS -> "S"
            NmeaParser.Constellation.IRNSS -> "I"
            else -> "?"
        }
        return "$prefix${s.prn}"
    }

    private fun isUsedSat(s: NmeaParser.Satellite): Boolean {
        val prn = s.prn
        return prn in usedNorm ||
                (prn in 33..64   && (prn + 87) in usedNorm) ||
                (prn in 120..158 && (prn - 87) in usedNorm)
    }

    private fun uniqueKey(s: NmeaParser.Satellite): Int =
        s.constellation.ordinal * 1000 + s.prn

    private fun constellationStrokeColor(c: NmeaParser.Constellation): Int = when (c) {
        NmeaParser.Constellation.GPS     -> Color.parseColor("#90CAF9") // light blue
        NmeaParser.Constellation.GLONASS -> Color.parseColor("#CE93D8") // violet
        NmeaParser.Constellation.GALILEO -> Color.parseColor("#A5D6A7") // green
        NmeaParser.Constellation.BEIDOU  -> Color.parseColor("#FFE082") // amber
        NmeaParser.Constellation.QZSS    -> Color.parseColor("#B39DDB")
        NmeaParser.Constellation.SBAS    -> Color.parseColor("#B0BEC5")
        else -> Color.LTGRAY
    }

    // ==== Touch handling for tap-to-details ====
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isMaybeClick = true
                // we handle it to receive MOVE/UP
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMaybeClick && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    isMaybeClick = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isMaybeClick) {
                    val hit = hitDots.minByOrNull { hypot(it.x - event.x, it.y - event.y) }
                    if (hit != null && hypot(hit.x - event.x, hit.y - event.y) <= hit.hitR) {
                        // If we have SkyGeometry data, try to find the corresponding geometry
                        val correspondingGeometry = skyGeometry.find { geom ->
                            geom.svid == hit.sat.prn &&
                            translateConstellationToNmea(geom.constellation) == hit.sat.constellation
                        }

                        if (correspondingGeometry != null) {
                            // Create a NmeaParser.Satellite from SkyGeometry for backward compatibility
                            val satelliteFromGeometry = NmeaParser.Satellite(
                                prn = correspondingGeometry.svid,
                                constellation = translateConstellationToNmea(correspondingGeometry.constellation),
                                elevationDeg = correspondingGeometry.elDeg?.toInt(),
                                azimuthDeg = correspondingGeometry.azDeg?.toInt(),
                                snrDb = correspondingGeometry.snrDbHz?.toInt()
                            )
                            onSatelliteClick?.invoke(satelliteFromGeometry, correspondingGeometry.usedInFix)
                        } else {
                            // Fallback to original behavior
                            onSatelliteClick?.invoke(hit.sat, hit.isUsed)
                        }
                    }
                }
                isMaybeClick = false
            }
            MotionEvent.ACTION_CANCEL -> isMaybeClick = false
        }
        return super.onTouchEvent(event) || isMaybeClick
    }

    /**
     * Helper to translate gnss.model.Constellation to NmeaParser.Constellation
     */
    private fun translateConstellationToNmea(constellation: Constellation): NmeaParser.Constellation {
        return when (constellation) {
            Constellation.GPS -> NmeaParser.Constellation.GPS
            Constellation.GLONASS -> NmeaParser.Constellation.GLONASS
            Constellation.GALILEO -> NmeaParser.Constellation.GALILEO
            Constellation.BEIDOU -> NmeaParser.Constellation.BEIDOU
            Constellation.QZSS -> NmeaParser.Constellation.QZSS
            Constellation.SBAS -> NmeaParser.Constellation.SBAS
            Constellation.IRNSS -> NmeaParser.Constellation.IRNSS
            Constellation.UNKNOWN -> NmeaParser.Constellation.UNKNOWN
        }
    }

    // ==== Utils ====
    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
