package com.example.surveyingapp.ui.viewpoints

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.surveyingapp.BuildConfig
import com.example.surveyingapp.domain.coordinates.CoordinateValidator
import com.example.surveyingapp.domain.model.CaptureMethod
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.model.CoordinateFactory
import com.example.surveyingapp.domain.model.CoordinateModelLink
import com.example.surveyingapp.domain.model.EmbeddedModelLocation
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.domain.model.ModelLocationConfidence
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.capture.AveragingPolicy
import com.example.surveyingapp.gnss.capture.ObservationSession
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.capture.GnssCaptureSettings
import com.example.surveyingapp.gnss.source.SourceSettings
import com.example.surveyingapp.gnss.capture.toAveragingPolicy
import com.example.surveyingapp.ui.capture.CaptureViewModel
import com.example.surveyingapp.util.DiagnosticsLogger
import com.example.surveyingapp.ui.models.ModelPickerActivity
import com.example.surveyingapp.util.GlbGeoreferenceDetector
import com.example.surveyingapp.util.UtmConverter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

/** Dev/emulator-only fallback position (Googleplex). Never used in production — see
 *  [AddCoordinateDialogFragment.allowDevLocationFallback]. */
private const val DEV_FALLBACK_LAT = 37.4219999
private const val DEV_FALLBACK_LON = -122.0840575

