package com.example.surveyingapp.di

import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import com.example.surveyingapp.gnss.nmea.parse.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GnssModule {

    /**
     * Provides a singleton [FixAccumulator] used by the NMEA replay feature
     * ([MainActivity]) and the Diagnostics screen.
     *
     * Note: the live GNSS path (internal GPS and TCP external) does NOT go through this
     * accumulator — it uses the [com.example.surveyingapp.gnss.bus.FixSwitchboard] pipeline
     * instead. The accumulator is deliberately kept separate so the replay feature can run
     * without interfering with live data.
     */
    @Provides
    @Singleton
    fun provideFixAccumulator(): FixAccumulator = FixAccumulator()

    /**
     * Provides a singleton [DiagnosticsService] for tracking NMEA processing metrics.
     */
    @Provides
    @Singleton
    fun provideDiagnosticsService(): DiagnosticsService = DiagnosticsService()

    /**
     * Provides the shared [NmeaRegistry] used by both the live GNSS pipeline
     * ([NmeaFuser] inside each [NmeaSource]) and the NMEA replay [GnssController].
     *
     * All sentence types supported by the app are registered here. To add a new sentence type:
     *  1. Create a data class implementing [NmeaSentence] in `gnss/nmea/sentence/`
     *  2. Create a [SentenceParser] in `gnss/nmea/parse/`
     *  3. Add it to this map
     *  4. Handle it in [NmeaFuser.handle] (live path) and [FixAccumulator.accept] (replay path)
     */
    @Provides
    @Singleton
    fun provideNmeaRegistry(): NmeaRegistry {
        val parsers = mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser(),
            "GST" to GstParser()   // accuracy error statistics
        )
        return NmeaRegistry(parsers, verifyChecksum = true)
    }
}
