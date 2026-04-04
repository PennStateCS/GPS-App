package com.example.surveyingapp.domain.model

/**
 * Data class representing a self-test result from an Emlid device
 */
data class SelfTest(
    val name: String,
    val status: TestStatus,
    val description: String? = null
)