@AndroidEntryPoint
class AddCoordinateDialogFragment(
    private val highAccuracy: Boolean = true,
    private val dbModels: List<Model> = emptyList(),
    private val onPointAdded: (Coordinate) -> Unit
) : DialogFragment() {

    @Inject lateinit var fixSwitchboard: FixSwitchboard
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var sourceSettings: SourceSettings
    @Inject lateinit var coordinateRepository: CoordinateRepository

    private val captureVm: CaptureViewModel by viewModels()

    // Throttle for wrong-provider live-preview diagnostics.
    private var lastCaptureIgnoreLogMs = 0L

    // ── View refs ─────────────────────────────────────────────────────────────
    private var nameEdit: EditText? = null
    private var noteEdit: EditText? = null
    private var sectionInternal: View? = null
    private var locationText: TextView? = null
    private var sectionExternal: View? = null
    private var tvSamplingStatus: TextView? = null
    private var progressCapture: ProgressBar? = null
    private var tvElapsed: TextView? = null
    private var tvSamples: TextView? = null
    private var tvFixStatus: TextView? = null
    private var tvAccuracy: TextView? = null
    private var btnModel: MaterialButton? = null
    private var btnSave: MaterialButton? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var selectedIconKey: String? = null
    private var capturePolicy: AveragingPolicy? = null
    private var isExternalMode = false

    /** Most recent live fix, so the status line can reflect current quality between accepted epochs. */
    private var lastLiveFix: Fix? = null

    // Internal GPS capture state (only used when isExternalMode == false)
    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0
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
        tvSamplingStatus  = view.findViewById(R.id.text_sampling_status)
        progressCapture   = view.findViewById(R.id.progress_capture)
        tvElapsed         = view.findViewById(R.id.text_elapsed)
        tvSamples         = view.findViewById(R.id.text_samples)
        tvFixStatus       = view.findViewById(R.id.text_fix_status)
        tvAccuracy        = view.findViewById(R.id.text_accuracy)
        btnModel          = view.findViewById(R.id.button_model)
        btnSave           = view.findViewById(R.id.btn_save)

        // Prefill point name from settings
        viewLifecycleOwner.lifecycleScope.launch {
            val coordSettings = settingsRepo.coordinateDisplaySettings.first()
            val prefix = coordSettings.defaultNamePrefix
            val proposedName = if (coordSettings.autoIncrementNames) {
                val count = withContext(Dispatchers.IO) {
                    coordinateRepository.count()
                }
                "$prefix ${count + 1}"
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

        // Determine source and show the appropriate section. Capture MODE follows the user's
        // selected source (Internal = instant fused capture, External = averaged GNSS capture).
        // Live data correctness is enforced separately by provider filtering (averaging input and
        // live preview both reject non-external fixes), so an External capture started while the
        // receiver is still reconnecting simply shows a waiting state instead of internal data.
        viewLifecycleOwner.lifecycleScope.launch {
            val sourceSetting = runCatching {
                settingsRepo.locationSource.first()
            }.getOrDefault(LocationSourceType.INTERNAL)
            val activeProvider = sourceSettings.activeProvider.value

            isExternalMode = sourceSetting != LocationSourceType.INTERNAL

            DiagnosticsLogger.i("Capture",
                "Capture opened selectedSource=$sourceSetting activeProvider=$activeProvider " +
                    "mode=${if (isExternalMode) "EXTERNAL" else "INTERNAL"}")

            if (isExternalMode) {
                sectionInternal?.visibility = View.GONE
                sectionExternal?.visibility = View.VISIBLE
                if (activeProvider != SourceSettings.ProviderChoice.EXTERNAL_TCP) {
                    DiagnosticsLogger.i("Capture", "External capture waiting for external data (activeProvider=$activeProvider)")
                    showExternalWaiting()
                }
                startExternalAveraging()
            } else {
                sectionInternal?.visibility = View.VISIBLE
                sectionExternal?.visibility = View.GONE
                fetchInternalOneShot()
            }
        }

        // Observe session state for the external averaging UI
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureVm.state.collect { state ->
                    if (isExternalMode) updateExternalUi(state)
                }
            }
        }

        // Show live fix quality + waiting reasons during external mode.
        //
        // Driven by currentFix (current-provider-only, cleared on switch). For external capture we
        // only display EXTERNAL-provider fixes — a null or internal fix means the receiver hasn't
        // delivered data yet, so we show "Waiting for external GNSS data" instead of ever surfacing
        // internal GPS coordinates as the external capture value.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixSwitchboard.currentFix.collect { fix ->
                    if (!isExternalMode) return@collect
                    if (fix == null || fix.provider == Provider.INTERNAL) {
                        if (fix?.provider == Provider.INTERNAL) logCaptureIgnore("wrong-provider")
                        showExternalWaiting()
                    } else {
                        updateLiveFix(fix)
                    }
                }
            }
        }
    }

    /**
     * Shows the external-capture waiting state without overwriting a completed result. Used when
     * External is selected but no external-provider fix is available yet (receiver reconnecting).
     */
    private fun showExternalWaiting() {
        if (captureVm.state.value is ObservationSession.State.Complete) return
        lastLiveFix = null
        tvSamplingStatus?.text = "Waiting for external GNSS data"
        tvFixStatus?.text = "--"
        tvAccuracy?.text = "--"
    }

    /** Throttled (≤ once / 10 s) diagnostic for fixes the capture preview dropped. */
    private fun logCaptureIgnore(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureIgnoreLogMs >= 10_000L) {
            lastCaptureIgnoreLogMs = now
            DiagnosticsLogger.w("Capture", "Ignored $reason fix in live preview")
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
        tvSamplingStatus = null; progressCapture = null
        tvElapsed = null; tvSamples = null; tvFixStatus = null; tvAccuracy = null
        btnModel = null; btnSave = null
    }

    // ── External GNSS averaging ───────────────────────────────────────────────

    private fun startExternalAveraging() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = runCatching {
                settingsRepo.gnssCaptureSettings.first()
            }.getOrDefault(GnssCaptureSettings())
            capturePolicy = settings.toAveragingPolicy()
            captureVm.startWithSavedPolicy()
        }
    }

    /**
     * Drives the session-progress parts of the external UI: progress bar, Time row,
     * Fixes row, the Save button, and the terminal (Complete / Paused) status text.
     *
     * The current fix-quality row, accuracy row, and the live "waiting for…" status are
     * owned by [updateLiveFix] / [refreshExternalStatus] so they reflect the most recent
     * fix even between accepted epochs.
     */
    private fun updateExternalUi(state: ObservationSession.State) {
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
                progressCapture?.progress = (min(timeProgress, fixProgress) * 100).roundToInt()

                // Time row never shows a fraction that can exceed its target.
                tvElapsed?.text = if (timeComplete)
                    "Complete · $elapsed sec elapsed"
                else
                    "$elapsed / ${policy.minDurationSec} sec"

                tvSamples?.text = "$samples / ${policy.minSamples} accepted"

                btnSave?.isEnabled = false
                refreshExternalStatus()
            }
            is ObservationSession.State.Complete -> {
                progressCapture?.progress = 100
                val result = state.result
                val elapsedSec = Duration.between(result.startedAt, result.endedAt).seconds
                tvElapsed?.text = "Complete · $elapsedSec sec elapsed"
                tvSamples?.text = "${result.samples} accepted"
                tvFixStatus?.text = shortFix(result.rtkStatus?.name ?: "")
                tvAccuracy?.text = formatAccuracy(result.hAccM)
                // Maximum sampling time is a safety timeout, not a "force complete". Save Point is
                // enabled ONLY when both minimum sampling time and minimum accepted fixes were met.
                // A timed-out, under-sampled capture shows the timeout message and keeps Save
                // disabled — it is never silently saved.
                if (state.requirementsMet) {
                    tvSamplingStatus?.text = "Ready to save"
                    btnSave?.isEnabled = true
                } else {
                    tvSamplingStatus?.text =
                        "Capture timed out before requirements were met. " +
                            "Accepted fixes: ${result.samples} / ${policy.minSamples}. " +
                            "Try lowering the required fix quality or minimum accepted fixes, " +
                            "or wait for a better GNSS signal."
                    btnSave?.isEnabled = false
                }
            }
            is ObservationSession.State.Paused -> {
                tvSamplingStatus?.text = "Receiver error — check connection and try again."
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
        tvAccuracy?.text = formatAccuracy(fix.hAccM)

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
        val policy = capturePolicy ?: return
        val fix = lastLiveFix
        val reason: String = when (val state = captureVm.state.value) {
            is ObservationSession.State.Capturing -> {
                val timeComplete = state.elapsedSec >= policy.minDurationSec
                val fixesComplete = state.samples >= policy.minSamples
                val remaining = (policy.minSamples - state.samples).coerceAtLeast(0)
                when {
                    timeComplete && !fixesComplete ->
                        "Waiting for $remaining more accepted ${if (remaining == 1) "fix" else "fixes"}"
                    !timeComplete && fixesComplete ->
                        "Waiting for minimum sampling time"
                    else ->
                        fix?.let { qualityWaitReason(it, policy) } ?: "Sampling external GNSS fixes…"
                }
            }
            is ObservationSession.State.Idle ->
                fix?.let { qualityWaitReason(it, policy) }
                    ?: if (fix == null) "Waiting for current external GNSS fix" else "No accepted fixes yet"
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
        if (Duration.between(fix.timeUtc, Instant.now()).seconds > policy.maxFixAgeSec)
            return "Waiting for fresh GNSS fix"
        val diffAge = fix.diffAgeS
        if (diffAge != null && diffAge > policy.maxDiffAgeSec)
            return "Waiting for fresh correction data"
        if (fix.altEllipsoidalM == null)
            return "Waiting for ellipsoidal height"
        return null
    }

    /** Short, toolbar-style fix quality label (delegates to the shared formatter). */
    private fun shortFix(status: String): String =
        com.example.surveyingapp.gnss.format.GnssStatusFormatter.formatCaptureFixStatus(status)

    /** Formats horizontal accuracy, or "Not reported" when the receiver did not supply it. */
    private fun formatAccuracy(meters: Double?): String =
        meters?.let { com.example.surveyingapp.gnss.format.GnssStatusFormatter.formatAccuracyMeters(it) }
            ?: "Not reported"

    // ── Internal GPS instant capture ──────────────────────────────────────────

    /** True only when a hardcoded dev fallback location may be used (debug build on an emulator). */
    private val allowDevLocationFallback: Boolean
        get() = BuildConfig.DEBUG && isProbablyEmulator()

    private fun isProbablyEmulator(): Boolean {
        val fp = Build.FINGERPRINT.orEmpty()
        return fp.startsWith("generic") || fp.startsWith("unknown") ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.BRAND.startsWith("generic") ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }

    /** Applies a validated internal-GPS location and enables Save. */
    private fun acceptInternalLocation(
        lat: Double, lon: Double, alt: Double,
        hAcc: Double?, vAcc: Double?, modeLabel: String,
        method: CaptureMethod, suffix: String = ""
    ) {
        latitude = lat; longitude = lon; altitude = alt
        providerStr = "fused"
        captureMethodStr = method.storageValue
        hAccVal = hAcc; vAccVal = vAcc
        capturedTimestampMs = System.currentTimeMillis()
        locationText?.text =
            getString(R.string.location_label_with_mode, lat, lon, alt, modeLabel) + suffix
        btnSave?.isEnabled = true
    }

    private suspend fun fetchInternalOneShot() {
        captureMethodStr = CaptureMethod.INTERNAL_GPS.storageValue
        btnSave?.isEnabled = false   // stay disabled until a real, valid fix arrives
        val fineGranted = ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            locationText?.text = getString(R.string.location_permission_required)
            btnSave?.isEnabled = false
            return
        }

        locationText?.text = getString(R.string.fetching_location)  // "Waiting for location…"

        val fused: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(requireActivity())
        val cts = CancellationTokenSource()
        val priority = if (fineGranted && highAccuracy)
            Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        // 1) Fresh one-shot fix.
        val result = withTimeoutOrNull(6_000L) {
            @Suppress("MissingPermission")
            fused.getCurrentLocation(priority, cts.token).awaitSafe()
        }
        if (result != null && CoordinateValidator.isValidLatLon(result.latitude, result.longitude)) {
            acceptInternalLocation(
                result.latitude, result.longitude, result.altitude,
                hAcc = if (result.hasAccuracy()) result.accuracy.toDouble() else null,
                vAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && result.hasVerticalAccuracy())
                    result.verticalAccuracyMeters.toDouble() else null,
                modeLabel = if (highAccuracy && fineGranted) "INTERNAL-HIGH" else "INTERNAL",
                method = CaptureMethod.INTERNAL_GPS
            )
            return
        }

        // 2) Last-known fix (only if it is itself valid).
        val last = try {
            @Suppress("MissingPermission") fused.lastLocation.awaitSafe()
        } catch (_: Exception) { null }
        if (last != null && CoordinateValidator.isValidLatLon(last.latitude, last.longitude)) {
            acceptInternalLocation(
                last.latitude, last.longitude, last.altitude,
                hAcc = if (last.hasAccuracy()) last.accuracy.toDouble() else null,
                vAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && last.hasVerticalAccuracy())
                    last.verticalAccuracyMeters.toDouble() else null,
                modeLabel = "LAST-KNOWN", method = CaptureMethod.INTERNAL_GPS, suffix = " (fallback)"
            )
            return
        }

        // 3) Developer/emulator-only fallback. NEVER runs in production: gated to debug builds
        //    on an emulator, and tagged as a SIMULATOR capture so it is clearly not field data.
        if (allowDevLocationFallback) {
            latitude = DEV_FALLBACK_LAT; longitude = DEV_FALLBACK_LON; altitude = 0.0
            providerStr = "fused"
            captureMethodStr = CaptureMethod.SIMULATOR.storageValue
            hAccVal = null; vAccVal = null
            capturedTimestampMs = System.currentTimeMillis()
            locationText?.text = String.format(
                Locale.US, "%.6f, %.6f (developer fallback — emulator)", latitude, longitude
            )
            btnSave?.isEnabled = true
            return
        }

        // 4) No valid fix. Clear any stale position, show a friendly error, keep Save disabled.
        latitude = 0.0; longitude = 0.0
        locationText?.text = getString(R.string.location_fix_failed)
        btnSave?.isEnabled = false
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

        if (isExternalMode) {
            val completedState = captureVm.state.value as? ObservationSession.State.Complete ?: return
            // Guard: never persist a timed-out, under-sampled capture (max sampling time reached
            // before the minimums were met). The Save button is already disabled in that case;
            // this is defense-in-depth so an incomplete capture can never be silently saved.
            if (!completedState.requirementsMet) {
                DiagnosticsLogger.w("Capture", "Save blocked: requirements not met (timed out)")
                return
            }
            viewLifecycleOwner.lifecycleScope.launch {
                // Prefer the user's device name; otherwise record the selected receiver profile
                // (e.g. "Emlid Reach RS4") rather than a hardcoded "RS2+".
                val sourceDevice = runCatching {
                    settingsRepo.externalTcpName.first()?.takeIf { it.isNotBlank() }
                        ?: settingsRepo.externalReceiverProfile.first().label
                }.getOrNull() ?: "External GNSS"

                val coordinate = CoordinateFactory.fromCaptureResult(
                    id            = UUID.randomUUID().toString(),
                    name          = name,
                    note          = note,
                    color         = DEFAULT_COORDINATE_COLOR,
                    iconId        = legacyIcon,
                    provider      = Provider.RS2_EXTERNAL,
                    result        = completedState.result,
                    captureMethod = CaptureMethod.EXTERNAL_GNSS.storageValue,
                    sourceDevice  = sourceDevice,
                    modelId       = linkedModelId,
                    iconKey       = builtinIconKey
                )
                if (persistIfValid(coordinate)) dismissAllowingStateLoss()
            }
        } else {
            val utm = try { UtmConverter.latLonToUtm(latitude, longitude) } catch (_: Exception) { null }
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
                easting             = utm?.easting,
                northing            = utm?.northing,
                utmZone             = utm?.utmZone,
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
     * invalid coordinate — out-of-range, NaN, or 0,0 — can never be persisted.
     */
    private fun persistIfValid(coordinate: Coordinate): Boolean {
        val validation = CoordinateValidator.validate(coordinate)
        if (!validation.isValid) {
            val msg = validation.errors.firstOrNull() ?: "Invalid coordinate"
            DiagnosticsLogger.w("Capture", "Save blocked: ${validation.errors.joinToString()}")
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
        val altStr = embedded.altitudeMeters?.let { String.format(Locale.US, "%.2f m", it) } ?: "—"
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Model Location Detected")
            .setMessage(message)
            .setPositiveButton("Use Model Location") { _, _ ->
                latitude = embedded.latitude
                longitude = embedded.longitude
                altitude = embedded.altitudeMeters ?: altitude
                providerStr = "model"
                captureMethodStr = CaptureMethod.MODEL_EMBEDDED.storageValue
                locationText?.text = String.format(
                    Locale.US, "From model: %.6f, %.6f, %.2fm", latitude, longitude, altitude
                )
                // Enable save for internal mode when model location is used
                if (!isExternalMode) btnSave?.isEnabled = true
                applySelectedModel(modelId, modelName, thumbnailPath)
            }
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
                    Log.w("AddCoordinateDialog", "Failed to decode thumbnail: $thumbnailPath", e)
                    null
                }
            }
            if (bmp != null) {
                btnModel?.iconTint = null
                btnModel?.icon = bmp.toDrawable(resources)
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitSafe(): T? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            addOnFailureListener { if (cont.isActive) cont.resume(null) }
            addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        }
}
