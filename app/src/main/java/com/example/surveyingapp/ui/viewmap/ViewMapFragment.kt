package com.example.surveyingapp.ui.viewmap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.databinding.FragmentViewMapBinding

class ViewMapFragment : Fragment() {

    private var _binding: FragmentViewMapBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewMapViewModel =
            ViewModelProvider(this).get(ViewMapViewModel::class.java)

        _binding = FragmentViewMapBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textViewMap
        viewMapViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

