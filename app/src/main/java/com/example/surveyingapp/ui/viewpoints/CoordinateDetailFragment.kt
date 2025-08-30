package com.example.surveyingapp.ui.viewpoints

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.TextView

class CoordinateDetailFragment : Fragment() {

    companion object {
        private const val ARG_ID = "arg_id"
        fun newInstance(id: String): CoordinateDetailFragment = CoordinateDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    private lateinit var viewModel: CoordinatesViewModel

    private var textName: TextView? = null
    private var textCoords: TextView? = null
    private var textTime: TextView? = null
    private var textProvider: TextView? = null
    private var textRtk: TextView? = null
    private var textHdop: TextView? = null
    private var textEmpty: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_coordinate_detail, container, false)
        textName = v.findViewById(R.id.text_name)
        textCoords = v.findViewById(R.id.text_coords)
        textTime = v.findViewById(R.id.text_time)
        textProvider = v.findViewById(R.id.text_provider)
        textRtk = v.findViewById(R.id.text_rtk)
        textHdop = v.findViewById(R.id.text_hdop)
        textEmpty = v.findViewById(R.id.text_empty)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
        ).get(CoordinatesViewModel::class.java)

        val id = arguments?.getString(ARG_ID)
        if (id.isNullOrBlank()) {
            showEmpty()
            return
        }
        lifecycleScope.launch {
            val coord = viewModel.getById(id)
            if (coord == null) {
                showEmpty()
            } else {
                bindCoordinate(coord)
            }
        }
    }

    private fun showEmpty() {
        textEmpty?.visibility = View.VISIBLE
        listOf(textName, textCoords, textTime, textProvider, textRtk, textHdop).forEach { it?.visibility = View.GONE }
    }

    private fun bindCoordinate(c: com.example.surveyingapp.data.Coordinate) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        textName?.text = c.name
        textCoords?.text = String.format(Locale.US, "Lat: %.6f\nLon: %.6f\nAlt: %.2f m", c.latitude, c.longitude, c.altitude)
        textTime?.text = "Time: ${fmt.format(Date(c.timestamp))}"
        textProvider?.text = "Provider: ${c.provider}"
        textRtk?.text = "RTK: ${c.rtkStatus ?: "--"}"
        textHdop?.text = "HDOP: ${c.hdop?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}"
        textEmpty?.visibility = View.GONE
        listOf(textName, textCoords, textTime, textProvider, textRtk, textHdop).forEach { it?.visibility = View.VISIBLE }
    }
}
