package app.surrealar.ui.settings.categories

import android.app.Application
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import app.surrealar.R
import app.surrealar.coords.CoordinateSystemService
import app.surrealar.domain.repository.CoordinateDisplaySettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.settings.model.CoordinateDisplaySettings
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import android.widget.LinearLayout
import app.surrealar.coords.ConvertedPosition
import app.surrealar.ui.settings.common.inputLayoutOf
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.pennstatecs.geokit.gnss.model.Fix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoordinatesSettingsControllerTest {

    private class FakeRepo(display: CoordinateDisplaySettings, system: CoordinateSystemSettings) : CoordinateDisplaySettingsRepository {
        val displayState = MutableStateFlow(display)
        val systemState = MutableStateFlow(system)
        val savedDisplay = mutableListOf<CoordinateDisplaySettings>()
        val savedSystem = mutableListOf<CoordinateSystemSettings>()
        override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = displayState
        override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) { savedDisplay += settings; displayState.value = settings }
        override val coordinateSystemSettings: Flow<CoordinateSystemSettings> = systemState
        override suspend fun setCoordinateSystemSettings(settings: CoordinateSystemSettings) { savedSystem += settings; systemState.value = settings }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun show(
        repo: CoordinateDisplaySettingsRepository,
        fix: Fix? = null,
        service: CoordinateSystemService = mockService(),
    ): View {
        val host = ControllerHost()
        val switchboard = Mockito.mock(FixSwitchboard::class.java)
        Mockito.`when`(switchboard.currentFix).thenReturn(MutableStateFlow(fix))
        // Product status computed inline here (the off-main dispatch is covered by CoordinatesSettingsOffMainTest).
        return host.show(CoordinatesSettingsController(host.fragment, repo, switchboard, service, mainScope(),
            statusDispatcher = Dispatchers.Unconfined), mainScope())
    }

    private val heightData = MutableStateFlow(0L)

    private fun mockService(): CoordinateSystemService = Mockito.mock(CoordinateSystemService::class.java).also {
        Mockito.`when`(it.heightDataVersion).thenReturn(heightData)
    }

    /** Kotlin-safe Mockito matcher for non-null parameters. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T { Mockito.any<T>(); return null as T }

    /** A repository whose stored values arrive only when [release]d (a slow DataStore read at screen open). */
    private class GatedRepo : CoordinateDisplaySettingsRepository {
        val display = MutableStateFlow<CoordinateDisplaySettings?>(null)
        val system = MutableStateFlow<CoordinateSystemSettings?>(null)
        val savedDisplay = mutableListOf<CoordinateDisplaySettings>()
        val savedSystem = mutableListOf<CoordinateSystemSettings>()
        override val coordinateDisplaySettings: Flow<CoordinateDisplaySettings> = display.filterNotNull()
        override suspend fun setCoordinateDisplaySettings(settings: CoordinateDisplaySettings) { savedDisplay += settings; display.value = settings }
        override val coordinateSystemSettings: Flow<CoordinateSystemSettings> = system.filterNotNull()
        override suspend fun setCoordinateSystemSettings(settings: CoordinateSystemSettings) { savedSystem += settings; system.value = settings }
        fun release(d: CoordinateDisplaySettings, s: CoordinateSystemSettings) { display.value = d; system.value = s }
    }

    private fun AutoCompleteTextView.pick(index: Int) = onItemClickListener.onItemClick(null, this, index, index.toLong())

    private val stored = CoordinateSystemSettings(horizontal = HorizontalSystem.UTM, vertical = VerticalReference.GEOID2022,
        unit = LinearUnit.US_SURVEY_FEET, nsrs2022Preview = true)

    @Test fun `binds the coordinate system and display settings`() {
        val repo = FakeRepo(CoordinateDisplaySettings(showAccuracyIndicators = false, defaultNamePrefix = "Stake"),
            CoordinateSystemSettings(horizontal = HorizontalSystem.UTM))
        val view = show(repo)
        assertEquals(HorizontalSystem.UTM.label, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.row_coord_spcs_zone).visibility)
        assertEquals("Current position: waiting for GNSS", view.findViewById<TextView>(R.id.text_coord_preview).text.toString())
        assertEquals(false, view.findViewById<MaterialSwitch>(R.id.switch_show_accuracy_indicators).isChecked)
        assertEquals("Stake", view.findViewById<EditText>(R.id.edit_coordinate_name_prefix).text.toString())
        assertTrue(repo.savedDisplay.isEmpty())
        assertTrue(repo.savedSystem.isEmpty())
    }

    @Test fun `SPCS2022 saved - preview ON shows the zone, its frame and the beta note, OFF shows the default with a note`() {
        val saved = CoordinateSystemSettings(horizontal = HorizontalSystem.SPCS2022, spcs2022Zone = "spcs2022:421001", nsrs2022Preview = true)
        val repo = FakeRepo(CoordinateDisplaySettings(), saved)
        val view = show(repo)
        val h = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal)
        val d = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum)
        val z = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_spcs_zone)
        val note = view.findViewById<TextView>(R.id.text_coord_horizontal_note)
        assertEquals(HorizontalSystem.SPCS2022.label, h.text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.row_coord_spcs_zone).visibility)
        assertTrue(z.text.toString(), z.text.startsWith("SPCS2022 Pennsylvania North East (421001) · NATRF2022"))
        assertEquals("NATRF2022 (zone frame, beta)", d.text.toString())
        assertFalse("the zone fixes the frame", d.isEnabled)
        assertEquals(View.VISIBLE, note.visibility)
        assertTrue(note.text.toString(), note.text.contains("Beta: SPCS2022") && note.text.contains("not authoritative"))
        // Preview off: stored value kept, default shown, note says why.
        view.findViewById<MaterialSwitch>(R.id.switch_coord_nsrs2022_preview).isChecked = false
        assertEquals(saved.copy(nsrs2022Preview = false), repo.savedSystem.last())
        assertEquals(HorizontalSystem.GEOGRAPHIC.label, h.text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.row_coord_spcs_zone).visibility)
        assertTrue(note.text.toString(), note.text.contains("preview is off"))
        assertEquals(OutputDatum.WGS84.label, d.text.toString())
        assertTrue(d.isEnabled)
    }

    @Test fun `2022 datum saved - preview ON shows it with the caveat, OFF shows WGS84 with a note, preview OFF hides beta options`() {
        val saved = CoordinateSystemSettings(datum = OutputDatum.PATRF2022, nsrs2022Preview = true)
        val repo = FakeRepo(CoordinateDisplaySettings(), saved)
        val view = show(repo)
        val d = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum)
        val note = view.findViewById<TextView>(R.id.text_coord_datum_note)
        assertEquals("PATRF2022 (beta)", d.text.toString())
        assertTrue(note.text.toString(), note.text.contains("Hawaii") && note.text.contains("EPP2022 beta") && note.text.contains("undocumented"))
        assertEquals(OutputDatum.entries.size, d.adapter.count)
        assertEquals(HorizontalSystem.entries.size, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal).adapter.count)
        view.findViewById<MaterialSwitch>(R.id.switch_coord_nsrs2022_preview).isChecked = false
        assertEquals(OutputDatum.WGS84.label, d.text.toString())
        assertTrue(note.text.toString(), note.text.contains("preview is off"))
        assertEquals(OutputDatum.PATRF2022, repo.savedSystem.last().datum)
        assertEquals(3, d.adapter.count)
        assertEquals(3, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal).adapter.count)
    }

    @Test fun `preview OFF with legacy settings - no beta note, no beta option`() {
        val view = show(FakeRepo(CoordinateDisplaySettings(), CoordinateSystemSettings()))
        assertEquals(View.GONE, view.findViewById<View>(R.id.text_coord_horizontal_note).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.text_coord_datum_note).visibility)
        assertEquals(3, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum).adapter.count)
        assertEquals(3, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal).adapter.count)
    }

    @Test fun `a name prefix over 20 characters is rejected, a valid one saves`() {
        val repo = FakeRepo(CoordinateDisplaySettings(), CoordinateSystemSettings())
        val view = show(repo)
        val prefix = view.findViewById<EditText>(R.id.edit_coordinate_name_prefix)

        prefix.setText("A".repeat(21))
        prefix.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertNotNull(inputLayoutOf(prefix)?.error)
        assertTrue(repo.savedDisplay.isEmpty())

        prefix.setText("Pt")
        prefix.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals(CoordinateDisplaySettings(defaultNamePrefix = "Pt"), repo.savedDisplay.last())
    }

    @Test fun `switching the accuracy indicator persists it`() {
        val repo = FakeRepo(CoordinateDisplaySettings(), CoordinateSystemSettings())
        val view = show(repo)
        view.findViewById<MaterialSwitch>(R.id.switch_show_accuracy_indicators).isChecked = false
        assertEquals(CoordinateDisplaySettings(showAccuracyIndicators = false), repo.savedDisplay.last())
    }

    // ── Phase 4 WP U ────────────────────────────────────────────────────────────────────────────

    @Test fun `save race - a pick before the first bind changes only that field, the stored preview stays ON`() {
        val repo = GatedRepo()
        val view = show(repo)
        // The DataStore read has not returned yet: the screen still holds defaults (preview OFF).
        view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum).pick(OutputDatum.entries.indexOf(OutputDatum.NAD83_2011))
        assertTrue("nothing written before the stored settings are known", repo.savedSystem.isEmpty())
        repo.release(CoordinateDisplaySettings(), stored)
        assertEquals(listOf(stored.copy(datum = OutputDatum.NAD83_2011)), repo.savedSystem)
        assertTrue(repo.savedSystem.single().nsrs2022Preview)
        assertEquals(VerticalReference.GEOID2022, repo.savedSystem.single().vertical)
        // The screen ends up showing the merged, stored settings.
        assertTrue(view.findViewById<MaterialSwitch>(R.id.switch_coord_nsrs2022_preview).isChecked)
        assertEquals(HorizontalSystem.UTM.label, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_horizontal).text.toString())
        assertEquals(OutputDatum.NAD83_2011.label, view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum).text.toString())
    }

    @Test fun `save race - a display switch flipped before the first bind keeps the other stored switch`() {
        val repo = GatedRepo()
        val view = show(repo)
        val sw = view.findViewById<MaterialSwitch>(R.id.switch_show_accuracy_indicators)
        val picked = !sw.isChecked
        sw.isChecked = picked
        val storedDisplay = CoordinateDisplaySettings(showAccuracyIndicators = !picked, autoIncrementNames = false, defaultNamePrefix = "Stake")
        repo.release(storedDisplay, CoordinateSystemSettings())
        assertEquals(listOf(storedDisplay.copy(showAccuracyIndicators = picked)), repo.savedDisplay)
    }

    @Test fun `quick successive picks are merged in order, none lost`() {
        val repo = FakeRepo(CoordinateDisplaySettings(), stored)
        val view = show(repo)
        view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_datum).pick(OutputDatum.entries.indexOf(OutputDatum.ITRF2020))
        view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_unit).pick(LinearUnit.entries.indexOf(LinearUnit.METERS))
        assertEquals(stored.copy(datum = OutputDatum.ITRF2020, unit = LinearUnit.METERS), repo.systemState.value)
    }

    @Test fun `preview toggle preserves the saved GEOID2022 selection`() {
        val repo = FakeRepo(CoordinateDisplaySettings(), stored)
        val view = show(repo)
        val sw = view.findViewById<MaterialSwitch>(R.id.switch_coord_nsrs2022_preview)
        val height = view.findViewById<AutoCompleteTextView>(R.id.spinner_coord_vertical)
        assertEquals(VerticalReference.GEOID2022.label, height.text.toString())
        sw.isChecked = false
        assertEquals(stored.copy(nsrs2022Preview = false), repo.savedSystem.last())
        assertEquals("saved choice kept", VerticalReference.GEOID2022, repo.savedSystem.last().vertical)
        assertEquals("default shown while off", VerticalReference.RECEIVER_MSL.label, height.text.toString())
        sw.isChecked = true
        assertEquals(stored, repo.savedSystem.last())
        assertEquals(VerticalReference.GEOID2022.label, height.text.toString())
    }

    @Test fun `preview ON - verified and Verified NGS beta groups (frames, SPCS2022 from the beta registry), OFF - hidden`() {
        val repo = FakeRepo(CoordinateDisplaySettings(), stored)
        val view = show(repo)
        val groups = view.findViewById<LinearLayout>(R.id.container_coord_nsrs2022_groups)
        assertEquals(View.VISIBLE, groups.visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.text_coord_nsrs2022_status).visibility)
        val texts = allTexts(groups)
        for (t in listOf("Verified systems", "Verified NGS beta")) {
            assertTrue("$t in $texts", texts.any { it == t })
        }
        // Phase 4 WP X: with the preview ON the app uses NSRS2022_BETA: nothing is left unsupported.
        assertFalse(texts.toString(), texts.any { it == "Unsupported / not yet verified" })
        assertTrue(texts.any { it.startsWith("GEOID2022 heights (NAPGD2022): beta") && it.contains(CoordinatesSettingsController.BETA_BADGE) })
        for (n in listOf("NATRF2022", "PATRF2022", "CATRF2022", "MATRF2022", "SPCS2022 (953 NGS beta zones)")) {
            assertTrue("$n in $texts", texts.any { it.startsWith("$n: beta") && it.contains(CoordinatesSettingsController.BETA_BADGE) })
        }
        assertFalse(texts.any { it.contains("awaiting") })
        // Beta rows: selectable, the caveat behind the info dialog; a tap opens details only.
        val rows = (0 until groups.childCount).map { groups.getChildAt(it) }.filterIsInstance<LinearLayout>()
        val natrf = rows.first { it.contentDescription?.startsWith("NATRF2022") == true }
        assertFalse(natrf.contentDescription.toString(), natrf.contentDescription.contains("not selectable"))
        assertTrue(natrf.contentDescription.toString(), natrf.contentDescription.contains("beta"))
        assertTrue(natrf.performClick())
        assertTrue("no save from an info tap", repo.savedSystem.isEmpty())

        view.findViewById<MaterialSwitch>(R.id.switch_coord_nsrs2022_preview).isChecked = false
        assertEquals(View.GONE, groups.visibility)
    }

    @Test fun `zone hint gets the SPCS2022 note once the status is computed`() {
        val view = show(FakeRepo(CoordinateDisplaySettings(), CoordinateSystemSettings(horizontal = HorizontalSystem.STATE_PLANE)))
        assertEquals("All 118 SPCS83 zones. Type a state name to filter. SPCS2022: not yet available.",
            view.findViewById<TextView>(R.id.text_coord_zone_hint).text.toString())
    }

    @Test fun `preview refreshes when a height model finished loading, without a new fix`() {
        var loaded = false
        val service = mockService()
        fun pos(): ConvertedPosition = ConvertedPosition(
            horizontal = HorizontalSystem.GEOGRAPHIC, datum = OutputDatum.WGS84, unit = LinearUnit.METERS,
            latDeg = 41.0, lonDeg = -76.0, easting = null, northing = null, zoneLabel = null,
            vertical = VerticalReference.GEOID2022, height = if (loaded) 389.123 else null,
            heightUnavailableReason = if (loaded) null else "GEOID2022 data is loading",
            verticalLabel = "NAPGD2022 beta", heightLoading = !loaded)
        Mockito.doAnswer { pos() }.`when`(service).convertFix(anyArg(), anyArg())
        val view = show(FakeRepo(CoordinateDisplaySettings(), stored), fix = fix, service = service)
        val preview = view.findViewById<TextView>(R.id.text_coord_preview)
        assertTrue(preview.text.toString(), preview.text.contains("GEOID2022 data is loading"))
        loaded = true
        heightData.value = 1   // AppGeoidModels / GEOID18 load-completion signal
        assertTrue(preview.text.toString(), preview.text.contains("389.123 m NAPGD2022 beta"))
        assertFalse(preview.text.contains("loading"))
    }

    private val fix = Fix(
        provider = io.github.pennstatecs.geokit.gnss.model.Provider.EXTERNAL_TCP, timeUtc = java.time.Instant.parse("2026-11-20T12:00:00Z"),
        timestampSource = io.github.pennstatecs.geokit.gnss.model.TimestampSource.UNKNOWN, latDeg = 41.0, lonDeg = -76.0,
        altEllipsoidalM = 360.0, altMslM = 391.6, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = io.github.pennstatecs.geokit.gnss.model.RtkStatus.FIX, satsUsed = 20, satsVisible = 30,
        diffAgeS = null, speedMps = null, courseDeg = null,
    )

    private fun allTexts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { allTexts(v.getChildAt(it)) }
        else -> emptyList()
    }
}
