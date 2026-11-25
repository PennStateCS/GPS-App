package io.github.pennstatecs.geokit.receiver.emlid.nmea

/**
 * ETC `State` field (Emlid "NMEA Message: ETC (Tilt Compensation Data)", message rev. 1, document rev. 3).
 *
 * @property code the integer on the wire; null for [OFF] (empty field) and [UNRECOGNIZED].
 */
public enum class EtcStateCode(public val code: Int?) {
    /** Empty field: tilt compensation is off. */
    OFF(null),
    /** `0`: fatal error. Irrecoverable until the receiver is rebooted. */
    FATAL_ERROR(0),
    /** `10`: setup (tilt engine internal initialisation after boot; the user only waits). */
    SETUP(10),
    /** `20`: alignment (the user must move the receiver to calibrate the IMU; can recur during work). */
    ALIGNMENT(20),
    /** `30`: compensating (normal working state; all data valid). */
    COMPENSATING(30),
    /** An integer the documentation does not define (see [EtcFields.stateRaw]). */
    UNRECOGNIZED(null),
}

/**
 * ETC `Notification` field (same document as [EtcStateCode]).
 *
 * @property code the integer on the wire; null for [TILT_OFF] (empty field) and [UNRECOGNIZED].
 */
public enum class EtcNotification(public val code: Int?) {
    /** Empty field: tilt is off. */
    TILT_OFF(null),
    /** `0`: none. */
    NONE(0),
    /** `10`: fast motion. */
    FAST_MOTION(10),
    /** `20`: bad GNSS. */
    BAD_GNSS(20),
    /** `30`: filter fault. */
    FILTER_FAULT(30),
    /** An integer the documentation does not define (see [EtcFields.notificationRaw]). */
    UNRECOGNIZED(null),
}

/**
 * The ETC fields decoded per Emlid's documented layout:
 *
 * ```
 * $GNETC,<UTC time-of-fix>,<State>,<Notification>,<Heading>,<Tilt Direction>,<Tilt Value>,
 *        <n-axis-acc>,<e-axis-acc>,<u-axis-acc>*<Checksum>
 * ```
 *
 * Sources (retrieved 2026-11-24): https://docs.emlid.com/reachrs4/specifications/nmea-format/ and the
 * linked https://files.emlid.com/docs/ETC_rev.3_RS4.pdf (message rev. 1, document rev. 3).
 *
 * Decoding never throws. A sentence that does not fit the layout (wrong field count, a non-numeric
 * state, a value out of its documented range, compensating without its values, values outside the
 * compensating state, tilt off with a non-empty notification…) is kept with [malformedReason] set and
 * the offending values null; consumers must treat it as unknown. [EtcFields.state] is then whatever the
 * state field said (or [EtcStateCode.UNRECOGNIZED]) — use [isWellFormed] before trusting anything.
 *
 * Nothing here is applied to a position. The accuracy coefficients are carried as published: per the
 * PDF they are multiplied by the antenna height to give a per-axis tilt accuracy in millimetres.
 *
 * @property timeOfFixSecondsOfDay UTC time-of-fix as seconds of day [0, 86400), or null when empty.
 * @property state decoded state.
 * @property stateRaw the state field as received (trimmed); empty when off.
 * @property notification decoded notification, or null when the field could not be read.
 * @property notificationRaw the notification field as received (trimmed).
 * @property headingDeg heading, degrees [0, 360]; only in the compensating state.
 * @property tiltDirectionDeg tilt direction, degrees [0, 360]; only in the compensating state.
 * @property tiltDeg tilt value, degrees [0, 180]; only in the compensating state.
 * @property accuracyCoefficientN n-axis accuracy coefficient; only in the compensating state.
 * @property accuracyCoefficientE e-axis accuracy coefficient; only in the compensating state.
 * @property accuracyCoefficientU u-axis accuracy coefficient; only in the compensating state.
 * @property malformedReason why the sentence does not fit the documented layout; null when it does.
 */
