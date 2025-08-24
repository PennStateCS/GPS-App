package com.example.surveyingapp.ui.openinar

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class OpenInARViewModel : ViewModel() {
    private val _text = MutableLiveData<String>().apply {
        value = "Open in AR"
    }
    val text: LiveData<String> = _text
}

