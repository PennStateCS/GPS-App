package app.surrealar.ui.theme

import android.content.Context
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import app.surrealar.R
import com.google.android.material.color.MaterialColors

/**
 * The five semantic status roles of the design system (docs/design-system.md, "Semantic status roles") as a
 * plain enum, so pure mappers can say *which* role a badge or line uses without touching Android resources.
 * Views resolve the actual colour from the current theme (light / night) with [foreground], [container] and
 * [onContainer].
 *
 * - foreground: text / icon / dot directly on a surface (≥ 4.5:1 on every surface).
 * - container + onContainer: a filled badge or chip (text on the fill ≥ 4.5:1). Never put foreground text on a
 *   container or white text on a container.
 */
enum class StatusTone(
    @param:AttrRes @get:AttrRes val foregroundAttr: Int,
    @param:AttrRes @get:AttrRes val containerAttr: Int,
    @param:AttrRes @get:AttrRes val onContainerAttr: Int,
) {
    SUCCESS(R.attr.colorStatusSuccess, R.attr.colorStatusSuccessContainer, R.attr.colorOnStatusSuccessContainer),
    WARNING(R.attr.colorStatusWarning, R.attr.colorStatusWarningContainer, R.attr.colorOnStatusWarningContainer),
    ERROR(R.attr.colorStatusError, R.attr.colorStatusErrorContainer, R.attr.colorOnStatusErrorContainer),
    INFO(R.attr.colorStatusInfo, R.attr.colorStatusInfoContainer, R.attr.colorOnStatusInfoContainer),
    NEUTRAL(R.attr.colorStatusNeutral, R.attr.colorStatusNeutralContainer, R.attr.colorOnStatusNeutralContainer);

    @ColorInt fun foreground(view: View): Int = MaterialColors.getColor(view, foregroundAttr)
    @ColorInt fun container(view: View): Int = MaterialColors.getColor(view, containerAttr)
    @ColorInt fun onContainer(view: View): Int = MaterialColors.getColor(view, onContainerAttr)

    @ColorInt fun foreground(context: Context): Int = MaterialColors.getColor(context, foregroundAttr, name)
    @ColorInt fun container(context: Context): Int = MaterialColors.getColor(context, containerAttr, name)
    @ColorInt fun onContainer(context: Context): Int = MaterialColors.getColor(context, onContainerAttr, name)
}
