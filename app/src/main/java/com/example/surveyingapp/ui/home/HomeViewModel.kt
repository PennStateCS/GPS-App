/**
 * ViewModel for the Home screen.
 *
 * This demonstrates the basic ViewModel pattern in Android's MVVM architecture:
 * - ViewModels survive configuration changes (screen rotation, etc.)
 * - They separate business logic from UI logic
 * - They use LiveData to automatically update the UI when data changes
 *
 * Key concepts for students:
 * - MutableLiveData: Can be changed internally by the ViewModel
 * - LiveData: Read-only view exposed to the UI (Fragment/Activity)
 * - Observer pattern: UI automatically updates when LiveData changes
 */
package com.example.surveyingapp.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {

    // Private MutableLiveData - only this ViewModel can change the value
    private val _text = MutableLiveData<String>().apply {
        value = "Welcome to SurveyingApp!\n\nCapture precise coordinates, manage survey points, and visualize your data with our comprehensive surveying tools.\n\nGet started by capturing coordinates or viewing your existing points."
    }

    // Public LiveData - UI can observe but not modify
    // This encapsulation protects data integrity
    val text: LiveData<String> = _text
}