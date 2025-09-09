package com.example.surveyingapp.gnss.repo

import com.example.surveyingapp.gnss.capture.CaptureResult
import com.example.surveyingapp.domain.model.Provider

/**
 * Persists finished averaged captures as CoordinateEntity rows.
 * Kept in the gnss.repo package so capture/UI stay decoupled from Room.
 */
interface CoordinateRepository {
    suspend fun saveCapture(
        name: String,
        note: String?,
        colorArgb: Int,
        iconId: String,
        result: CaptureResult,
        provider: Provider,
        sourceDevice: String? = null,
        appVersion: String? = null
    )
}
