package com.example.surveyingapp.ui.openinar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.databinding.FragmentOpenInArBinding

class OpenInARFragment : Fragment() {

    private var _binding: FragmentOpenInArBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val openInARViewModel =
            ViewModelProvider(this).get(OpenInARViewModel::class.java)

        _binding = FragmentOpenInArBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val textView: TextView = binding.textOpenInAr
        openInARViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

