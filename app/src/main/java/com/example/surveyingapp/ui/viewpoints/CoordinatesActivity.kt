package com.example.surveyingapp.ui.viewpoints

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.surveyingapp.R

class CoordinatesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coordinates)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.list_container, CoordinatesFragment())
                .commit()
        }
    }

    fun showDetail(id: String) {
        val detailContainer = findViewById<android.view.View?>(R.id.detail_container)
        if (detailContainer != null) {
            // Two-pane: show / replace fragment in detail container
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_container, CoordinateDetailFragment.newInstance(id))
                .commit()
        } else {
            // Single-pane: launch detail activity
            startActivity(Intent(this, CoordinateDetailActivity::class.java).apply {
                putExtra(CoordinateDetailActivity.EXTRA_ID, id)
            })
        }
    }
}
