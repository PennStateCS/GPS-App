package com.example.surveyingapp.data.local.db

import androidx.room.TypeConverter
import com.example.surveyingapp.domain.model.CorrectionSource
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Room type converters for non-primitive fields.
 *
 * Notes:
 * - Instant stored as epoch millis (Long)
 * - Duration stored as seconds (Double, fractional ok)
 * - Enums stored as name Strings (safe from typos via runCatching)
 */
object Converters {

    // ---- Instant <-> epoch millis ----
    @TypeConverter
    @JvmStatic
    fun fromEpochMillis(value: Long?): Instant? =
        value?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }

    @TypeConverter
    @JvmStatic
    fun toEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    // ---- Duration <-> seconds (Double) ----
    @TypeConverter
    @JvmStatic
    fun fromSeconds(value: Double?): Duration? =
        value?.let { runCatching { it.toDuration(DurationUnit.SECONDS) }.getOrNull() }

    @TypeConverter
    @JvmStatic
    fun toSeconds(value: Duration?): Double? =
        value?.toDouble(DurationUnit.SECONDS)

    // ---- RtkStatus <-> String ----
    @TypeConverter
    @JvmStatic
    fun fromRtkStatus(value: RtkStatus?): String? = value?.name

    /**
     * Lenient parse for the nullable rtkStatus column. Tolerates canonical enum names and the
     * legacy "RTK_FIXED"/"RTK_FLOAT"/"FIXED" aliases. Unknown/blank values map to null so old
     * rows never crash on read.
     */
    @TypeConverter
    @JvmStatic
    fun toRtkStatus(value: String?): RtkStatus? = when (value?.trim()?.uppercase(Locale.US)) {
        null, "" -> null
        "FIX", "FIXED", "RTK_FIXED" -> RtkStatus.FIX
        "FLOAT", "RTK_FLOAT"        -> RtkStatus.FLOAT
        "DGPS"                      -> RtkStatus.DGPS
        "SINGLE"                    -> RtkStatus.SINGLE
        "DEAD_RECKONING", "DR"      -> RtkStatus.DEAD_RECKONING
        "NONE"                      -> RtkStatus.NONE
        "INVALID"                   -> RtkStatus.INVALID
        else -> runCatching { RtkStatus.valueOf(value.trim().uppercase(Locale.US)) }.getOrNull()
    }

    // ---- Provider <-> String ----
    @TypeConverter
    @JvmStatic
    fun fromProvider(value: Provider?): String? = value?.name

    /**
     * Lenient parse for the (non-null) provider column. Tolerates canonical enum names and the
     * legacy hyphen/underscore/captureMethod-style aliases. A non-blank but unrecognized value
     * maps to [Provider.OTHER] rather than null so the non-null column never NPEs on read.
     */
    @TypeConverter
    @JvmStatic
    fun toProvider(value: String?): Provider? = when (value?.trim()?.lowercase(Locale.US)) {
        null, "" -> null
        "internal", "fused", "internal_gps"                          -> Provider.INTERNAL
        "rs2_external", "rs2-external", "external", "external_gnss"   -> Provider.RS2_EXTERNAL
        "rs2_bt", "rs2-bt"                                            -> Provider.RS2_BT
        "rs2_tcp", "rs2-tcp"                                          -> Provider.RS2_TCP
        "model", "model_embedded"                                    -> Provider.MODEL
        "other"                                                      -> Provider.OTHER
        else -> runCatching { Provider.valueOf(value.trim().uppercase(Locale.US)) }.getOrNull()
            ?: Provider.OTHER
    }

    // ---- CorrectionSource <-> String ----
    @TypeConverter
    @JvmStatic
    fun fromCorrectionSource(value: CorrectionSource?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun toCorrectionSource(value: String?): CorrectionSource? =
        value?.let { runCatching { CorrectionSource.valueOf(it) }.getOrNull() }
}
