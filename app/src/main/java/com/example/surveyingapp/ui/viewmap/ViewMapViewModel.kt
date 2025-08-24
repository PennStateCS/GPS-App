package com.example.surveyingapp.ui.viewmap

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ViewMapViewModel : ViewModel() {
    private val _text = MutableLiveData<String>().apply {
        value = "View Map"
    }
    val text: LiveData<String> = _text
}

