package com.example.surveyingapp.ui.viewpoints

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.surveyingapp.R
import com.google.android.material.appbar.MaterialToolbar

class CoordinateDetailActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "extra_coord_id" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coordinate_detail)

        // Setup toolbar with Up navigation
        val toolbar: MaterialToolbar? = findViewById(R.id.toolbar)
        toolbar?.let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            it.setNavigationOnClickListener { finish() }
        }

        if (savedInstanceState == null) {
            val id = intent.getStringExtra(EXTRA_ID) ?: ""
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_root, CoordinateDetailFragment.newInstance(id))
                .commit()
        }
    }
}
