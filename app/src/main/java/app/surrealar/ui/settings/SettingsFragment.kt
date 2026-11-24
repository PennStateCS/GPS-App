package app.surrealar.ui.settings

import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.surrealar.R
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.ui.common.BaseTwoPaneFragment
import app.surrealar.ui.settings.categories.AboutSettingsController
import app.surrealar.ui.settings.categories.ActiveSettingsCategory
import app.surrealar.ui.settings.categories.AppearanceSettingsController
import app.surrealar.ui.settings.categories.ArDisplaySettingsController
import app.surrealar.ui.settings.categories.CaptureSettingsController
import app.surrealar.ui.settings.categories.CoordinatesSettingsController
import app.surrealar.ui.settings.categories.DataSettingsController
import app.surrealar.ui.settings.categories.DeveloperSettingsController
import app.surrealar.ui.settings.categories.MapSettingsController
import app.surrealar.ui.settings.categories.ReceiverSettingsController
import app.surrealar.ui.settings.categories.SettingsCategoryController
import app.surrealar.ui.settings.common.settingsError
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Settings screen host: presents the settings categories and edits values through the DataStore-backed
 * settings repositories. Holds no settings state of its own and writes nothing to Room.
 *
 * Each category's UI lives in its controller (`ui/settings/categories`); this fragment owns the
 * category list, selection (incl. [ARG_INITIAL_CATEGORY_ID] from the toolbar), the two-pane layout
 * ([BaseTwoPaneFragment]), the document launchers, and the controllers' lifecycle: one controller
 * per category for the fragment's lifetime, the shown one's view destroyed when another category is
 * shown and in [onDestroyView].
 */
@AndroidEntryPoint
class SettingsFragment : BaseTwoPaneFragment() {

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard
    @Inject
    lateinit var sourceSettings: app.surrealar.gnss.source.SourceSettings
    @Inject
    lateinit var gnssSourceCoordinator: app.surrealar.gnss.source.GnssSourceCoordinator
    @Inject
    lateinit var receiverDevices: ReceiverDeviceManager

    // ─────────────────────────── Preferences / Data ───────────────────────────
    @Inject lateinit var repository: CoordinateRepository
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var modelRepository: app.surrealar.domain.repository.ModelRepository
    @Inject lateinit var exportBackup: app.surrealar.domain.usecase.ExportCoordinateBackupUseCase
    @Inject lateinit var coordinateSystemService: app.surrealar.coords.CoordinateSystemService
    @Inject lateinit var importBackup: app.surrealar.domain.usecase.ImportCoordinateBackupUseCase

    /**
     * Scope for user-triggered settings WRITES (Main dispatcher). It outlives this screen, so a save
     * fired by focus loss as the user leaves Settings still completes; `categoryScope` and the view
     * scopes are cancelled at that moment. Loads and view binding stay on `categoryScope`; UI touched
     * from a save must check that the view still exists.
     */
    private val saveScope: CoroutineScope
        get() = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope

