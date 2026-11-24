package app.surrealar.ui.settings.categories

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/**
 * Dropdown adapter whose filtering is delegated to [search] (e.g. geokit's `CrsRegistry.search`) instead of
 * ArrayAdapter's word-prefix filter, so ranking and matching rules live in one testable place. [search] runs
 * on the filter's worker thread and must be pure.
 */
internal class SearchFilterAdapter(
    context: Context,
    layout: Int,
    initial: List<String>,
    private val search: (String) -> List<String>,
) : ArrayAdapter<String>(context, layout, ArrayList(initial)) {

    private val searchFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val hits = runCatching { search(constraint?.toString().orEmpty()) }.getOrDefault(emptyList())
            return FilterResults().apply { values = hits; count = hits.size }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            @Suppress("UNCHECKED_CAST")
            val hits = results?.values as? List<String> ?: emptyList()
            setNotifyOnChange(false)
            clear()
            addAll(hits)
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence = resultValue as? String ?: ""
    }

    override fun getFilter(): Filter = searchFilter
}
