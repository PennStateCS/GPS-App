package app.surrealar.ui.viewpoints

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import app.surrealar.R

/**
 * Host activity for the coordinate list/detail two-pane flow.
 */
class CoordinatesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coordinates)
        // Edge-to-edge: keep the list/detail panes clear of the status and navigation bars.
        app.surrealar.ui.common.SystemBarInsets.apply(findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.list_container, CoordinatesFragment())
                .commit()
        }
    }

    fun showDetail(id: String) {
        Log.d("CoordinatesActivity", "showDetail called with id=$id")
        val fm = supportFragmentManager
        val existing = fm.findFragmentById(R.id.detail_container) as? CoordinateDetailFragment
        if (existing != null) {
            Log.d("CoordinatesActivity", "Updating existing detail fragment")
            existing.updateId(id)
        } else {
            Log.d("CoordinatesActivity", "Creating new detail fragment")
            // Immediate commit to reduce perceived delay
            fm.beginTransaction()
                .replace(R.id.detail_container, CoordinateDetailFragment.newInstance(id))
                .commitNowAllowingStateLoss()
        }
    }
}
