package com.example.surveyingapp.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.ui.settings.SettingsCategory
import com.example.surveyingapp.ui.settings.SettingsCategoryAdapter

/**
 * Reusable fragment base for two-pane screens consisting of a list of categories on the left
 * and a dynamic content area on the right.
 */
abstract class BaseTwoPaneFragment : Fragment() {

    private lateinit var categoriesRecycler: RecyclerView
    private lateinit var internalContentContainer: LinearLayout
    private lateinit var headerView: TextView
    private lateinit var placeholderView: TextView
    private lateinit var adapter: SettingsCategoryAdapter
    protected var currentContentView: View? = null
    protected val contentContainer: LinearLayout get() = internalContentContainer

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.layout_twopane, container, false)
        categoriesRecycler = root.findViewById(R.id.categoriesRecycler)
        internalContentContainer = root.findViewById(R.id.twopane_content_container)
        headerView = root.findViewById(R.id.twopane_header)
        placeholderView = root.findViewById(R.id.twopane_placeholder)

        onRootCreated(root)

        val cats = provideCategories() // avoid multiple calls
        adapter = com.example.surveyingapp.ui.settings.SettingsCategoryAdapter(cats) { cat -> showCategory(cat) }
        categoriesRecycler.layoutManager = LinearLayoutManager(requireContext())
        categoriesRecycler.adapter = adapter

        val initialIdx = initialCategoryIndex().coerceIn(0, (cats.size - 1).coerceAtLeast(0))
        if (cats.isNotEmpty()) {
            val initialCat = cats[initialIdx]
            showCategory(initialCat)
            // Sync adapter selection if initial index not default first (or even if it is for clarity)
            adapter.setSelectedCategoryId(initialCat.id)
        }
        return root
    }

    /** Override to perform initialization before first category content is selected. */
    protected open fun onRootCreated(root: View) {}

    /** Categories to display. */
    protected abstract fun provideCategories(): List<SettingsCategory>

    /** Build (inflate/create) the view for a given category. */
    protected abstract fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View?

    /** Optionally choose which category index to select first. */
    protected open fun initialCategoryIndex(): Int = 0

    private fun showCategory(category: SettingsCategory) {
        val dynamicStartIndex = 2
        while (internalContentContainer.childCount > dynamicStartIndex) {
            internalContentContainer.removeViewAt(dynamicStartIndex)
        }
        val view = buildCategoryContent(category, layoutInflater) ?: return
        currentContentView = view
        headerView.apply {
            text = category.title
            visibility = View.VISIBLE
        }
        placeholderView.visibility = View.GONE
        internalContentContainer.addView(view)
    }
}
