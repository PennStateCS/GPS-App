package com.example.surveyingapp.gnss.repo

import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.gnss.capture.CaptureResult
import com.example.surveyingapp.domain.model.Provider
import com.example.surveyingapp.gnss.repo.CoordinateRepository
/**
 * Room-backed implementation for persisting coordinates.
 */
class CoordinateRepositoryImpl(
    private val dao: CoordinateDao
) : CoordinateRepository {

    override suspend fun saveCapture(
        name: String,
        note: String?,
        colorArgb: Int,
        iconId: String,
        result: CaptureResult,
        provider: Provider,
        sourceDevice: String?,
        appVersion: String?
    ) {
        val entity = Mappers.toEntity(
            name = name,
            note = note,
            colorArgb = colorArgb,
            iconId = iconId,
            result = result,           // <- consistent name
            provider = provider,
            sourceDevice = sourceDevice,
            appVersion = appVersion
        )
        dao.insert(entity)
    }
}