public data class EtcFields(
    val timeOfFixSecondsOfDay: Double?,
    val state: EtcStateCode,
    val stateRaw: String,
    val notification: EtcNotification?,
    val notificationRaw: String,
    val headingDeg: Double?,
    val tiltDirectionDeg: Double?,
    val tiltDeg: Double?,
    val accuracyCoefficientN: Double?,
    val accuracyCoefficientE: Double?,
    val accuracyCoefficientU: Double?,
    val malformedReason: String?,
) {
    /** True when the sentence matches the documented layout and its rules. */
    public val isWellFormed: Boolean get() = malformedReason == null

    public companion object {
        /** Data fields after the time field: state, notification, heading, direction, tilt, n, e, u. */
        public const val DATA_FIELD_COUNT: Int = 8

        /**
         * Decodes the ETC time field and the data fields that follow it (as carried by [ETC]).
         * Never throws.
         */
        public fun decode(timeRaw: String?, dataFields: List<String>): EtcFields {
            val problems = mutableListOf<String>()

            val time = timeRaw?.trim().orEmpty()
            val timeOfDay = if (time.isEmpty()) null else parseTimeOfDay(time)
            if (time.isNotEmpty() && timeOfDay == null) problems += "time '$time' is not hhmmss.ss"

            if (dataFields.size != DATA_FIELD_COUNT) {
                problems += "expected $DATA_FIELD_COUNT data fields, got ${dataFields.size}"
            }
            val f = List(DATA_FIELD_COUNT) { dataFields.getOrNull(it)?.trim().orEmpty() }

            val stateRaw = f[0]
            val stateInt = stateRaw.toIntOrNull()
            val state = when {
                stateRaw.isEmpty() -> EtcStateCode.OFF
                stateInt == null -> { problems += "state '$stateRaw' is not an integer"; EtcStateCode.UNRECOGNIZED }
                else -> EtcStateCode.entries.firstOrNull { it.code == stateInt }
                    ?: run { problems += "state $stateInt is not documented"; EtcStateCode.UNRECOGNIZED }
            }

            val notificationRaw = f[1]
            val notificationInt = notificationRaw.toIntOrNull()
            val notification = when {
                notificationRaw.isEmpty() -> EtcNotification.TILT_OFF
                notificationInt == null -> { problems += "notification '$notificationRaw' is not an integer"; null }
                else -> EtcNotification.entries.firstOrNull { it.code == notificationInt }
                    ?: run { problems += "notification $notificationInt is not documented"; EtcNotification.UNRECOGNIZED }
            }
            if ((state == EtcStateCode.OFF) != (notification == EtcNotification.TILT_OFF)) {
                problems += "state '$stateRaw' and notification '$notificationRaw' disagree about tilt being off"
            }

            val heading = ranged(f[2], 0.0, 360.0, "heading", problems)
            val direction = ranged(f[3], 0.0, 360.0, "tilt direction", problems)
            val tilt = ranged(f[4], 0.0, 180.0, "tilt value", problems)
            val coefN = ranged(f[5], 0.0, Double.MAX_VALUE, "n-axis coefficient", problems)
            val coefE = ranged(f[6], 0.0, Double.MAX_VALUE, "e-axis coefficient", problems)
            val coefU = ranged(f[7], 0.0, Double.MAX_VALUE, "u-axis coefficient", problems)
            val values = listOf(heading, direction, tilt, coefN, coefE, coefU)
            val valueFieldsBlank = f.subList(2, DATA_FIELD_COUNT).all { it.isEmpty() }

            if (state == EtcStateCode.COMPENSATING) {
                if (values.any { it == null }) problems += "compensating without all six values"
            } else if (!valueFieldsBlank) {
                problems += "values present outside the compensating state"
            }

            val ok = problems.isEmpty()
            return EtcFields(
                timeOfFixSecondsOfDay = timeOfDay,
                state = state,
                stateRaw = stateRaw,
                notification = notification,
                notificationRaw = notificationRaw,
                headingDeg = heading.takeIf { ok },
                tiltDirectionDeg = direction.takeIf { ok },
                tiltDeg = tilt.takeIf { ok },
                accuracyCoefficientN = coefN.takeIf { ok },
                accuracyCoefficientE = coefE.takeIf { ok },
                accuracyCoefficientU = coefU.takeIf { ok },
                malformedReason = if (ok) null else problems.joinToString("; "),
            )
        }

        /**
         * Strict NMEA `hhmmss[.s…]` → seconds of day, or null when it is not a valid time of day.
         * Shared with [io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltTracker] for GGA times.
         */
        public fun parseTimeOfDay(raw: String?): Double? {
            val s = raw?.trim() ?: return null
            if (s.length < 6 || !s.substring(0, 6).all { it in '0'..'9' }) return null
            val frac = s.substring(6)
            if (frac.isNotEmpty() && (frac[0] != '.' || frac.length == 1 || !frac.substring(1).all { it in '0'..'9' })) {
                return null
            }
            val hh = s.substring(0, 2).toInt()
            val mm = s.substring(2, 4).toInt()
            val ss = s.substring(4).toDoubleOrNull() ?: return null
            if (hh > 23 || mm > 59 || ss >= 61.0) return null  // 60.x tolerated for a leap second
            return hh * 3600.0 + mm * 60.0 + ss
        }

        private fun ranged(raw: String, min: Double, max: Double, name: String, problems: MutableList<String>): Double? {
            if (raw.isEmpty()) return null
            val v = raw.toDoubleOrNull()
            if (v == null || !v.isFinite()) { problems += "$name '$raw' is not a number"; return null }
            if (v < min || v > max) { problems += "$name $v is outside [$min, $max]"; return null }
            return v
        }
    }
}
