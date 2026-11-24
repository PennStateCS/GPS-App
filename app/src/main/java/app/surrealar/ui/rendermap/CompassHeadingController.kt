package app.surrealar.ui.rendermap

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.pennstatecs.geokit.stakeout.HeadingSource

/**
 * The one rotation-vector (compass) listener of the survey map, shared by stakeout guidance AND the
 * My Location heading arrow. Turns readings into a TRUE heading via [headingProvider], keeps the
 * latest value ([compassHeadingDeg]) and calls [onHeadingChanged] on each reading (main thread).
 *
 * Registration is driven by the host through [sync] (see [isNeeded]); [register]/[unregister] are
 * idempotent. Also logs the heading source (diagnostics only) when its parts change.
 */
internal class CompassHeadingController(
    /** The system SensorManager; may throw when the host is detached (caught and logged). */
    private val sensorManagerProvider: () -> SensorManager?,
    /** Current display rotation (Surface.ROTATION_*), for remapping the rotation vector. */
    private val displayRotation: () -> Int,
    private val headingProvider: HeadingProvider,
    /** A new compass heading arrived (keeps the on-map heading arrow following it). */
    private val onHeadingChanged: () -> Unit,
) {
    internal companion object {
        /**
         * Single source of compass truth: ONE listener when any feature needs it and the screen is
         * resumed; none otherwise.
         */
        fun isNeeded(
            resumed: Boolean, guidanceActive: Boolean, guidanceUsesCompass: Boolean, showMyLocation: Boolean,
        ): Boolean {
            val guidanceWants = guidanceActive && guidanceUsesCompass
            val myLocationWants = showMyLocation
            return resumed && (guidanceWants || myLocationWants)
        }
    }

    var compassHeadingDeg: Double? = null
        private set
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    var isRegistered = false
        private set

    /** True when no position was available, so the heading is relative to MAGNETIC north. */
    val isMagneticOnly: Boolean get() = headingProvider.isMagneticOnly

    internal val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            // Was getOrientation() azimuth = MAGNETIC north, screen-top only (≈11.6° off near Hazleton).
            val rotation = displayRotation()
            compassHeadingDeg = headingProvider.trueHeadingDeg(event.values, rotation)
            logHeadingSourceIfChanged(null)
            // Keep the on-map heading arrow following the compass between fixes (throttled). Delivered
            // on the main thread, so touching the map here is safe.
            onHeadingChanged()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            app.surrealar.util.diagnostics.HeadingDiagnostics.onCompassAccuracy(accuracy)
        }
    }

    /** Registers when [needed], else unregisters. Idempotent — safe to call from any caller. */
    fun sync(needed: Boolean) {
        if (needed) register() else unregister()
    }

    fun register() {
        if (isRegistered) return
        try {
            val sm = sensorManager ?: sensorManagerProvider()?.also { sensorManager = it }
            rotationSensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotationSensor != null) {
                sm?.registerListener(rotationListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                isRegistered = true
            } else {
                compassHeadingDeg = null  // no rotation-vector sensor → callers fall back to COG/north-up
            }
        } catch (e: Exception) { logFail("registerCompass", e); compassHeadingDeg = null }
    }

    fun unregister() {
        if (isRegistered) {
            try { sensorManager?.unregisterListener(rotationListener) } catch (_: Exception) {}
            isRegistered = false
        }
        compassHeadingDeg = null
    }

    // Diagnostics only: log the heading source when its parts change (enum compares, no allocation).
    private var loggedDeclSource: Any? = null
    private var loggedMagneticOnly: Boolean? = null
    private var loggedStakeoutSource: Any? = null
    fun logHeadingSourceIfChanged(stakeoutSource: HeadingSource?) {
        val decl = headingProvider.declinationSource
        val mag = headingProvider.isMagneticOnly
        val st = stakeoutSource ?: loggedStakeoutSource
        if (decl == loggedDeclSource && mag == loggedMagneticOnly && st == loggedStakeoutSource) return
        loggedDeclSource = decl; loggedMagneticOnly = mag; loggedStakeoutSource = st
        app.surrealar.util.diagnostics.HeadingDiagnostics.onSource(
            app.surrealar.util.diagnostics.HeadingDiagnostics.describe((st as? Enum<*>)?.name, decl.name, mag))
    }
}
