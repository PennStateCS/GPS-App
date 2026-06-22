package com.example.surveyingapp.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import com.example.surveyingapp.ui.settings.SettingsCategory
import com.example.surveyingapp.ui.settings.SettingsCategoryAdapter

/**
 * Reusable fragment base for screens with a category selector on the left (wide) or top (narrow)
 * and a dynamic content area.
 *
 * Wide mode (sw>=600dp, w>=700dp): RecyclerView rail on the left.
 * Narrow mode (everything else): exposed dropdown at the top, content below.
 *
 * The mode is determined by whichever view is present in the inflated layout — no runtime
 * width checks needed.
 */
abstract class BaseTwoPaneFragment : Fragment() {

    companion object {
        private const val KEY_SELECTED_CAT_ID = "twopane_selected_cat_id"
    }

    // Wide mode (non-null when categoriesRecycler exists in the inflated layout)
    private var categoriesRecycler: RecyclerView? = null
    private var recyclerAdapter: SettingsCategoryAdapter? = null

    // Narrow mode (non-null when category_dropdown exists in the inflated layout)
    private var categoryDropdown: AutoCompleteTextView? = null
    private var narrowCategories: List<SettingsCategory> = emptyList()

    // Shared
    private lateinit var internalContentContainer: LinearLayout
    private lateinit var headerView: TextView
    private lateinit var placeholderView: TextView
    protected var currentContentView: View? = null
    protected val contentContainer: LinearLayout get() = internalContentContainer

    // Selection state for narrow mode (wide mode uses adapter's own tracking)
    private var narrowSelectedId: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.layout_twopane, container, false)

        categoriesRecycler = root.findViewById(R.id.categoriesRecycler)
        categoryDropdown = root.findViewById(R.id.category_dropdown)
        internalContentContainer = root.findViewById(R.id.twopane_content_container)
        headerView = root.findViewById(R.id.twopane_header)
        placeholderView = root.findViewById(R.id.twopane_placeholder)

        onRootCreated(root)

        val cats = provideCategories()
        val savedCatId = savedInstanceState?.getInt(KEY_SELECTED_CAT_ID, -1)?.takeIf { it >= 0 }
        val initialCat = savedCatId?.let { id -> cats.firstOrNull { it.id == id } }
            ?: cats.getOrElse(initialCategoryIndex().coerceIn(0, (cats.size - 1).coerceAtLeast(0))) { cats.firstOrNull() }

        if (categoriesRecycler != null) {
            setupWideMode(cats, initialCat)
        } else {
            setupNarrowMode(cats, initialCat)
        }

        return root
    }

    private fun setupWideMode(cats: List<SettingsCategory>, initialCat: SettingsCategory?) {
        val recycler = categoriesRecycler ?: return
        val adapter = SettingsCategoryAdapter(cats) { cat -> showCategory(cat) }
        recyclerAdapter = adapter
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        if (initialCat != null) {
            showCategory(initialCat)
            adapter.setSelectedCategoryId(initialCat.id)
        }
    }

    private fun setupNarrowMode(cats: List<SettingsCategory>, initialCat: SettingsCategory?) {
        val dropdown = categoryDropdown ?: return
        narrowCategories = cats
        val names = cats.map { it.title }
        dropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        )
        dropdown.setOnItemClickListener { _, _, position, _ ->
            val cat = narrowCategories.getOrNull(position) ?: return@setOnItemClickListener
            narrowSelectedId = cat.id
            showCategory(cat)
        }
        if (initialCat != null) {
            dropdown.setText(initialCat.title, false)
            narrowSelectedId = initialCat.id
            showCategory(initialCat)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val id = recyclerAdapter?.getSelectedCategoryId() ?: narrowSelectedId
        id?.let { outState.putInt(KEY_SELECTED_CAT_ID, it) }
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
        headerView.visibility = View.GONE
        placeholderView.visibility = View.GONE
        internalContentContainer.addView(view)
    }

    /** Allow subclasses to refresh the category list dynamically (e.g. toggling dev tools). */
    protected fun updateCategoriesDynamic(newCategories: List<SettingsCategory>) {
        if (categoriesRecycler != null) {
            updateWideDynamic(newCategories)
        } else {
            updateNarrowDynamic(newCategories)
        }
    }

    private fun updateWideDynamic(newCategories: List<SettingsCategory>) {
        val adapter = recyclerAdapter ?: return
        val prevSelectedId = adapter.getSelectedCategoryId()
        adapter.updateCategories(newCategories)
        val stillSelectedId = adapter.getSelectedCategoryId()
        if (stillSelectedId == null && newCategories.isNotEmpty()) {
            val first = newCategories.first()
            adapter.setSelectedCategoryId(first.id)
            showCategory(first)
        } else if (prevSelectedId != null && newCategories.none { it.id == prevSelectedId }) {
            newCategories.firstOrNull()?.let { showCategory(it); adapter.setSelectedCategoryId(it.id) }
        }
    }

    private fun updateNarrowDynamic(newCategories: List<SettingsCategory>) {
        val dropdown = categoryDropdown ?: return
        narrowCategories = newCategories
        val names = newCategories.map { it.title }
        dropdown.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        )
        val prevId = narrowSelectedId
        val stillExists = prevId?.let { id -> newCategories.firstOrNull { it.id == id } }
        if (stillExists != null) {
            // Keep current selection — dropdown text is already correct
            dropdown.setText(stillExists.title, false)
        } else {
            newCategories.firstOrNull()?.let { first ->
                dropdown.setText(first.title, false)
                narrowSelectedId = first.id
                showCategory(first)
            }
        }
    }
}
