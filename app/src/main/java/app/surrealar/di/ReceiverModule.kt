package app.surrealar.di

import android.content.Context
import app.surrealar.domain.repository.ExternalReceiverSettingsRepository
import app.surrealar.gnss.receiver.ReceiverVendor
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.receiver.EmlidDiscovery
import app.surrealar.receiver.GenericNmeaDevice
import app.surrealar.receiver.ReceiverDeviceManager
import app.surrealar.receiver.emlid.EmlidReachDevice
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/** Receiver status devices: the vendor implementations behind [ReceiverDeviceManager]. */
@Module
@InstallIn(SingletonComponent::class)
object ReceiverModule {

    @Provides
    @Singleton
    fun provideReceiverDeviceManager(
        @ApplicationContext context: Context,
        sourceSettings: SourceSettings,
        settings: ExternalReceiverSettingsRepository,
    ): ReceiverDeviceManager {
        val discovery = EmlidDiscovery(context)
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
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
