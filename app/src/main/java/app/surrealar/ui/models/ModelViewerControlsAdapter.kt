package app.surrealar.ui.models

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R

// Buttons shown in the controls viewer in order
// To add a feature, add an entry here and connect it in ModelViewerActivity
enum class ModelViewerControl(@StringRes val defaultLabel: Int, @DrawableRes val icon: Int) {
    RESET_ROTATION(R.string.reset, R.drawable.ic_star),
    AUTO_ROTATE(R.string.auto_rotate, R.drawable.ic_star),
    LIGHTING(R.string.lighting_on, R.drawable.ic_star),
    DIAGNOSTICS(R.string.model_diag_string, R.drawable.ic_star),
    AXIS(R.string.axis_off, R.drawable.ic_star),
    OFFSET(R.string.offset_off, R.drawable.ic_star),
    BOUNDING_BOX(R.string.bbox_off, R.drawable.ic_star),
    GRID(R.string.grid_off, R.drawable.ic_star),
    OVERLAY_ON_TOP(R.string.overlay_top_on, R.drawable.ic_star),
}

// RecyclerView adapter for the Model Viewer's controls tray.
class ModelViewerControlsAdapter(context: Context, private val onControlClick: (ModelViewerControl) -> Unit)
    : RecyclerView.Adapter<ModelViewerControlsAdapter.ControlViewHolder>() {

    private val controls = ModelViewerControl.entries

    private val labels: MutableList<CharSequence> = controls.mapTo(mutableListOf()) { context.getString(it.defaultLabel) }

    fun setLabel(control: ModelViewerControl, label: CharSequence) {
        labels[control.ordinal] = label
        notifyItemChanged(control.ordinal)
    }

    override fun getItemCount(): Int = controls.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ControlViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_model_viewer_control, parent, false)
        return ControlViewHolder(view)
    }

    override fun onBindViewHolder(holder: ControlViewHolder, position: Int) {
        holder.bind(controls[position], labels[position])
    }

    inner class ControlViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageIcon: ImageView = itemView.findViewById(R.id.image_control_icon)
        private val textLabel: TextView = itemView.findViewById(R.id.text_control_label)

        fun bind(control: ModelViewerControl, label: CharSequence) {
            imageIcon.setImageResource(control.icon)
            textLabel.text = label

            // Marquee only runs while selected, labels that already fit should stay still
            textLabel.isSelected = true
            itemView.setOnClickListener { onControlClick(control) }
        }
    }
}
