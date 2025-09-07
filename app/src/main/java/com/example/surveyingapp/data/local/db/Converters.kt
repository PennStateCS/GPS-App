package com.example.surveyingapp.data.local.db

import androidx.room.TypeConverter
import com.example.surveyingapp.domain.model.CorrectionSource
import com.example.surveyingapp.domain.model.Provider
import com.example.surveyingapp.domain.model.RtkStatus
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Room type converters for non-primitive fields.
 * These let you store enums, Instants, and Durations in SQLite.
 */
object Converters {

    // ---- Instant <-> epoch millis ----
    @TypeConverter
    @JvmStatic
    fun fromEpochMillis(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    @JvmStatic
    fun toEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    // ---- Duration <-> seconds (Double) ----
    @TypeConverter
    @JvmStatic
    fun fromSeconds(value: Double?): Duration? = value?.toDuration(DurationUnit.SECONDS)

    @TypeConverter
    @JvmStatic
    fun toSeconds(value: Duration?): Double? = value?.toDouble(DurationUnit.SECONDS)

    // ---- RtkStatus <-> String ----
    @TypeConverter
    @JvmStatic
    fun fromRtkStatus(value: RtkStatus?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun toRtkStatus(value: String?): RtkStatus? =
        value?.let { runCatching { RtkStatus.valueOf(it) }.getOrNull() }

    // ---- Provider <-> String ----
    @TypeConverter
    @JvmStatic
    fun fromProvider(value: Provider?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun toProvider(value: String?): Provider? =
        value?.let { runCatching { Provider.valueOf(it) }.getOrNull() }

    // ---- CorrectionSource <-> String ----
    @TypeConverter
    @JvmStatic
    fun fromCorrectionSource(value: CorrectionSource?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun toCorrectionSource(value: String?): CorrectionSource? =
        value?.let { runCatching { CorrectionSource.valueOf(it) }.getOrNull() }
}
