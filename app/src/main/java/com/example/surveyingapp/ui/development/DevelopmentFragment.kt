package com.example.surveyingapp.ui.development

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.surveyingapp.R
import com.example.surveyingapp.databinding.FragmentDevelopmentBinding
import com.example.surveyingapp.ui.viewpoints.CoordinatesViewModel
import com.example.surveyingapp.data.Coordinate
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class DevelopmentFragment : Fragment() {

    private var _binding: FragmentDevelopmentBinding? = null
    private val binding get() = _binding!!

    private val ARCORE_SDK_VERSION = "1.44.0" // Keep in sync with build.gradle dependency

    private lateinit var coordinatesViewModel: CoordinatesViewModel
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastPermissionStatus: String = ""
    private var lastArStatus: String = ""
    private var lastPointCount: Int = 0
    private var lastPointSample: String = ""

    private var expandedLocation = false
    private var expandedAr = false
    private var expandedDiagnostics = false
    private var expandedData = false

    private var arInstallRequested = false
    private var installingArcore = false
    private var arInstallRetryCount = 0
    private var arTransientPolling = false

    // ---- Modern permission launchers ----
    private val requestLocationPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            updatePermissionStatus()
            val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            Toast.makeText(
                requireContext(),
                if (granted) getString(R.string.location_permissions_granted) else getString(R.string.location_permissions_denied),
                Toast.LENGTH_LONG
            ).show()
        }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            updateArStatus()
            Toast.makeText(
                requireContext(),
                if (granted) getString(R.string.camera_permission_granted) else getString(R.string.camera_permission_denied_toast),
                Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevelopmentBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // ViewModel for points
        coordinatesViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(requireActivity().application)
        ).get(CoordinatesViewModel::class.java)

        coordinatesViewModel.allCoordinates.observe(viewLifecycleOwner) { points ->
            lastPointCount = points.size
            lastPointSample = if (points.isNotEmpty()) points.take(3).joinToString("\n") { p ->
                "• ${p.id} ${p.name} (${String.format(Locale.US, "%.5f, %.5f", p.latitude, p.longitude)})"
            } else "(none)"
            updateDiagnostics()
        }

        // Initial status updates
        updatePermissionStatus()
        updateArStatus()
        updateDiagnostics()

        // Buttons
        binding.btnRefreshPermissions.setOnClickListener { updatePermissionStatus() }
        binding.btnRequestPermissions.setOnClickListener { requestLocationPermissions() }
        binding.btnCheckArStatus.setOnClickListener { updateArStatus() }
        binding.btnRequestCamera.setOnClickListener { requestCameraPermission() }
        binding.btnFakePoints.setOnClickListener { createLocationBasedFakePoints() }
        binding.btnClearAllPoints.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_all_coordinates_title)
                .setMessage(R.string.clear_all_coordinates_message)
                .setPositiveButton(R.string.yes_clear_all) { _, _ ->
                    coordinatesViewModel.deleteAllCoordinates()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.btnCopyDevReport.setOnClickListener { copyDiagnosticsToClipboard() }
        // Default: try direct install flow; we may override the click in updateArStatus()
        binding.btnInstallArcore.setOnClickListener { attemptArCoreInstall() }

        if (savedInstanceState != null) {
            expandedLocation = savedInstanceState.getBoolean(KEY_LOC_EXP, true)
            expandedAr = savedInstanceState.getBoolean(KEY_AR_EXP, true)
            expandedDiagnostics = savedInstanceState.getBoolean(KEY_DIAG_EXP, true)
            expandedData = savedInstanceState.getBoolean(KEY_DATA_EXP, true)
        }
        setupCollapsibles()
        applyExpandedStates(animated = false)

        // Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        return root
    }

    private fun setupCollapsibles() {
        setupSection(
            header = binding.headerLocation,
            content = binding.contentLocation,
            arrow = binding.arrowLocation,
            getExpanded = { expandedLocation },
            setExpanded = { expandedLocation = it }
        )
        setupSection(
            header = binding.headerAr,
            content = binding.contentAr,
            arrow = binding.arrowAr,
            getExpanded = { expandedAr },
            setExpanded = { expandedAr = it }
        )
        setupSection(
            header = binding.headerDiagnostics,
            content = binding.contentDiagnostics,
            arrow = binding.arrowDiagnostics,
            getExpanded = { expandedDiagnostics },
            setExpanded = { expandedDiagnostics = it }
        )
        setupSection(
            header = binding.headerData,
            content = binding.contentData,
            arrow = binding.arrowData,
            getExpanded = { expandedData },
            setExpanded = { expandedData = it }
        )
    }

    private fun setupSection(
        header: ViewGroup,
        content: View,
        arrow: ImageView,
        getExpanded: () -> Boolean,
        setExpanded: (Boolean) -> Unit
    ) {
        header.setOnClickListener {
            val newState = !getExpanded()
            setExpanded(newState)
            toggleContent(content, arrow, newState, animated = true)
        }
    }

    private fun applyExpandedStates(animated: Boolean) {
        toggleContent(binding.contentLocation, binding.arrowLocation, expandedLocation, animated)
        toggleContent(binding.contentAr, binding.arrowAr, expandedAr, animated)
        toggleContent(binding.contentDiagnostics, binding.arrowDiagnostics, expandedDiagnostics, animated)
        toggleContent(binding.contentData, binding.arrowData, expandedData, animated)
    }

    private fun toggleContent(content: View, arrow: ImageView, expanded: Boolean, animated: Boolean) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        val targetRotation = if (expanded) 0f else -90f
        if (animated) {
            arrow.animate().rotation(targetRotation)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setDuration(180L).start()
        } else {
            arrow.rotation = targetRotation
        }
    }

    // ---------------- Permissions ----------------

    private fun requestLocationPermissions() {
        requestLocationPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), getString(R.string.camera_already_granted), Toast.LENGTH_SHORT).show()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updatePermissionStatus() {
        try {
            val fineLocationGranted =
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
            val coarseLocationGranted =
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED

            val statusText = buildString {
                appendLine("Location Permissions:")
                appendLine("• Fine Location: ${if (fineLocationGranted) "✓ GRANTED" else "✗ DENIED"}")
                appendLine("• Coarse Location: ${if (coarseLocationGranted) "✓ GRANTED" else "✗ DENIED"}")
                appendLine()
                appendLine("Summary:")
                when {
                    fineLocationGranted || coarseLocationGranted -> {
                        appendLine("✓ Location access available")
                        appendLine("Add Point dialog should work properly")
                    }
                    else -> {
                        appendLine("✗ No location permissions granted")
                        appendLine("Add Point dialog will request permission first")
                    }
                }
            }
            lastPermissionStatus = statusText.trimEnd()
            if (_binding != null) binding.textPermissionStatus.text = statusText
            updateDiagnostics()
        } catch (e: Exception) {
            if (_binding != null) binding.textPermissionStatus.text = getString(R.string.error_checking_permissions, e.message ?: "?")
        }
    }

    // ---------------- AR / ARCore ----------------

    private fun attemptArCoreInstall() {
        if (installingArcore || !isAdded || _binding == null) return

        val ctx = requireContext()
        val availability = ArCoreApk.getInstance().checkAvailability(ctx)

        // Don’t try to install if device is not capable or still checking.
        if (!availability.isSupported || availability.isTransient) {
            Toast.makeText(
                ctx,
                if (availability.isTransient) getString(R.string.checking_ar_availability) else getString(R.string.device_not_ar_capable),
                Toast.LENGTH_LONG
            ).show()
            updateArStatus()
            return
        }

        try {
            val status = ArCoreApk.getInstance().requestInstall(requireActivity(), !arInstallRequested)
            when (status) {
                InstallStatus.INSTALL_REQUESTED -> {
                    arInstallRequested = true
                    installingArcore = true
                    arInstallRetryCount = 0 // reset retries on fresh request
                    updateInstallButtonState(disable = true, label = getString(R.string.installing_arcore), showProgress = true)
                    Toast.makeText(ctx, getString(R.string.arcore_install_requested_toast), Toast.LENGTH_SHORT).show()
                }
                InstallStatus.INSTALLED -> {
                    installingArcore = false
                    updateInstallButtonState(disable = false, label = getString(R.string.install_update_arcore), showProgress = false)
                    Toast.makeText(ctx, getString(R.string.ar_session_created), Toast.LENGTH_SHORT).show()
                    updateArStatus()
                }
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            installingArcore = false
            updateInstallButtonState(disable = false, label = getString(R.string.install_arcore), showProgress = false)
            Toast.makeText(requireContext(), getString(R.string.user_declined_arcore_install), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // If the device isn’t compatible, don’t keep retrying.
            val fatal = e.javaClass.simpleName.contains("NotCompatible", ignoreCase = true)
            installingArcore = false
            val willRetry = if (fatal) false else scheduleArInstallRetry()
            val msg = if (willRetry)
                getString(R.string.install_failed_retrying, e.javaClass.simpleName)
            else
                getString(R.string.install_failed, e.javaClass.simpleName)
            updateInstallButtonState(disable = willRetry, label = if (willRetry) getString(R.string.retrying_ellipsis) else getString(R.string.install_arcore), showProgress = willRetry)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleArInstallRetry(): Boolean {
        val maxRetries = 3
        if (arInstallRetryCount >= maxRetries) return false
        arInstallRetryCount++
        val baseDelayMs = 1500L
        val delay = min(baseDelayMs * (1 shl (arInstallRetryCount - 1)), 8000L)
        binding.btnInstallArcore.postDelayed({
            if (!isAdded || _binding == null) return@postDelayed
            installingArcore = false // allow attempt
            attemptArCoreInstall()
        }, delay)
        return true
    }

    private fun getArCoreApkVersion(): String = try {
        val pm = requireContext().packageManager
        val pkg = "com.google.ar.core"
        val pInfo = pm.getPackageInfo(pkg, 0)
        val versionName = pInfo.versionName ?: "?"
        @Suppress("DEPRECATION")
        val versionCode: Long =
            if (Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()
        "$versionName (code $versionCode)"
    } catch (_: Exception) {
        "Not installed"
    }

    private fun scheduleArTransientRefresh() {
        if (arTransientPolling || !isAdded || _binding == null) return
        arTransientPolling = true
        binding.textArStatus.postDelayed({
            arTransientPolling = false
            if (isAdded) updateArStatus()
        }, 800)
    }

    private data class HardwareInfo(
        val hasGyro: Boolean,
        val hasAccel: Boolean,
        val hasCamera: Boolean,
        val hasBackCam: Boolean,
        val hasDepth: Boolean,
        val summary: String
    )

    private fun collectArHardwareInfo(): HardwareInfo {
        val pm = requireContext().packageManager
        fun f(name: String) = pm.hasSystemFeature(name)
        val gyro = f(PackageManager.FEATURE_SENSOR_GYROSCOPE)
        val accel = f(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
        val cam = f(PackageManager.FEATURE_CAMERA_ANY)
        val backCam = f(PackageManager.FEATURE_CAMERA)
        val depth = f("android.hardware.sensor.depth") || f("android.hardware.camera.depth")
        val parts = mutableListOf<String>()
        parts += if (gyro) "gyro" else "no-gyro"
        parts += if (accel) "accel" else "no-accel"
        parts += if (cam) "cam" else "no-cam"
        parts += if (backCam) "backcam" else "no-backcam"
        parts += if (depth) "depth" else "no-depth"
        return HardwareInfo(gyro, accel, cam, backCam, depth, parts.joinToString("/"))
    }

    private fun deriveUnsupportedReason(
        av: ArCoreApk.Availability,
        apkVersion: String,
        glOk: Boolean,
        glVersion: String,
        hw: HardwareInfo? = null
    ): String {
        if (!glOk) return "OpenGL ES $glVersion < 3.0"

        return when (av) {
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> buildString {
                append("Device not calibrated for ARCore")
                if (hw != null) {
                    append(" (need gyro + accelerometer + back camera; have: ${hw.summary})")
                    if (!hw.hasGyro) append("; missing gyroscope")
                    if (!hw.hasAccel) append("; missing accelerometer")
                    if (!hw.hasBackCam) append("; missing back camera")
                }
                append(". Some devices (incl. certain tablets) are not on Google's supported list.")
            }
            ArCoreApk.Availability.UNKNOWN_ERROR -> "Play Services/ARCore reported an unknown error"
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> "Availability check timed out (network/Play Services)"
            ArCoreApk.Availability.UNKNOWN_CHECKING -> "Still checking availability"
            ArCoreApk.Availability.SUPPORTED_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "Supported"
        }
    }

    private fun openPlayServicesForArInStore() {
        val pkg = "com.google.ar.core"
        val market = "market://details?id=$pkg".let { Uri.parse(it) }
        val web = "https://play.google.com/store/apps/details?id=$pkg".let { Uri.parse(it) }
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, market).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(i)
        } catch (_: Exception) {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, web))
        }
    }

    private fun checkOpenGlSupport(): Pair<Boolean, String> {
        return try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val cfg = am.deviceConfigurationInfo
            val req = cfg.reqGlEsVersion
            val major = ((req and 0xffff0000.toInt()) shr 16)
            val minor = (req and 0x0000ffff)
            val versionStr = "$major.$minor"
            val ok = major >= 3 // Require GLES 3.0+
            ok to "GLES $versionStr"
        } catch (e: Exception) {
            false to "Unknown ($e)"
        }
    }

    private fun updateArStatus() {
        // Move heavy operations off UI thread to prevent freezing
        Thread {
            try {
                val ctx = requireContext()
                val availability = ArCoreApk.getInstance().checkAvailability(ctx)
                val cameraGranted =
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                val arCoreApkVersion = getArCoreApkVersion()
                val (glOk, glVersionStr) = checkOpenGlSupport()
                val availabilityLabel = availability.toString()
                val supportCategory = when (availability) {
                    ArCoreApk.Availability.SUPPORTED_INSTALLED -> "Supported (installed)"
                    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "Supported (APK too old)"
                    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "Supported (not installed)"
                    ArCoreApk.Availability.UNKNOWN_CHECKING -> "Checking"
                    ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> "Timed out (will retry)"
                    ArCoreApk.Availability.UNKNOWN_ERROR -> "Unknown error"
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "Unsupported (not capable)"
                }
                val hardwareInfo = collectArHardwareInfo()
                val reason = if (!availability.isSupported && !availability.isTransient) {
                    deriveUnsupportedReason(availability, arCoreApkVersion, glOk, glVersionStr, hardwareInfo)
                } else ""

                val statusText = buildString {
                    appendLine("AR Status:")
                    appendLine("• Availability Enum: $availabilityLabel")
                    appendLine("• Support Category: $supportCategory")
                    appendLine("• Camera Permission: ${if (cameraGranted) "✓ GRANTED" else "✗ DENIED"}")
                    appendLine("• ARCore APK Version: $arCoreApkVersion")
                    appendLine("• SDK Dependency: $ARCORE_SDK_VERSION")
                    appendLine("• OpenGL ES >= 3.0: ${if (glOk) "✓ ($glVersionStr)" else "✗ ($glVersionStr)"}")
                    appendLine("• Hardware: ${hardwareInfo.summary}")
                    if (reason.isNotBlank()) appendLine("• Reason: $reason")
                    @Suppress("DEPRECATION")
                    appendLine("• Known Enums: ${ArCoreApk.Availability.values().joinToString { it.name }}")
                    appendLine()
                    when {
                        !glOk -> appendLine("Result: OpenGL ES < 3.0.")
                        availability.isTransient -> appendLine("Result: Checking… will refresh shortly.")
                        !availability.isSupported -> appendLine("Result: Device not supported ($availabilityLabel).")
                        cameraGranted -> appendLine("Result: Ready for AR session.")
                        else -> appendLine("Result: Request camera permission.")
                    }
                }

                // Update UI on main thread
                requireActivity().runOnUiThread {
                    if (!isAdded || _binding == null) return@runOnUiThread

                    lastArStatus = statusText.trimEnd()
                    binding.textArStatus.text = statusText

                    // Button logic - moved to UI thread
                    when (availability) {
                        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                            binding.btnInstallArcore.visibility = View.VISIBLE
                            updateInstallButtonState(false, getString(R.string.install_arcore_play_store), false)
                            binding.btnInstallArcore.setOnClickListener { openPlayServicesForArInStore() }
                        }
                        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                            binding.btnInstallArcore.visibility = View.VISIBLE
                            updateInstallButtonState(false, getString(R.string.update_arcore_play_store), false)
                            binding.btnInstallArcore.setOnClickListener { openPlayServicesForArInStore() }
                        }
                        ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                            binding.btnInstallArcore.visibility = View.VISIBLE
                            updateInstallButtonState(false, getString(R.string.recheck_arcore), false)
                            binding.btnInstallArcore.setOnClickListener { attemptArCoreInstall() }
                        }
                        else -> {
                            if (availability.isTransient) {
                                binding.btnInstallArcore.visibility = View.GONE
                                scheduleArTransientRefresh()
                            } else {
                                binding.btnInstallArcore.visibility = View.GONE
                            }
                        }
                    }

                    if (availability.isTransient) {
                        scheduleArTransientRefresh()
                    }
                    updateDiagnostics()
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    if (_binding != null) binding.textArStatus.text = "Error checking AR status: ${e.message ?: "Unknown error"}"
                }
            }
        }.start()
    }

    private fun updateInstallButtonState(disable: Boolean, label: String, showProgress: Boolean) {
        if (_binding == null) return
        binding.btnInstallArcore.isEnabled = !disable
        binding.btnInstallArcore.alpha = if (disable) 0.5f else 1f
        binding.btnInstallArcore.text = label
        binding.progressArInstall.visibility = if (showProgress) View.VISIBLE else View.GONE
    }

    // ---------------- Diagnostics / Utilities ----------------

    private fun updateDiagnostics() {
        if (_binding == null) return
        val context = requireContext()
        val pm = context.packageManager
        val pkgName = context.packageName
        val versionName: String
        val versionCode: Long
        try {
            val pInfo = pm.getPackageInfo(pkgName, 0)
            versionName = pInfo.versionName ?: "?"
            @Suppress("DEPRECATION")
            versionCode = if (Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()
        } catch (e: Exception) {
            binding.textDiagnostics.text = getString(R.string.error_reading_app_version, e.message ?: "?")
            return
        }
        val runtime = Runtime.getRuntime()
        val usedMemMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemMB = runtime.maxMemory() / 1024 / 1024
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = timeFmt.format(Date())
        val diagnostics = buildString {
            appendLine("Dev Report @ $now")
            appendLine("App: $pkgName v$versionName (code $versionCode)")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Memory: ${usedMemMB}MB used / ${maxMemMB}MB max")
            appendLine("Points: $lastPointCount")
            if (lastPointSample.isNotBlank()) appendLine("Sample Points:\n$lastPointSample")
            appendLine()
            appendLine(lastPermissionStatus)
            appendLine()
            appendLine(lastArStatus)
        }
        binding.textDiagnostics.text = diagnostics.trimEnd()
    }

    private fun copyDiagnosticsToClipboard() {
        if (_binding == null) return
        val text = binding.textDiagnostics.text?.toString() ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dev Report", text))
        Toast.makeText(requireContext(), getString(R.string.dev_report_copied), Toast.LENGTH_SHORT).show()
    }

    // ---------------- Location ----------------

    private fun createLocationBasedFakePoints() {
        if (!isAdded || _binding == null) return

        // Check for location permissions first
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            Toast.makeText(
                requireContext(),
                "Location permission required to create location-based fake points",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Get the current location
        try {
            @Suppress("MissingPermission")
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    // Create fake points around the current location
                    val baseLat = location.latitude
                    val baseLon = location.longitude
                    val now = System.currentTimeMillis()
                    val colors = listOf(
                        0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFF81C784.toInt(),
                        0xFFFFB74D.toInt(), 0xFFBA68C8.toInt(), 0xFFA1C181.toInt(),
                        0xFFFF8A65.toInt(), 0xFF9FA8DA.toInt(), 0xFF4DB6AC.toInt(),
                        0xFFFFF176.toInt(), 0xFFAED581.toInt(), 0xFFFFAB91.toInt(),
                        0xFF90CAF9.toInt(), 0xFFCE93D8.toInt(), 0xFFF48FB1.toInt()
                    )
                    val icons = listOf("ic_menu_camera", "ic_menu_gallery", "ic_menu_slideshow")

                    val fakePoints: List<Coordinate> = listOf(
                        // Very close points (1-5 feet) - approximately 0.0003-0.0015 degrees
                        Coordinate(
                            id = "fake_1",
                            name = "Very Close Point 1",
                            latitude = baseLat + 0.000003, // ~1 foot north
                            longitude = baseLon + 0.000002,
                            altitude = 100.0,
                            timestamp = now,
                            icon = icons[0],
                            color = colors[0]
                        ),
                        Coordinate(
                            id = "fake_2",
                            name = "Very Close Point 2",
                            latitude = baseLat - 0.000005, // ~1.5 feet south
                            longitude = baseLon - 0.000003,
                            altitude = 100.5,
                            timestamp = now,
                            icon = icons[1],
                            color = colors[1]
                        ),
                        Coordinate(
                            id = "fake_3",
                            name = "Very Close Point 3",
                            latitude = baseLat + 0.000008, // ~2.5 feet north
                            longitude = baseLon,
                            altitude = 99.8,
                            timestamp = now,
                            icon = icons[2],
                            color = colors[2]
                        ),
                        Coordinate(
                            id = "fake_4",
                            name = "Very Close Point 4",
                            latitude = baseLat,
                            longitude = baseLon + 0.000012, // ~4 feet east
                            altitude = 100.2,
                            timestamp = now,
                            icon = icons[0],
                            color = colors[3]
                        ),
                        Coordinate(
                            id = "fake_5",
                            name = "Very Close Point 5",
                            latitude = baseLat - 0.000015, // ~5 feet south
                            longitude = baseLon - 0.000010, // ~3 feet west
                            altitude = 99.5,
                            timestamp = now,
                            icon = icons[1],
                            color = colors[4]
                        ),

                        // Medium distance points (10-100 feet) - approximately 0.003-0.03 degrees
                        Coordinate(
                            id = "fake_6",
                            name = "Medium Point 1",
                            latitude = baseLat + 0.0003, // ~100 feet north
                            longitude = baseLon,
                            altitude = 102.0,
                            timestamp = now,
                            icon = icons[2],
                            color = colors[5]
                        ),
                        Coordinate(
                            id = "fake_7",
                            name = "Medium Point 2",
                            latitude = baseLat,
                            longitude = baseLon + 0.0002, // ~65 feet east
                            altitude = 98.5,
                            timestamp = now,
                            icon = icons[0],
                            color = colors[6]
                        ),
                        Coordinate(
                            id = "fake_8",
                            name = "Medium Point 3",
                            latitude = baseLat - 0.0001, // ~35 feet south
                            longitude = baseLon - 0.00015, // ~50 feet west
                            altitude = 101.5,
                            timestamp = now,
                            icon = icons[1],
                            color = colors[7]
                        ),
                        Coordinate(
                            id = "fake_9",
                            name = "Medium Point 4",
                            latitude = baseLat + 0.00025, // ~80 feet north
                            longitude = baseLon + 0.0001, // ~35 feet east
                            altitude = 99.2,
                            timestamp = now,
                            icon = icons[2],
                            color = colors[8]
                        ),

                        // Far points (0.5-2 km) - approximately 0.005-0.02 degrees
                        Coordinate(
                            id = "fake_10",
                            name = "Far Point 1",
                            latitude = baseLat + 0.009, // ~1 km north
                            longitude = baseLon,
                            altitude = 105.0,
                            timestamp = now,
                            icon = icons[0],
                            color = colors[9]
                        ),
                        Coordinate(
                            id = "fake_11",
                            name = "Far Point 2",
                            latitude = baseLat - 0.009, // ~1 km south
                            longitude = baseLon,
                            altitude = 95.0,
                            timestamp = now,
                            icon = icons[1],
                            color = colors[10]
                        ),
                        Coordinate(
                            id = "fake_12",
                            name = "Far Point 3",
                            latitude = baseLat,
                            longitude = baseLon + 0.009, // ~1 km east
                            altitude = 110.0,
                            timestamp = now,
                            icon = icons[2],
                            color = colors[11]
                        ),
                        Coordinate(
                            id = "fake_13",
                            name = "Far Point 4",
                            latitude = baseLat,
                            longitude = baseLon - 0.009, // ~1 km west
                            altitude = 90.0,
                            timestamp = now,
                            icon = icons[0],
                            color = colors[12]
                        ),
                        // Create a far diagonal point (2km northeast)
                        Coordinate(
                            id = "fake_14",
                            name = "Far Point 5",
                            latitude = baseLat + 0.018, // ~2 km north
                            longitude = baseLon + 0.018, // ~2 km east (diagonal)
                            altitude = 120.0,
                            timestamp = now,
                            icon = icons[1],
                            color = colors[13]
                        ),
                        // Create a far diagonal point (0.5km southwest)
                        Coordinate(
                            id = "fake_15",
                            name = "Far Point 6",
                            latitude = baseLat - 0.0045, // ~0.5 km south
                            longitude = baseLon - 0.0045, // ~0.5 km west (diagonal)
                            altitude = 85.0,
                            timestamp = now,
                            icon = icons[2],
                            color = colors[14]
                        )
                    )

                    // Add all fake points to the ViewModel for display
                    fakePoints.forEach { coordinate ->
                        coordinatesViewModel.addCoordinate(coordinate)
                    }

                    // Show success message with count of created points
                    Toast.makeText(
                        requireContext(),
                        "Created ${fakePoints.size} fake points around current location (5 very close, 4 medium distance, 6 far)",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // Handle case where location is null
                    Toast.makeText(
                        requireContext(),
                        "Unable to get current location",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.addOnFailureListener { e ->
                // Handle location request failure
                Toast.makeText(
                    requireContext(),
                    "Error getting location: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            // Handle any other exceptions during location request
            Toast.makeText(
                requireContext(),
                "Error getting location: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------- Lifecycle ----------------

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updatePermissionStatus()
            updateArStatus()
            updateDiagnostics()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOC_EXP, expandedLocation)
        outState.putBoolean(KEY_AR_EXP, expandedAr)
        outState.putBoolean(KEY_DIAG_EXP, expandedDiagnostics)
        outState.putBoolean(KEY_DATA_EXP, expandedData)
    }

    companion object {
        private const val KEY_LOC_EXP = "key_location_expanded"
        private const val KEY_AR_EXP = "key_ar_expanded"
        private const val KEY_DIAG_EXP = "key_diagnostics_expanded"
        private const val KEY_DATA_EXP = "key_data_expanded"
    }
}
