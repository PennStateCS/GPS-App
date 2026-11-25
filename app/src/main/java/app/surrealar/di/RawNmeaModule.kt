package app.surrealar.di

import android.content.Context
import app.surrealar.BuildConfig
import app.surrealar.domain.repository.ExternalReceiverSettingsRepository
import app.surrealar.gnss.diagnostics.raw.RawNmeaFiles
import app.surrealar.gnss.diagnostics.raw.RawNmeaOrigin
import app.surrealar.gnss.diagnostics.raw.RawNmeaRecorder
import app.surrealar.gnss.diagnostics.raw.RawNmeaSourceMetadata
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.util.DiagnosticsLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Singleton

/**
 * Settings → Developer Tools → Raw NMEA Recording. The recorder is app-scoped (one recording at a time,
 * finalized safely whatever the UI does); its stop triggers (source / link / profile) are bound in
 * GnssModule.provideFixSwitchboard, where the external adapter's link state is available without a cycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object RawNmeaModule {

    @Provides
    @Singleton
    fun provideRawNmeaRecorder(
        @ApplicationContext context: Context,
        appScope: CoroutineScope,
        settings: ExternalReceiverSettingsRepository,
        receiverDevices: ReceiverDeviceManager,
    ): RawNmeaRecorder {
        val profile = settings.externalReceiverProfile.stateIn(appScope, SharingStarted.Eagerly, ExternalReceiverProfile.DEFAULT)
        val output = settings.compensatedNmeaOutput.stateIn(appScope, SharingStarted.Eagerly, CompensatedOutputSetting.DEFAULT)
        return RawNmeaRecorder(
            scope = appScope,
            ioDispatcher = Dispatchers.IO,
            files = RawNmeaFiles { File(context.filesDir, RawNmeaFiles.DIR_NAME) },
            metadata = { origin ->
                when (origin) {
                    RawNmeaOrigin.INTERNAL -> RawNmeaSourceMetadata(fileLabel = "Internal", appVersion = BuildConfig.VERSION_NAME)
                    RawNmeaOrigin.EXTERNAL_TCP -> {
                        val p = profile.value
                        // Only what the receiver itself reported (null otherwise); never the host / IP.
                        val info = receiverDevices.deviceInfo.value
                        RawNmeaSourceMetadata(
                            profileKey = p.prefKey,
                            profileLabel = p.label,
                            fileLabel = p.shortLabel,
                            reportedModel = info?.model?.takeIf { it.isNotBlank() },
                            reportedFirmware = info?.firmware?.takeIf { it.isNotBlank() },
                            reportedSerial = info?.serial?.takeIf { it.isNotBlank() },
                            compensatedOutput = output.value.name,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    }
                }
            },
            monoClockMs = { android.os.SystemClock.elapsedRealtime() },
            log = { msg -> DiagnosticsLogger.i("GNSS", msg) },
        )
    }
}
