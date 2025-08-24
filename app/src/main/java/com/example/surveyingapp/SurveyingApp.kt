package com.example.surveyingapp

import android.app.Application
import android.util.Log

class SurveyingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GlobalCrash", "Uncaught exception on thread ${thread.name}", throwable)
        }
        Log.d("SurveyingApp","Application started; global crash handler installed")
    }
}

