package com.example.surveyingapp

import android.app.Application
import android.content.Context
import android.util.Log
import org.osmdroid.config.Configuration

class SurveyingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GlobalCrash", "Uncaught exception on thread ${thread.name}", throwable)
        }
        try {
            val ctx = applicationContext
            val prefs = ctx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
            Configuration.getInstance().load(ctx, prefs)
            val currentUA = Configuration.getInstance().userAgentValue
            if (currentUA == null || currentUA == "osmdroid") {
                val pkg = ctx.packageName
                val versionName = try {
                    val pm = ctx.packageManager
                    val pInfo = pm.getPackageInfo(pkg, 0)
                    pInfo.versionName ?: "0"
                } catch (e: Exception) { "0" }
                Configuration.getInstance().userAgentValue = "$pkg/$versionName (SurveyingApp)"
            }
            Log.d("SurveyingApp","osmdroid UA set to ${Configuration.getInstance().userAgentValue}")
        } catch (e: Exception) {
            Log.w("SurveyingApp","Failed to set osmdroid user agent: ${e.message}")
        }
        Log.d("SurveyingApp","Application started; global crash handler & osmdroid config done")
    }
}
