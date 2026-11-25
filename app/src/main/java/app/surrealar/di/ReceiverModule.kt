package app.surrealar.di

import android.content.Context
import app.surrealar.domain.repository.ExternalReceiverSettingsRepository
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.receiver.EmlidDiscovery
import app.surrealar.receiver.GenericNmeaDevice
import app.surrealar.receiver.ReceiverDeviceManager
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachDevice
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Singleton

/** Receiver status devices: the vendor implementations behind [ReceiverDeviceManager]. */
@Module
@InstallIn(SingletonComponent::class)
object ReceiverModule {

    @Provides
    @Singleton
    fun provideEmlidDiscovery(@ApplicationContext context: Context): EmlidDiscovery = EmlidDiscovery(context)

    @Provides
    @Singleton
    fun provideReceiverDeviceManager(
        discovery: EmlidDiscovery,
        sourceSettings: SourceSettings,
        settings: ExternalReceiverSettingsRepository,
    ): ReceiverDeviceManager {
        return ReceiverDeviceManager(
            externalSourceActive = sourceSettings.activeProvider.map { it == SourceSettings.ProviderChoice.EXTERNAL_TCP },
            profile = settings.externalReceiverProfile,
            host = settings.externalTcpHost,
            deviceFor = { vendor ->
                when (vendor) {
                    ReceiverVendor.EMLID   -> EmlidReachDevice()
                    ReceiverVendor.GENERIC -> GenericNmeaDevice
                }
            },
            discoveryFor = { profile -> if (profile.vendor == ReceiverVendor.EMLID) discovery else null },
            // Same kind of scope the former ReachDeviceRepository owned: IO, survives the UI.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineModule.LogUncaught),
        )
    }

    /**
     * Phase 5: receiver selection core (test before switch, identification, recent receivers). App-scoped: closing a
     * screen never cancels an attempt. Reuses the coordinator, the external adapter's link state and the device
     * manager's device info; adds no connection path of its own.
     */
    @Provides
    @Singleton
    fun provideReceiverConnectionController(
        appScope: CoroutineScope,
        settings: app.surrealar.domain.repository.SettingsRepository,
        coordinator: app.surrealar.gnss.source.GnssSourceCoordinator,
        sourceSettings: SourceSettings,
        externalAdapter: app.surrealar.gnss.external.ExternalAdapter,
        receiverDevices: ReceiverDeviceManager,
        captureTracker: app.surrealar.ui.capture.ActiveCaptureTracker,
        discovery: EmlidDiscovery,
    ): app.surrealar.receiver.selection.ReceiverConnectionController =
        app.surrealar.receiver.selection.ReceiverConnectionController(
            settings = settings,
            coordinator = coordinator,
            scope = appScope,
            activeProvider = sourceSettings.activeProvider,
            externalLink = externalAdapter.connectionState,
            deviceInfo = receiverDevices.deviceInfo,
            captureActive = captureTracker.active,
            discovery = discovery,
        )

    /**
     * Phase 4 WP C: receiver tilt status (RS4 / RS4 Pro ETC) for the active external source. Status only: fed
     * from the TCP stream's line hook (GnssModule), reset on source / profile change (and on connection changes,
     * bound in GnssModule), firmware from the device manager.
     */
    @Provides
    @Singleton
    fun provideReceiverTiltMonitor(
        appScope: CoroutineScope,
        sourceSettings: SourceSettings,
        settings: ExternalReceiverSettingsRepository,
        receiverDevices: ReceiverDeviceManager,
    ): app.surrealar.receiver.tilt.ReceiverTiltMonitor {
        val profile = settings.externalReceiverProfile.stateIn(
            appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, app.surrealar.settings.model.ExternalReceiverProfile.DEFAULT)
        val output = settings.compensatedNmeaOutput.stateIn(
            appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, app.surrealar.settings.model.CompensatedOutputSetting.DEFAULT)
        // Last known firmware per receiver host + profile: a failed 30 s device-info poll (firmware = null) must not
        // make pole-tip epochs "firmware unknown" (FirmwareMemory).
        val firmwareMemory = app.surrealar.receiver.tilt.FirmwareMemory()
        val firmware = kotlinx.coroutines.flow.combine(receiverDevices.deviceInfo, profile) { info, p ->
            firmwareMemory.update(info, p)
        }.stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
        return app.surrealar.receiver.tilt.ReceiverTiltMonitor(
            scope = appScope,
            profile = profile,
            compensatedOutput = output,
            firmware = firmware,
            resetSignals = kotlinx.coroutines.flow.combine(sourceSettings.activeProvider, profile) { p, prof -> p to prof },
            clockMs = { android.os.SystemClock.elapsedRealtime() },
        )
    }
}
