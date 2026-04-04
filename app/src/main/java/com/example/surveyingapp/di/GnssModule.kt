package com.example.surveyingapp.di

import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.nmea.parse.*
import com.example.surveyingapp.gnss.service.GnssController
import com.example.surveyingapp.gnss.settings.SourceSettings
import com.example.surveyingapp.gnss.source.GnssSource
import com.example.surveyingapp.gnss.source.NmeaTcpSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.emptyFlow

@Module
@InstallIn(SingletonComponent::class)
object GnssModule {

    /**
     * Provides a singleton FixAccumulator for accumulating NMEA sentence data.
     */
    @Provides
    @Singleton
    fun provideFixAccumulator(): FixAccumulator = FixAccumulator()

    /**
     * Provides a singleton DiagnosticsService for tracking NMEA processing metrics.
     */
    @Provides
    @Singleton
    fun provideDiagnosticsService(): DiagnosticsService = DiagnosticsService()

    /**
     * Provides NmeaRegistry with all available NMEA parsers.
     * Maps sentence tags to their corresponding parsers.
     */
    @Provides
    @Singleton
    fun provideNmeaRegistry(): NmeaRegistry {
        val parsers = mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser()
        )
        return NmeaRegistry(parsers, verifyChecksum = true)
    }

    /**
     * Factory method to provide GnssSource based on SourceSettings.
     * Creates appropriate source implementation based on the active provider choice.
     */
    @Provides
    @Singleton
    fun provideGnssSource(sourceSettings: SourceSettings): GnssSource {
        return GnssSourceFactory.create(sourceSettings)
    }

    /**
     * Provides GnssController with all required dependencies.
     * This is where the GNSS data stream processing pipeline runs.
     */
    @Provides
    @Singleton
    fun provideGnssController(
        gnssSource: GnssSource,
        nmeaRegistry: NmeaRegistry,
        fixAccumulator: FixAccumulator
    ): GnssController = GnssController(
        scope = kotlinx.coroutines.GlobalScope,
        source = gnssSource,
        registry = nmeaRegistry,
        accumulator = fixAccumulator
    )
}

/**
 * Factory object for creating GnssSource instances based on SourceSettings.
 */


object GnssSourceFactory {

    fun create(sourceSettings: SourceSettings): GnssSource {
        return when (sourceSettings.activeProvider.value) {
            SourceSettings.ProviderChoice.INTERNAL -> {
                object : GnssSource {
                    override val name: String = "Internal GPS"
                    override fun lines() = emptyFlow<String>()
                }
            }

            SourceSettings.ProviderChoice.RS2_EXTERNAL -> {
                val conn  = sourceSettings.resolveActiveConnection()
                val prof  = sourceSettings.getActiveProfile()
                val host  = conn?.host ?: "192.168.42.1"
                val port  = conn?.port ?: 9000
                val label = prof?.name?.let { "RS2+ External ($it)" } ?: "RS2+ External (default)"
                NmeaTcpSource(host = host, port = port, name = label)
            }
        }
    }
}
