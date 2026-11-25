package app.surrealar.ui.viewpoints

import android.Manifest
import app.surrealar.coords.frameToken
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.surrealar.BuildConfig
import app.surrealar.domain.coordinates.CoordinateValidator
import app.surrealar.domain.model.CaptureMethod
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.surrealar.R
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.CoordinateFactory
import app.surrealar.domain.model.CoordinateModelLink
import app.surrealar.domain.model.EmbeddedModelLocation
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.domain.model.Model
import app.surrealar.domain.model.ModelLocationConfidence
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import io.github.pennstatecs.geokit.gnss.capture.AveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.ObservationSession
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import app.surrealar.gnss.source.SourceSettings
import io.github.pennstatecs.geokit.gnss.capture.toAveragingPolicy
import io.github.pennstatecs.geokit.gnss.capture.resolvePolicy
import app.surrealar.ui.capture.CaptureViewModel
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import app.surrealar.ui.models.ModelPickerActivity
import app.surrealar.util.GlbGeoreferenceDetector
import io.github.pennstatecs.geokit.projection.Utm
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

/** Default coordinate marker color (app primary blue). */
private const val DEFAULT_COORDINATE_COLOR = 0xFF155DA8.toInt()

/**
 * Dialog for creating a coordinate from a live GNSS/internal fix or manual entry. Capture is gated on
 * a real position and altitude; it never enables Save with a fabricated 0.0 altitude.
 */
