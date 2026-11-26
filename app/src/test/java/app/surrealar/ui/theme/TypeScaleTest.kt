package app.surrealar.ui.theme

import android.app.Application
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import app.surrealar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The named type scale (values/styles.xml) resolves in light and night with the documented sizes, nothing named is
 * below 12 sp, and the reading styles use tabular figures (docs/design-system.md, "Type scale").
 */
abstract class TypeScaleChecks {
    protected abstract val themeName: String

    private val ctx by lazy { ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SurveyingApp) }

    private class Spec(val name: String, val style: Int, val sp: Float, val tnum: Boolean = false)

    private val scale = listOf(
        Spec("PageTitle", R.style.TextAppearance_App_PageTitle, 24f),
        Spec("SectionHeading", R.style.TextAppearance_App_SectionHeading, 18f),
        Spec("Body", R.style.TextAppearance_App_Body, 16f),
        Spec("Secondary", R.style.TextAppearance_App_Secondary, 14f),
        Spec("Reading", R.style.TextAppearance_App_Reading, 14f, tnum = true),
        Spec("ReadingCompact", R.style.TextAppearance_App_ReadingCompact, 13f, tnum = true),
        Spec("Supporting", R.style.TextAppearance_App_Supporting, 12f),
        Spec("StatusLabel", R.style.TextAppearance_App_StatusLabel, 12f),
        Spec("LabelSmall", R.style.TextAppearance_App_LabelSmall, 12f),
        Spec("Mono", R.style.TextAppearance_App_Mono, 13f),
        Spec("AR.Overlay", R.style.TextAppearance_App_AR_Overlay, 12f),
        Spec("AR.OverlaySmall", R.style.TextAppearance_App_AR_OverlaySmall, 12f),
        Spec("AR.Alert", R.style.TextAppearance_App_AR_Alert, 12f),
        Spec("Toolbar.Label", R.style.TextAppearance_App_Toolbar_Label, 12f),
        Spec("Toolbar.Value", R.style.TextAppearance_App_Toolbar_Value, 14f, tnum = true),
    )

    private fun spOf(style: Int): Float {
        val a = ctx.obtainStyledAttributes(style, intArrayOf(android.R.attr.textSize))
        try { return a.getDimension(0, -1f) / ctx.resources.displayMetrics.scaledDensity } finally { a.recycle() }
    }

    private fun stringOf(style: Int, attr: Int): String? {
        val a = ctx.obtainStyledAttributes(style, intArrayOf(attr))
        try { return a.getString(0) } finally { a.recycle() }
    }

    private fun colorResolves(style: Int): Boolean {
        val a = ctx.obtainStyledAttributes(style, intArrayOf(android.R.attr.textColor))
        try { return a.getColorStateList(0) != null } finally { a.recycle() }
    }

    @Test fun `named text appearances have the documented sizes`() {
        for (s in scale) assertEquals("$themeName ${s.name}", s.sp, spOf(s.style), 0.01f)
    }

    @Test fun `no named text appearance is below 12 sp`() {
        for (s in scale) assertTrue("$themeName ${s.name} is ${spOf(s.style)} sp", spOf(s.style) >= 12f)
    }

    @Test fun `named text appearances resolve a text colour`() {
        for (s in scale) assertTrue("$themeName ${s.name} has no resolvable textColor", colorResolves(s.style))
    }

    @Test fun `reading styles use tabular figures and Mono is monospace`() {
        for (s in scale.filter { it.tnum }) assertEquals("$themeName ${s.name}", "tnum", stringOf(s.style, android.R.attr.fontFeatureSettings))
        assertEquals("monospace", stringOf(R.style.TextAppearance_App_Mono, android.R.attr.fontFamily))
    }

    @Test fun `theme label-small maps to the 12 sp app style`() {
        val tv = TypedValue()
        assertTrue(ctx.theme.resolveAttribute(com.google.android.material.R.attr.textAppearanceLabelSmall, tv, true))
        assertEquals(R.style.TextAppearance_App_LabelSmall, if (tv.resourceId != 0) tv.resourceId else tv.data)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TypeScaleLightTest : TypeScaleChecks() {
    override val themeName = "light"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "night")
class TypeScaleNightTest : TypeScaleChecks() {
    override val themeName = "night"
}