    // Document launchers for Settings → Data. Registered here, at construction, as the Activity Result
    // API requires; the results go to the Data controller (a null uri = cancelled, ignored there).
    // Coordinate backup export: system "create document" save dialog.
    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> dataController.onExportBackupResult(uri) }

    // Survey points CSV export (configured coordinate system).
    private val exportPointsCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> dataController.onExportPointsCsvResult(uri) }

    // Coordinate backup import: system "open document" picker.
    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> dataController.onImportResult(uri) }

    companion object {
        // Category IDs (internal)
        private const val CAT_ID_LOCATION           = 1
        private const val CAT_ID_GNSS_CAPTURE       = 2
        private const val CAT_ID_AR_DISPLAY         = 3
        private const val CAT_ID_COORDINATE_DISPLAY = 4
        private const val CAT_ID_DATA               = 5
        private const val CAT_ID_DEV                = 7
        private const val CAT_ID_ABOUT              = 8
        private const val CAT_ID_APPEARANCE         = 9
        private const val CAT_ID_MAP                = 10
        /** Nav argument: id of the category to open first (e.g. [CATEGORY_RECEIVER] from the toolbar). */
        const val ARG_INITIAL_CATEGORY_ID = "initialCategoryId"
        /** The Receiver category (receiver status, battery, device info). */
        const val CATEGORY_RECEIVER = CAT_ID_LOCATION
    }

    // ───────────────────────────── Lifecycle hooks ─────────────────────────────

    override fun provideCategories(): List<SettingsCategory> = baseCategories()

    /** Opens [ARG_INITIAL_CATEGORY_ID] when given (the toolbar's battery token opens Receiver). */
    override fun initialCategoryIndex(): Int {
        val id = arguments?.getInt(ARG_INITIAL_CATEGORY_ID, -1) ?: -1
        return provideCategories().indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: super.initialCategoryIndex()
    }

    // Categories are listed alphabetically by display name (case-insensitive), with About pinned last.
    private fun baseCategories(): List<SettingsCategory> = listOf(
        SettingsCategory(CAT_ID_APPEARANCE,         "Appearance",              R.drawable.ic_appearance_24),
        SettingsCategory(CAT_ID_AR_DISPLAY,         "AR Display",              R.drawable.ic_section_ar),
        SettingsCategory(CAT_ID_GNSS_CAPTURE,       "Capture",                 R.drawable.ic_satellite_24),
        SettingsCategory(CAT_ID_COORDINATE_DISPLAY, "Coordinates",             R.drawable.ic_list_coordinates),
        SettingsCategory(CAT_ID_DATA,               "Data",                    R.drawable.ic_file),
        SettingsCategory(CAT_ID_DEV,                "Developer Tools",         R.drawable.ic_dev_tools),
        SettingsCategory(CAT_ID_MAP,                "Map",                     R.drawable.ic_map),
        SettingsCategory(CAT_ID_LOCATION,           "Receiver",                R.drawable.ic_section_location),
        SettingsCategory(CAT_ID_ABOUT,              "About",                   R.drawable.ic_section_info),
    )

    private fun refreshCategoriesForSource(@Suppress("UNUSED_PARAMETER") source: LocationSourceType) {
        // RS2+ page removed from Settings; categories are static
        updateCategoriesDynamic(baseCategories())
    }

    // ───────────────────────────── Category controllers ─────────────────────────
    // One controller per category for this fragment's lifetime (see SettingsCategoryController), so
    // state that must survive a category switch (an import in progress, the receiver connect attempt)
    // does, as it did when it lived in this fragment.
    private val activeCategory = ActiveSettingsCategory()
    private val appearanceController by lazy { AppearanceSettingsController(this, settingsRepo, saveScope) }
    private val arDisplayController by lazy { ArDisplaySettingsController(this, settingsRepo, saveScope) }
    private val mapController by lazy { MapSettingsController(this, settingsRepo, settingsRepo, saveScope) }
    private val captureController by lazy { CaptureSettingsController(this, settingsRepo, saveScope) }
    private val coordinatesController by lazy { CoordinatesSettingsController(this, settingsRepo, fixSwitchboard, coordinateSystemService, saveScope) }
    private val developerController by lazy { DeveloperSettingsController(this, settingsRepo, saveScope) }
    private val aboutController by lazy { AboutSettingsController(this) }
    private val dataController: DataSettingsController by lazy {
        DataSettingsController(this, repository, settingsRepo, coordinateSystemService, exportBackup, importBackup, saveScope,
            exportBackupLauncher, exportPointsCsvLauncher, importBackupLauncher)
    }
    private val receiverController by lazy {
        ReceiverSettingsController(this, settingsRepo, fixSwitchboard, gnssSourceCoordinator, receiverDevices, saveScope,
            ::refreshCategoriesForSource)
    }

    private fun controllerFor(categoryId: Int): SettingsCategoryController? = when (categoryId) {
        CAT_ID_APPEARANCE -> appearanceController
        CAT_ID_AR_DISPLAY -> arDisplayController
        CAT_ID_MAP -> mapController
        CAT_ID_GNSS_CAPTURE -> captureController
        CAT_ID_COORDINATE_DISPLAY -> coordinatesController
        CAT_ID_DEV -> developerController
        CAT_ID_ABOUT -> aboutController
        CAT_ID_DATA -> dataController
        CAT_ID_LOCATION -> receiverController
        else -> null
    }

    override fun onDestroyView() {
        activeCategory.replace(null)
        super.onDestroyView()
    }

    /** Called by [BaseTwoPaneFragment] after it cancelled the previous category's scope and removed its view. */
    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? =
        try {
            activeCategory.show(controllerFor(category.id), inflater, contentContainer, categoryScope)
        } catch (e: Exception) {
            settingsError("Settings category build failed id=${category.id}", e)
            null
        }
}
