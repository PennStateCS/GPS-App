package app.surrealar.util.diagnostics.incident

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.surrealar.SurRealApplicationEntryPoint
import app.surrealar.util.ArSessionDiagnostics
import app.surrealar.util.diagnostics.GnssFeedLogger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads [IncidentContextInputs] from state the app already keeps — no new subscriptions:
 * the active source ([app.surrealar.gnss.source.SourceSettings]), the saved receiver profile,
 * [GnssFeedLogger]'s link + feed trackers, and [ArSessionDiagnostics] (which the AR screen updates
 * when camera/Earth tracking change). One-shot reads only.
 */
class AndroidIncidentContextInputs(context: Context) : IncidentContextInputs {

    private val app = context.applicationContext

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(app, SurRealApplicationEntryPoint::class.java)
    }

    override fun gnssSource(): String? = entryPoint.sourceSettings().activeProvider.value.name

    override suspend fun receiverProfile(): String? =
        withTimeoutOrNull(1_000L) { entryPoint.settingsRepository().externalReceiverProfile.first() }?.label

    override fun receiverLink(): String? = GnssFeedLogger.instance?.link?.lastLinkState

    override fun corrections(): String? = GnssFeedLogger.instance?.link?.correctionsSummary

    override fun fixQuality(): FixQuality? =
        GnssFeedLogger.instance?.feed?.latest(GnssFeedLogger.now())?.let {
            FixQuality(it.rtk.name, it.ageMs, it.hAccM, it.vAccM, it.correctionAgeS)
        }

    override fun arState(): ArState? {
        val ar = ArSessionDiagnostics
        // Outside AR the last session's tracking states would read as current; leave them unknown.
        if (!ar.hasSession() || ar.closedAtMs != 0L) return ArState(sessionActive = false, null, null, null, null)
        val models = ar.models()
        return ArState(
            sessionActive = true,
            cameraTracking = ar.lastCameraTrackingState,
            earthTracking = ar.lastEarthTrackingState,
            modelsInScene = models.count { it.status == ArSessionDiagnostics.ModelStatus.IN_SCENE },
            modelsLoading = models.count {
                it.status == ArSessionDiagnostics.ModelStatus.QUEUED || it.status == ArSessionDiagnostics.ModelStatus.LOADING
            },
        )
    }

    override fun freeStorageBytes(): Long? = app.filesDir?.usableSpace

    override fun permissions(): PermissionState = PermissionState(
        camera = granted(Manifest.permission.CAMERA),
        fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        notifications = NotificationManagerCompat.from(app).areNotificationsEnabled(),
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
}