@AndroidEntryPoint
class AddCoordinateDialogFragment(
    private val dbModels: List<Model> = emptyList(),
    private val onPointAdded: (Coordinate) -> Unit
) : DialogFragment() {

    /**
     * Used only by Android when it rebuilds the dialog (process death, theme/dark-mode change). The
     * caller's models and callback are gone then, so the dialog closes itself instead of crashing
     * with Fragment.InstantiationException; the user reopens Add Coordinate.
     */
    constructor() : this(emptyList(), {}) { recreatedBySystem = true }

    private var recreatedBySystem = false

    @Inject lateinit var fixSwitchboard: FixSwitchboard
    @Inject lateinit var coordinateSystemService: app.surrealar.coords.CoordinateSystemService
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var sourceSettings: SourceSettings
    @Inject lateinit var coordinateRepository: CoordinateRepository
    @Inject lateinit var validateForSave: app.surrealar.domain.usecase.ValidateCoordinateForSaveUseCase

    private val captureVm: CaptureViewModel by viewModels()

    // Throttle for wrong-provider live-preview diagnostics.
    private var lastCaptureIgnoreLogMs = 0L

    // ── View refs ─────────────────────────────────────────────────────────────
    private var nameEdit: EditText? = null
    private var noteEdit: EditText? = null
    private var sectionInternal: View? = null
    private var locationText: TextView? = null
    private var sectionExternal: View? = null
    private var tvRequirements: TextView? = null
    private var tvSamplingStatus: TextView? = null
    private var progressCapture: ProgressBar? = null
    private var tvElapsed: TextView? = null
    private var tvSamples: TextView? = null
    private var tvFixStatus: TextView? = null
    private var tvAccuracy: TextView? = null
    private var tvAccuracyNote: TextView? = null
    private var tvHdop: TextView? = null
    private var tvSatsUsed: TextView? = null
    private var tvDetails: TextView? = null
    private var btnModel: MaterialButton? = null
    private var btnSave: MaterialButton? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var selectedIconKey: String? = null
    private var capturePolicy: AveragingPolicy? = null
    /** Name of the preset the current capture uses (for the rules line). */
    private var capturePresetLabel: String? = null
    private var isExternalMode = false
    /** False until the selected source has been read; live/session updates wait for it. */
    private var modeResolved = false

    /** Most recent live fix, so the status line can reflect current quality between accepted epochs. */
    private var lastLiveFix: Fix? = null

    // Internal GPS capture state (only used when isExternalMode == false)
    private var latitude = 0.0
    private var longitude = 0.0
    /** Altitude for a model-embedded location; null until one is known (never a fabricated 0.0). */
    private var altitude: Double? = null
    private var providerStr = "fused"
    private var rtkStatusStr: String? = null
    private var satsUsedVal: Int? = null
    private var satsVisibleVal: Int? = null
    private var hdopVal: Double? = null
    private var vDopVal: Double? = null
    private var pDopVal: Double? = null
    private var hAccVal: Double? = null
    private var vAccVal: Double? = null
    private var correctionSourceStr: String? = null
    private var correctionAgeSeconds: Double? = null
    private var correctionStationIdStr: String? = null
    private var speedMpsVal: Double? = null
    private var courseDegVal: Double? = null
    private var timestampSourceStr: String? = null
    private var multipathIndexVal: Double? = null
    private var altitudeMslVal: Double? = null
    private var geoidSeparationVal: Double? = null
    private var crsEpsgVal: Int? = 4326
    private var captureMethodStr: String? = null
    private var stdLatVal: Double? = null
    private var stdLonVal: Double? = null
    private var stdAltVal: Double? = null
    private var capturedTimestampMs = System.currentTimeMillis()

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val id = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_MODEL_ID)
                ?: return@registerForActivityResult
            val name = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_MODEL_NAME)
            val thumbnailPath = data.getStringExtra(ModelPickerActivity.EXTRA_SELECTED_THUMBNAIL_PATH)
            onModelSelected(id, name, thumbnailPath)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, 0)
        if (recreatedBySystem) {
            DiagnosticsLogger.i(DiagTags.CAPTURE, "Add Coordinate dialog closed: recreated by the system")
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        // DialogFragment with onCreateView() defaults to an overly-narrow wrap_content width.
        // Compute the same effective width that MaterialAlertDialogBuilder uses: ~82% of screen
        // width on phones, capped at 560 dp on larger screens.
        val dm = resources.displayMetrics
        val maxPx = (560 * dm.density).toInt()
        val widthPx = minOf((dm.widthPixels * 0.82f).toInt(), maxPx)
        dialog?.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_capture_coordinate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setTitle("Capture Coordinate")

        nameEdit          = view.findViewById(R.id.edit_point_name)
        noteEdit          = view.findViewById(R.id.edit_point_note)
        sectionInternal   = view.findViewById(R.id.section_internal)
        locationText      = view.findViewById(R.id.text_location)
        sectionExternal   = view.findViewById(R.id.section_external)
        tvRequirements    = view.findViewById(R.id.text_capture_requirements)
        tvSamplingStatus  = view.findViewById(R.id.text_sampling_status)
        progressCapture   = view.findViewById(R.id.progress_capture)
        tvElapsed         = view.findViewById(R.id.text_elapsed)
        tvSamples         = view.findViewById(R.id.text_samples)
        tvFixStatus       = view.findViewById(R.id.text_fix_status)
        tvAccuracy        = view.findViewById(R.id.text_accuracy)
        tvAccuracyNote    = view.findViewById(R.id.text_accuracy_note)
        tvHdop            = view.findViewById(R.id.text_hdop)
        tvSatsUsed        = view.findViewById(R.id.text_sats_used)
        tvDetails         = view.findViewById(R.id.text_details)
        btnModel          = view.findViewById(R.id.button_model)
        btnSave           = view.findViewById(R.id.btn_save)

        // Prefill point name from settings
        viewLifecycleOwner.lifecycleScope.launch {
            val coordSettings = settingsRepo.coordinateDisplaySettings.first()
            val prefix = coordSettings.defaultNamePrefix
            val proposedName = if (coordSettings.autoIncrementNames) {
                val names = withContext(Dispatchers.IO) {
                    runCatching { coordinateRepository.getAllCoordinatesList().map { it.name } }.getOrDefault(emptyList())
                }
                PointNameSuggester.next(prefix, names)
            } else {
                prefix
            }
            nameEdit?.setText(proposedName)
        }

        // Model picker
        btnModel?.setOnClickListener {
            modelPickerLauncher.launch(
                ModelPickerActivity.newIntent(requireContext(), "Choose a Model")
            )
        }

        view.findViewById<MaterialButton>(R.id.btn_cancel)?.setOnClickListener {
            captureVm.cancel()
            dismissAllowingStateLoss()
        }

        btnSave?.setOnClickListener { handleSave() }

        // Determine source. Capture MODE follows the user's selected source; BOTH modes are averaged
        // captures using the saved GNSS Capture preset (separate internal / external rules)
        // and share the averaging UI. Provider filtering (averaging input and live preview) keeps the
        // other source's fixes out, so e.g. an External capture started while the receiver is still
        // reconnecting shows a waiting state instead of internal data.
        viewLifecycleOwner.lifecycleScope.launch {
            val sourceSetting = runCatching {
                settingsRepo.locationSource.first()
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture source read failed: using default source=INTERNAL", it)
            }.getOrDefault(LocationSourceType.INTERNAL)
            val activeProvider = sourceSettings.activeProvider.value

            isExternalMode = sourceSetting != LocationSourceType.INTERNAL
            modeResolved = true

            DiagnosticsLogger.i(DiagTags.CAPTURE,
                "Capture opened selectedSource=$sourceSetting activeProvider=$activeProvider " +
                    "mode=${if (isExternalMode) "EXTERNAL" else "INTERNAL"}")

            sectionInternal?.visibility = View.GONE
            sectionExternal?.visibility = View.VISIBLE
            if (isExternalMode) {
                if (activeProvider != SourceSettings.ProviderChoice.EXTERNAL_TCP) {
                    DiagnosticsLogger.i(DiagTags.CAPTURE, "Capture waiting for external receiver data activeProvider=$activeProvider")
                    showExternalWaiting()
                }
                startExternalAveraging()
            } else {
                startInternalAveraging()
            }
        }

        // Observe session state for the averaging UI (both modes)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureVm.state.collect { state ->
                    if (modeResolved) updateExternalUi(state)
                }
            }
        }

        // Capture stopped by the app (receiver datum or GNSS source changed mid-occupation): say so.
        // Nothing from it can be saved; the message stays until a new capture starts.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureVm.interruption.collect { message ->
                    if (message == null) return@collect
                    tvSamplingStatus?.text = message
                    btnSave?.isEnabled = false
                    if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Live fix quality + waiting reasons. Driven by currentFix (current-provider-only, cleared on
        // switch / lost signal). Only fixes from the selected mode's provider are shown — a null or
        // other-provider fix shows "Waiting for … GNSS data", never the other source's coordinates.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixSwitchboard.currentFix.collect { fix ->
                    if (!modeResolved) return@collect
                    val matchesMode = fix != null && (fix.provider == Provider.INTERNAL) == !isExternalMode
                    if (!matchesMode) {
                        if (fix != null) logCaptureIgnore("wrong-provider")
                        showExternalWaiting()
                    } else {
                        updateLiveFix(fix!!)
                    }
                }
            }
        }
    }

    /** "external" / "internal" for status text. */
    private val modeWord: String get() = if (isExternalMode) "external" else "internal"

    /**
     * Shows the waiting state without overwriting a completed result. Used when no fix from the
     * selected mode's provider is available yet (acquiring, receiver reconnecting, signal lost).
     */
    private fun showExternalWaiting() {
        if (captureVm.state.value is ObservationSession.State.Complete) return
        lastLiveFix = null
        tvSamplingStatus?.text = captureVm.interruption.value ?: "Waiting for $modeWord GNSS data"
        tvFixStatus?.text = "—"
        clearPositionQuality()
    }

    /** Throttled (≤ once / 10 s) debug trace for fixes the capture preview dropped (expected while switching). */
    private fun logCaptureIgnore(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureIgnoreLogMs >= 10_000L) {
            lastCaptureIgnoreLogMs = now
            DiagnosticsLogger.d(DiagTags.CAPTURE) { "Capture preview ignored fix reason=$reason" }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel any running session when the dialog is dismissed
        if (captureVm.state.value !is ObservationSession.State.Complete) {
            captureVm.cancel()
        }
        nameEdit = null; noteEdit = null; locationText = null
        sectionInternal = null; sectionExternal = null
        tvRequirements = null
        tvSamplingStatus = null; progressCapture = null
        tvElapsed = null; tvSamples = null; tvFixStatus = null; tvAccuracy = null
        tvAccuracyNote = null; tvHdop = null; tvSatsUsed = null; tvDetails = null
        btnModel = null; btnSave = null
    }

    // ── External GNSS averaging ───────────────────────────────────────────────

    private fun startExternalAveraging() = startAveraging(internal = false)

    /**
     * Internal (device GNSS) capture: an averaged session over internal fixes from the GNSS feed,
     * using the saved preset's internal policy. Averaging removes single-epoch jumps and per-fix
     * jitter and yields a measured spread; the saved point is the mean, like external captures.
     */
    private fun startInternalAveraging() {
        val fine = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) {
            tvSamplingStatus?.text = getString(R.string.location_permission_required)
            btnSave?.isEnabled = false
            return
        }
        startAveraging(internal = true)
    }

    /**
     * Resolves the policy ONCE from the saved preset (Settings → GNSS Capture) and uses that same
     * policy for both the on-screen rules and the session, so they can never disagree.
     */
    private fun startAveraging(internal: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = runCatching { settingsRepo.gnssCaptureSettings.first() }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture policy read failed: using default", it)
            }.getOrDefault(GnssCaptureSettings())
            val policy = settings.resolvePolicy(internal)
            capturePolicy = policy
            capturePresetLabel = settings.preset.label
            renderCaptureRequirements(policy)
            captureVm.start(policy, internal)
        }
    }

    /**
     * Fills the capture-requirements summary from the active [policy] (display only — it never
     * changes capture rules): preset, minimum time, accepted fixes, required quality, precision
     * target, and timeout. The quality clause is omitted when no minimum is enforced.
     */
    private fun renderCaptureRequirements(policy: AveragingPolicy) {
        val qualityClause =
            if (policy.requiredMinStatus.name == "NONE") ""
            else " · ${shortFix(policy.requiredMinStatus.name)}+"
        val precisionClause = policy.horizontalPrecisionTargetM
            ?.let { " · spread ≤ ${formatMeters(it)}" } ?: ""
        val presetClause = capturePresetLabel?.let { "$it · " } ?: ""
        tvRequirements?.text =
            "Rules: $presetClause${policy.minDurationSec} sec min · ${policy.minSamples} fixes$qualityClause" +
                "$precisionClause · ${policy.maxDurationSec} sec timeout"
    }

    /** Metres with cm precision below 1 m ("0.02 m"), one decimal above ("1.5 m"). */
    private fun formatMeters(m: Double): String =
        if (m < 1.0) String.format(Locale.US, "%.2f m", m) else String.format(Locale.US, "%.1f m", m)

    /**
     * Drives the session-progress parts of the external UI: progress bar, Time row,
     * Fixes row, the Save button, and the terminal (Complete / Paused) status text.
     *
     * The current fix-quality row, accuracy row, and the live "waiting for…" status are
     * owned by [updateLiveFix] / [refreshExternalStatus] so they reflect the most recent
     * fix even between accepted epochs.
     */
    private fun updateExternalUi(state: ObservationSession.State) {
        // Internal "Use Model Location" replaced the averaged capture; don't let the (cancelled)
        // session's state disable Save or overwrite the status.
        if (!isExternalMode && captureMethodStr == CaptureMethod.MODEL_EMBEDDED.storageValue) return
        val policy = capturePolicy ?: AveragingPolicy()
        when (state) {
            is ObservationSession.State.Capturing -> {
                val elapsed = state.elapsedSec
                val samples = state.samples
                val timeComplete = elapsed >= policy.minDurationSec
                val fixesComplete = samples >= policy.minSamples

                // Overall progress reaches 100% only when BOTH requirements are met,
                // so elapsed time can never overfill the bar on its own.
                val timeProgress = if (policy.minDurationSec > 0)
                    (elapsed.toFloat() / policy.minDurationSec).coerceIn(0f, 1f) else 1f
                val fixProgress = if (policy.minSamples > 0)
                    (samples.toFloat() / policy.minSamples).coerceIn(0f, 1f) else 1f
                // While waiting only on the precision target, hold just short of 100 % so the bar
                // never claims "done" before the spread is within the target.
                val pct = (min(timeProgress, fixProgress) * 100).roundToInt()
                val target = policy.horizontalPrecisionTargetM
                val precisionMet = target == null || (state.spreadHorizontalM?.let { it <= target } == true)
                progressCapture?.progress = if (precisionMet) pct else pct.coerceAtMost(95)

                // Compact grid cells: "18 / 25 sec", "7 / 10".
                tvElapsed?.text = "$elapsed / ${policy.minDurationSec} sec"
                tvSamples?.text = "$samples / ${policy.minSamples}"

                btnSave?.isEnabled = false
                refreshExternalStatus()
            }
            is ObservationSession.State.Complete -> {
                progressCapture?.progress = 100
                val result = state.result
                val elapsedSec = Duration.between(result.startedAt, result.endedAt).seconds
                tvElapsed?.text = "$elapsedSec sec"
                tvSamples?.text = "${result.samples}"
                tvFixStatus?.text = shortFix(result.rtkStatus?.name ?: "")
                // CaptureResult.hAccM/vAccM are AccuracyEstimator output (receiver-reported when the
                // receiver supplied GST, otherwise a DOP/UERE estimate). The result carries no source
                // flag, so use the last live fix's GST presence to label each axis honestly — defaulting
                // to "estimated" when unknown rather than over-claiming "reported".
                val hReported = lastLiveFix?.hAccM != null
                val vReported = lastLiveFix?.vAccM != null
                renderPositionQuality(
                    reportedHM = if (hReported) result.hAccM else null,
                    estimatedHM = if (hReported) null else result.hAccM,
                    reportedVM = if (vReported) result.vAccM else null,
                    estimatedVM = if (vReported) null else result.vAccM,
                    hdop = result.hdop, satsUsed = result.satsUsed, diffAgeS = result.diffAgeS,
                    spreadHM = result.spreadHorizontalM, spreadVM = result.spreadVerticalM,
                )
                // Maximum sampling time is a safety timeout, not a "force complete". Save Point is
                // enabled ONLY when both minimum sampling time and minimum accepted fixes were met.
                // A timed-out, under-sampled capture shows the timeout message and keeps Save
                // disabled — it is never silently saved.
                if (state.requirementsMet) {
                    tvSamplingStatus?.text = "Ready to save"
                    btnSave?.isEnabled = true
                } else {
                    // Say WHICH requirement was missed: fixes, or the precision target.
                    val target = policy.horizontalPrecisionTargetM
                    val spread = result.spreadHorizontalM
                    val missedPrecision = target != null && result.samples >= policy.minSamples &&
                        (spread == null || spread > target)
                    tvSamplingStatus?.text = if (missedPrecision)
                        "Timed out · spread ±${spread?.let { formatMeters(it) } ?: "—"} (spread target ±${formatMeters(target!!)})" +
                            " · try again or choose another preset"
                    else
                        "Timed out · ${result.samples}/${policy.minSamples} fixes · try again or adjust settings"
                    btnSave?.isEnabled = false
                }
            }
            is ObservationSession.State.Paused -> {
                tvSamplingStatus?.text =
                    if (state.reason == ObservationSession.TIMEOUT_NO_ACCEPTED_FIXES)
                        "Timed out · no fixes met the rules (quality / corrections) · try again or choose another preset"
                    else
                        "Receiver error — check connection and try again."
                btnSave?.isEnabled = false
            }
            is ObservationSession.State.Idle -> {
                btnSave?.isEnabled = false
                refreshExternalStatus()
            }
        }
    }

    /**
     * Observes the live fix stream to keep the current fix-quality and accuracy rows
     * up to date, and to refresh the blocking-reason status line.
     */
    private fun updateLiveFix(fix: Fix) {
        lastLiveFix = fix
        // Once the session is Complete the rows show the final averaged result; don't overwrite.
        if (captureVm.state.value is ObservationSession.State.Complete) return

        val policy = capturePolicy
        val current = shortFix(fix.rtkStatus.name)
        // When the current fix is below the required minimum, make the gap explicit.
        tvFixStatus?.text =
            if (policy != null && !fix.rtkStatus.meetsOrExceeds(policy.requiredMinStatus))
                "$current · need ${shortFix(policy.requiredMinStatus.name)}"
            else
                current
        // Live values are receiver-reported (GST); the app does not estimate during the live preview.
        renderPositionQuality(
            reportedHM = fix.hAccM, estimatedHM = null,
            reportedVM = fix.vAccM, estimatedVM = null,
            hdop = fix.hDop, satsUsed = fix.satsUsed, diffAgeS = fix.diffAgeS,
        )

        refreshExternalStatus()
    }

    /**
     * Recomputes the single most relevant "why can't I save yet" line from the current
     * session progress and the latest live fix.
     *
     * Priority while capturing:
     *  - time done but fixes short  → "Waiting for N more accepted fix(es)"
     *  - fixes done but time short  → "Waiting for minimum sampling time"
     *  - both still short           → current rejection reason, else "Sampling…"
     *
     * Complete and Paused own their own status text and are left untouched.
     */
    private fun refreshExternalStatus() {
        // A stopped capture keeps its "Capture stopped: ..." message.
        captureVm.interruption.value?.let { tvSamplingStatus?.text = it; return }
        val policy = capturePolicy ?: return
        val fix = lastLiveFix
        val reason: String = when (val state = captureVm.state.value) {
            is ObservationSession.State.Capturing -> {
                val timeComplete = state.elapsedSec >= policy.minDurationSec
                val fixesComplete = state.samples >= policy.minSamples
                val remaining = (policy.minSamples - state.samples).coerceAtLeast(0)
                val target = policy.horizontalPrecisionTargetM
                when {
                    timeComplete && fixesComplete && target != null ->
                        "Waiting for spread · ±${state.spreadHorizontalM?.let { formatMeters(it) } ?: "—"}" +
                            " (spread target ±${formatMeters(target)})"
                    timeComplete && !fixesComplete ->
                        "Waiting for $remaining more accepted ${if (remaining == 1) "fix" else "fixes"}"
                    !timeComplete && fixesComplete ->
                        "Waiting for minimum sampling time"
                    else ->
                        fix?.let { qualityWaitReason(it, policy) } ?: "Sampling $modeWord GNSS fixes…"
                }
            }
            is ObservationSession.State.Idle ->
                fix?.let { qualityWaitReason(it, policy) }
                    ?: if (fix == null) "Waiting for current $modeWord GNSS fix" else "No accepted fixes yet"
            else -> return  // Complete / Paused set their own status
        }
        tvSamplingStatus?.text = reason
    }

    /**
     * Returns why [fix] is currently being rejected by [policy], or null if it would be
     * accepted (all gates pass). Used to explain why no fixes are being collected yet.
     */
    private fun qualityWaitReason(fix: Fix, policy: AveragingPolicy): String? {
        if (!fix.rtkStatus.meetsOrExceeds(policy.requiredMinStatus))
            return "Waiting for required fix quality: ${shortFix(policy.requiredMinStatus.name)}"
        val diffAge = fix.diffAgeS
        if (policy.requiresFreshCorrections && diffAge != null && diffAge > policy.maxDiffAgeSec)
            return "Waiting for fresh correction data"
        if (fix.altEllipsoidalM == null)
            return "Waiting for ellipsoidal height"
        return null
    }

    /** Short, toolbar-style fix quality label (delegates to the shared formatter). */
    private fun shortFix(status: String): String =
        app.surrealar.gnss.format.GnssStatusFormatter.formatCaptureFixStatus(status)

    /**
     * Updates the position-quality rows (display only). Horizontal accuracy is labeled reported vs
     * estimated by the caller passing the receiver-reported value and/or an already-computed estimate;
     * this method never estimates. Optional rows are hidden when their value is unavailable.
     */
    private fun renderPositionQuality(
        reportedHM: Double?, estimatedHM: Double?,
        reportedVM: Double?, estimatedVM: Double?,
        hdop: Double?, satsUsed: Int?, diffAgeS: Double?,
        /** Measured spread of the averaged samples (completed captures only). */
        spreadHM: Double? = null, spreadVM: Double? = null,
    ) {
        val h = PositionQualityFormatter.horizontalAccuracy(reportedHM, estimatedHM)
        // Grid cells stay single-line; "—" when unavailable so they never grow the card.
        tvAccuracy?.text = PositionQualityFormatter.horizontalAccuracyCompact(reportedHM, estimatedHM)
        tvSatsUsed?.text = satsUsed?.toString() ?: "—"
        tvHdop?.text = hdop?.let { PositionQualityFormatter.dop(it) } ?: "—"

        // Lower-priority values share one optional line, shown only when present.
        val details = buildList {
            // How tightly the averaged samples clustered — measured, not receiver-reported.
            if (spreadHM != null) {
                add("Spread H ±${String.format(Locale.US, "%.2f", spreadHM)} m" +
                    (spreadVM?.let { " / V ±${String.format(Locale.US, "%.2f", it)} m" } ?: ""))
            }
            PositionQualityFormatter.verticalAccuracy(reportedVM, estimatedVM)?.let { add("V $it") }
            PositionQualityFormatter.correctionAgeSec(diffAgeS)?.let { add("Corr $it") }
        }
        setOptionalText(tvDetails, details.takeIf { it.isNotEmpty() }?.joinToString("  ·  "))

        // Short note only when horizontal accuracy is estimated or unavailable.
        tvAccuracyNote?.visibility =
            if (h.source == PositionQualityFormatter.AccuracySource.REPORTED) View.GONE else View.VISIBLE
    }

    /** Sets [view] text and shows it, or hides it when [text] is null. */
    private fun setOptionalText(view: TextView?, text: String?) {
        if (text == null) {
            view?.visibility = View.GONE
        } else {
            view?.text = text
            view?.visibility = View.VISIBLE
        }
    }

    /** Resets the position-quality grid to the "no fix yet" placeholder. */
    private fun clearPositionQuality() {
        tvAccuracy?.text = "—"
        tvSatsUsed?.text = "—"
        tvHdop?.text = "—"
        tvDetails?.visibility = View.GONE
        tvAccuracyNote?.visibility = View.GONE
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun handleSave() {
        val name = nameEdit?.text?.toString()?.ifBlank { "Unnamed Coordinate" } ?: "Unnamed Coordinate"
        val note = noteEdit?.text?.toString()?.trim()?.ifBlank { null }

        // Split the (legacy) selected icon into the explicit model/built-in fields. The legacy
        // `icon` column is still written for backward compatibility until Stage 4 flips consumers.
        val rawIcon = selectedIconKey
        val linkedModelId = CoordinateModelLink.resolveModelId(null, rawIcon)
        val builtinIconKey = CoordinateModelLink.resolveIconKey(null, null, rawIcon)
        val legacyIcon = rawIcon ?: ""

        // Internal mode can instead save a model's embedded location ("Use Model Location"); every
        // other capture — internal or external — saves the averaged session result.
        val useModelLocation = !isExternalMode && captureMethodStr == CaptureMethod.MODEL_EMBEDDED.storageValue
        if (!useModelLocation) {
            val completedState = captureVm.state.value as? ObservationSession.State.Complete ?: return
            // Guard: never persist a timed-out, under-sampled capture (max sampling time reached
            // before the minimums were met). The Save button is already disabled in that case;
            // this is defense-in-depth so an incomplete capture can never be silently saved.
            if (!completedState.requirementsMet) {
                DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture save blocked: requirements not met, capture timed out")
                return
            }
            viewLifecycleOwner.lifecycleScope.launch {
                // Settings as they were when this capture STARTED (antenna height edited mid-capture
                // must not change a point measured with the old pole).
                val snap = captureVm.settingsAtStart()
                val receiver = snap?.receiver ?: run {
                    // The antenna/pole settings couldn't be read (the snapshot read logged why).
                    // Saving with 0 m would store the antenna height as the ground mark: refuse.
                    DiagnosticsLogger.e(DiagTags.CAPTURE, "Capture save blocked: antenna offset unavailable mode=${if (isExternalMode) "EXTERNAL" else "INTERNAL"}",
                        IllegalStateException("receiver settings unavailable in capture snapshot"))
                    if (isAdded) Toast.makeText(requireContext(), getString(R.string.capture_antenna_offset_unavailable), Toast.LENGTH_LONG).show()
                    return@launch
                }
                // Settings → antenna (pole) height for the antenna that produced the fixes, subtracted
                // so the stored altitude is the ground mark, not the antenna.
                // External: pole height to the bottom of the receiver + the model's phase-centre offset.
                val antennaHeightM = if (isExternalMode)
                    app.surrealar.settings.model.AntennaSetup.resolve(
                        receiver,
                        app.surrealar.settings.model.ExternalReceiverProfile.fromLabel(snap?.externalProfileLabel)
                            ?: app.surrealar.settings.model.ExternalReceiverProfile.DEFAULT,
                    ).captureOffsetM
                    else receiver.internalAntennaHeightM
                // Receiver identity: the selected profile's label (e.g. "Emlid Reach RS4"), the same
                // stable value the Capture screen stores.
                val sourceDevice = if (!isExternalMode) "Internal GPS" else
                    snap?.externalProfileLabel?.takeIf { it.isNotBlank() } ?: "External GNSS"

                // Record the datum the averaged position is in (internal GNSS → WGS84; external per
                // Settings → Coordinates → External receiver datum) so it is converted correctly later.
                val coordSettings = snap?.coordinateSystemSettings
                    ?: app.surrealar.settings.model.CoordinateSystemSettings()
                val datumEpsg = coordinateSystemService.storedDatumEpsg(
                    if (isExternalMode) Provider.EXTERNAL_TCP else Provider.INTERNAL, coordSettings)
                val coordinate = CoordinateFactory.fromCaptureResult(
                    id            = UUID.randomUUID().toString(),
                    name          = name,
                    note          = note,
                    color         = DEFAULT_COORDINATE_COLOR,
                    iconId        = legacyIcon,
                    provider      = if (isExternalMode) Provider.EXTERNAL_TCP else Provider.INTERNAL,
                    result        = completedState.result,
                    captureMethod = (if (isExternalMode) CaptureMethod.EXTERNAL_GNSS else CaptureMethod.INTERNAL_GPS).storageValue,
                    sourceDevice  = sourceDevice,
                    modelId       = linkedModelId,
                    iconKey       = builtinIconKey,
                    antennaHeightM = antennaHeightM,
                    crsEpsg       = datumEpsg,
                    // Provenance only (the feed already normalised to WGS84): the receiver datum that
                    // normalised every averaged fix (a datum change mid-occupation stops the capture);
                    // null when not an external receiver capture.
                    receiverFrame = if (isExternalMode) captureVm.occupationReceiverDatum()?.frameToken() else null
                )
                if (persistIfValid(coordinate)) dismissAllowingStateLoss()
            }
        } else {
            // Model-embedded location: the model's own coordinates, not an antenna measurement, so
            // no antenna height is subtracted.
            val altitude = altitude ?: run {
                DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture save blocked: model location has no altitude")
                return
            }
            val utm = try { Utm.forward(latitude, longitude) } catch (_: Exception) { null }
            val coordinate = Coordinate(
                id                  = UUID.randomUUID().toString(),
                name                = name,
                latitude            = latitude,
                longitude           = longitude,
                altitude            = altitude,
                timestamp           = capturedTimestampMs,
                icon                = legacyIcon,
                color               = DEFAULT_COORDINATE_COLOR,
                provider            = providerStr,
                rtkStatus           = rtkStatusStr,
                satsUsed            = satsUsedVal,
                satsVisible         = satsVisibleVal,
                hdop                = hdopVal,
                vDop                = vDopVal,
                pDop                = pDopVal,
                horizontalAccuracyM = hAccVal,
                verticalAccuracyM   = vAccVal,
                correctionSource    = correctionSourceStr,
                correctionAgeS      = correctionAgeSeconds,
                correctionStationId = correctionStationIdStr,
                speedMps            = speedMpsVal,
                courseDeg           = courseDegVal,
                timestampSource     = timestampSourceStr,
                multipathIndex      = multipathIndexVal,
                altitudeMsl         = altitudeMslVal,
                geoidSeparationM    = geoidSeparationVal,
                crsEpsg             = crsEpsgVal,
                easting             = utm?.eastingM,
                northing            = utm?.northingM,
                utmZone             = utm?.zoneLabel,
                note                = note,
                captureMethod       = captureMethodStr,
                stdLatM             = stdLatVal,
                stdLonM             = stdLonVal,
                stdAltM             = stdAltVal,
                // v10 explicit model association + audit timestamps
                modelId             = linkedModelId,
                iconKey             = builtinIconKey,
                renderEnabled       = true,
                createdAt           = capturedTimestampMs,
                updatedAt           = capturedTimestampMs
            )
            if (persistIfValid(coordinate)) dismissAllowingStateLoss()
        }
    }

    /**
     * Validates [coordinate] and forwards it when valid (returns true). When invalid, shows a
     * user-friendly message, disables Save, and keeps the dialog open (returns false) so an
     * invalid coordinate — out-of-range, NaN, 0,0, or bad model placement — can never be persisted.
     *
     * Delegates to the shared [app.surrealar.domain.usecase.ValidateCoordinateForSaveUseCase] (the
     * same gate the capture use case uses) rather than checking rules inline, so the dialog path and
     * the capture path stay consistent and placement checks (scale > 0, finite offsets) are applied.
     */
    private fun persistIfValid(coordinate: Coordinate): Boolean {
        val validation = validateForSave(coordinate)
        if (!validation.isValid) {
            val msg = validation.errors.firstOrNull() ?: "Invalid coordinate"
            DiagnosticsLogger.w(DiagTags.CAPTURE, "Capture save blocked by validation: ${validation.errors.joinToString("; ")}")
            if (isAdded) Toast.makeText(requireContext(), "Can't save: $msg", Toast.LENGTH_LONG).show()
            btnSave?.isEnabled = false
            return false
        }
        onPointAdded(coordinate)
        return true
    }

    // ── Model selection + embedded-location detection ─────────────────────────

    private fun onModelSelected(modelId: String, name: String?, thumbnailPath: String?) {
        lifecycleScope.launch {
            val model = dbModels.firstOrNull { it.id == modelId }
            val embedded = resolveEmbeddedLocation(model)
            if (embedded != null) {
                showModelLocationDialog(embedded, modelId, name, thumbnailPath)
            } else {
                applySelectedModel(modelId, name, thumbnailPath)
            }
        }
    }

    /**
     * Resolves a model's embedded geographic origin: prefers the value captured at import
     * (georeferenced GLBs are reprojected on import, erasing the in-file signal), and falls
     * back to on-the-fly detection for models imported before that was stored.
     */
    private suspend fun resolveEmbeddedLocation(model: Model?): EmbeddedModelLocation? {
        model ?: return null
        if (model.embeddedLatitude != null && model.embeddedLongitude != null) {
            return EmbeddedModelLocation(
                latitude      = model.embeddedLatitude,
                longitude     = model.embeddedLongitude,
                altitudeMeters = model.embeddedAltitudeM,
                confidence    = ModelLocationConfidence.HIGH,
                source        = "GLB_POSITION_WGS_LIKE"
            )
        }
        val fp = model.filePath
        if (fp.isBlank()) return null
        return withContext(Dispatchers.IO) { GlbGeoreferenceDetector.detect(File(fp)) }
    }

    private fun showModelLocationDialog(
        embedded: EmbeddedModelLocation,
        modelId: String,
        modelName: String?,
        thumbnailPath: String?
    ) {
        // A model without an altitude takes the current fix's ellipsoidal height (said so below);
        // with no live height either, "Use Model Location" is not offered.
        val resolvedAlt = ModelAltitudeResolver.resolve(embedded.altitudeMeters, lastLiveFix?.altEllipsoidalM)
        val altStr = when (resolvedAlt?.source) {
            ModelAltitudeResolver.Source.MODEL -> coordinateSystemService.lengthText(resolvedAlt.altitudeM, 2)
            ModelAltitudeResolver.Source.CURRENT_FIX ->
                "— (not in model; current GNSS height ${coordinateSystemService.lengthText(resolvedAlt.altitudeM, 2)} will be used)"
            null -> "— (not in model, and no live GNSS height yet)"
        }
        val confidenceNote = when (embedded.confidence) {
            ModelLocationConfidence.HIGH   -> ""
            ModelLocationConfidence.MEDIUM -> "\n(Confidence: medium)"
            ModelLocationConfidence.LOW    -> "\n(Confidence: low)"
        }
        val message = String.format(
            Locale.US,
            "This model appears to contain an embedded location:\n\n" +
                "Latitude:  %.7f\nLongitude: %.7f\nAltitude:  %s%s\n\n" +
                "How would you like to place it?",
            embedded.latitude, embedded.longitude, altStr, confidenceNote
        )
        if (!isAdded || activity == null) return
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Model Location Detected")
            .setMessage(message)
        if (resolvedAlt != null) builder.setPositiveButton("Use Model Location") { _, _ ->
                latitude = embedded.latitude
                longitude = embedded.longitude
                val altitude = resolvedAlt.altitudeM
                this.altitude = altitude
                providerStr = "model"
                captureMethodStr = CaptureMethod.MODEL_EMBEDDED.storageValue
                // Model files carry WGS84; shown in the user's system and height reference.
                locationText?.text = runCatching {
                    val p = coordinateSystemService.convertWgs84(latitude, longitude, altitude, System.currentTimeMillis())
                    "From model: ${app.surrealar.coords.CoordinateFormat.horizontal(p, latLonDecimals = 7)} · " +
                        app.surrealar.coords.CoordinateFormat.height(p)
                }.getOrElse { String.format(Locale.US, "From model: %.7f, %.7f, %.2f m", latitude, longitude, altitude) } +
                    if (resolvedAlt.source == ModelAltitudeResolver.Source.CURRENT_FIX)
                        "\nHeight from the current GNSS fix (the model has none)" else ""
                // Internal mode: the model's location replaces the averaged capture — stop the
                // session, show the chosen location, and allow Save.
                if (!isExternalMode) {
                    captureVm.cancel()
                    sectionInternal?.visibility = View.VISIBLE
                    tvSamplingStatus?.text = "Using the model's embedded location"
                    btnSave?.isEnabled = true
                }
                applySelectedModel(modelId, modelName, thumbnailPath)
            }
        builder
            .setNegativeButton("Use Current Coordinate") { _, _ ->
                applySelectedModel(modelId, modelName, thumbnailPath)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun applySelectedModel(modelId: String, name: String?, thumbnailPath: String?) {
        selectedIconKey = CoordinateModelLink.toLegacyIcon(modelId)
        val button = btnModel ?: return
        button.text = name ?: "Selected model"
        if (thumbnailPath.isNullOrBlank()) {
            button.icon = null
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val file = File(thumbnailPath)
                    if (file.exists()) BitmapFactory.decodeFile(thumbnailPath) else null
                } catch (e: Exception) {
                    DiagnosticsLogger.w(DiagTags.MODEL, "Model thumbnail decode failed screen=addCoordinate", e)
                    null
                }
            }
            if (bmp != null) {
                btnModel?.iconTint = null
                btnModel?.icon = bmp.toDrawable(resources)
            }
        }
    }
}
