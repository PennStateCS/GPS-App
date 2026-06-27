package app.surrealar.ui.viewcoordinates

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import app.surrealar.gnss.model.Fix
import java.time.format.DateTimeFormatter
import java.util.Locale

class CoordinatesAdapter : RecyclerView.Adapter<CoordinatesAdapter.CoordinateViewHolder>() {

    private var fixes: List<Fix> = emptyList()

    fun updateFixes(newFixes: List<Fix>) {
        fixes = newFixes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoordinateViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_coordinate, parent, false)
        return CoordinateViewHolder(view)
    }

    override fun onBindViewHolder(holder: CoordinateViewHolder, position: Int) {
        holder.bind(fixes[position])
    }

    override fun getItemCount(): Int = fixes.size

    class CoordinateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)
        private val coordinatesText: TextView = itemView.findViewById(R.id.coordinates_text)
        private val accuracyText: TextView = itemView.findViewById(R.id.accuracy_text)
        private val providerText: TextView = itemView.findViewById(R.id.provider_text)
        private val rtkStatusText: TextView = itemView.findViewById(R.id.rtk_status_text)

        fun bind(fix: Fix) {
            val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            timestampText.text = fix.timeUtc.atZone(java.time.ZoneId.systemDefault()).format(formatter)

            coordinatesText.text = String.format(
                Locale.US,
                "%.8f, %.8f",
                fix.latDeg,
                fix.lonDeg
            )

            val accuracyInfo = buildString {
                fix.hAccM?.let { append("H: %.2fm ".format(Locale.US, it)) }
                fix.vAccM?.let { append("V: %.2fm".format(Locale.US, it)) }
                if (isEmpty()) append("Accuracy: N/A")
            }
            accuracyText.text = accuracyInfo

            providerText.text = "Provider: ${fix.provider.name}"
            rtkStatusText.text = "RTK: ${fix.rtkStatus.name}"
        }
    }
}
