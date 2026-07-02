package com.example.surveyingapp.ui.models

import java.io.Serializable

// Stores the data for model diagnostics
data class ModelDiagnostics(
    val fileSizeBytes: Long,
    val widthMeters: Float,
    val depthMeters: Float,
    val heightMeters: Float,
    val originOffsetMeters: Float,
    val originDescription: String,
    val verticalPlacement: String,
    val arReadiness: String
) : Serializable
