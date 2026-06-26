package com.example.surveyingapp.settings

import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.settings.model.AppThemeMode
import com.example.surveyingapp.settings.model.ExternalReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {

    @Test
    fun `standalone defaults match documented values`() {
        assertEquals(LocationSourceType.INTERNAL, SettingsDefaults.locationSource)
        // TCP is the only implemented external transport, so it is the fresh-install default.
        assertEquals(ExternalConnectionType.TCP, SettingsDefaults.externalConnectionType)
        assertEquals(ExternalConnectionType.TCP, SettingsDefaults.externalReceiverSettings.connectionType)
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, SettingsDefaults.externalReceiverProfile)
        assertEquals(9000, SettingsDefaults.externalTcpPort)
        assertEquals(false, SettingsDefaults.mockLocationEnabled)
    }

    @Test
    fun `grouped capture defaults are stable`() {
        val c = SettingsDefaults.gnssCapture
        assertEquals(RtkStatus.FIX, c.requiredMinStatus)
        assertEquals(60, c.minDurationSec)
        assertEquals(120, c.maxDurationSec)
        assertEquals(150, c.minSamples)
        assertEquals(3, c.maxFixAgeSec)
        assertEquals(10, c.maxDiffAgeSec)
    }

    @Test
    fun `grouped AR and display defaults are stable`() {
        assertEquals("STORED", SettingsDefaults.arDisplay.altitudeMode)
        assertEquals(2.0f, SettingsDefaults.arDisplay.modelScale, 1e-6f)
        assertEquals(true, SettingsDefaults.arDisplay.showLabels)
        assertEquals("Point", SettingsDefaults.coordinateDisplay.defaultNamePrefix)
        assertEquals(true, SettingsDefaults.coordinateDisplay.autoIncrementNames)
        assertEquals(AppThemeMode.SYSTEM, SettingsDefaults.appearance.themeMode)
        assertEquals(true, SettingsDefaults.gnssReceiver.highAccuracy)
        assertEquals(false, SettingsDefaults.developer.developerToolsEnabled)
    }

    @Test
    fun `sanitizeTcpPort defaults on null and clamps out-of-range`() {
        assertEquals(9000, SettingsDefaults.sanitizeTcpPort(null))
        assertEquals(9000, SettingsDefaults.sanitizeTcpPort(0))
        assertEquals(9000, SettingsDefaults.sanitizeTcpPort(70000))
        assertEquals(9000, SettingsDefaults.sanitizeTcpPort(-1))
    }

    @Test
    fun `sanitizeTcpPort preserves a valid saved port`() {
        assertEquals(9001, SettingsDefaults.sanitizeTcpPort(9001))
        assertEquals(2947, SettingsDefaults.sanitizeTcpPort(2947))
        assertEquals(65535, SettingsDefaults.sanitizeTcpPort(65535))
    }

    @Test
    fun `RS4 and RS4 Pro profiles default to port 9001`() {
        assertEquals(9001, ExternalReceiverProfile.REACH_RS4.defaultPort)
        assertEquals(9001, ExternalReceiverProfile.REACH_RS4_PRO.defaultPort)
        assertEquals(9000, ExternalReceiverProfile.GENERIC_NMEA_TCP.defaultPort)
    }
}
