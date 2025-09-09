package com.example.surveyingapp.gnss.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Only the fields that influence source routing are included here.
 * Backed by DataStore in your existing settings repo.
 */
class SourceSettings(
    val activeProvider: StateFlow<ProviderChoice>,
    val rs2Host: StateFlow<String?>            // e.g., "192.168.1.50"
) {
    enum class ProviderChoice { INTERNAL, RS2_EXTERNAL }
}
