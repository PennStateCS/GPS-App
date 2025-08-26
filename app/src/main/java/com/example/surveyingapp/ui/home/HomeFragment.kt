/**
 * Fragment for the Home screen - the main entry point of the surveying app.
 *
 * This demonstrates key Android Fragment concepts:
 * - Fragment lifecycle: onCreateView, onDestroyView
 * - View binding: Safe way to access views without findViewById
 * - MVVM pattern: Fragment (View) observes ViewModel for data changes
 * - Observer pattern: UI automatically updates when LiveData changes

 */
package com.example.surveyingapp.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    // View binding - safer than findViewById, automatically set to null when view is destroyed
    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and onDestroyView
    // The !! operator is safe here because we only access it when _binding is not null
    private val binding get() = _binding!!

    /**
     * Called when the fragment needs to create its view hierarchy.
     * This is where we inflate the layout and set up the UI components.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Create the ViewModel - ViewModelProvider ensures it survives configuration changes
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

        // Inflate the layout using view binding
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Get reference to the text view using view binding
        val textView: TextView = binding.textHome

        // Observe the LiveData from ViewModel
        // When the text changes, this observer automatically updates the UI
        homeViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    /**
     * Called when the view hierarchy is being destroyed.
     * IMPORTANT: Always set binding to null to prevent memory leaks!
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null  // Prevents memory leaks by releasing view references
    }
}