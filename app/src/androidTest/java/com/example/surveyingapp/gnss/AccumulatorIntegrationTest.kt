package com.example.surveyingapp.gnss

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.nmea.parse.*
import com.example.surveyingapp.gnss.nmea.sentence.GGA
import com.example.surveyingapp.gnss.nmea.sentence.GSA
import com.example.surveyingapp.gnss.nmea.sentence.GSV
import com.example.surveyingapp.gnss.nmea.sentence.RMC
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Integration test that processes a recorded NMEA log through NmeaRegistry + FixAccumulator
 * and validates the final FixSnapshot values.
 */
@RunWith(AndroidJUnit4::class)
class AccumulatorIntegrationTest {

    private lateinit var nmeaRegistry: NmeaRegistry
    private lateinit var fixAccumulator: FixAccumulator

    @Before
    fun setUp() {
        // Initialize NmeaRegistry with all available parsers
        nmeaRegistry = NmeaRegistry(mapOf(
            "GGA" to GgaParser(),
            "RMC" to RmcParser(),
            "GSA" to GsaParser(),
            "GSV" to GsvParser(),
            "ZDA" to ZdaParser()
        ))

        fixAccumulator = FixAccumulator()
    }

    @Test
    fun testNmeaLogProcessingThroughAccumulator() = runBlocking {
        // Read the sample NMEA file from assets
        val context = InstrumentationRegistry.getInstrumentation().context
        val assetManager = context.assets
        val inputStream = assetManager.open("sample.nmea")
        val reader = BufferedReader(InputStreamReader(inputStream))

        var lineCount = 0
        var processedSentences = 0

        // Process each line through the registry and accumulator
        reader.useLines { lines ->
            lines.forEach { line ->
                lineCount++
                val trimmedLine = line.trim()

                if (trimmedLine.isNotEmpty() && trimmedLine.startsWith('$')) {
                    // Parse the NMEA sentence using the registry
                    val sentence = nmeaRegistry.parse(trimmedLine)

                    if (sentence != null) {
                        // Feed the parsed sentence to the accumulator
                        fixAccumulator.accept(sentence)
                        processedSentences++
                    }
                }
            }
        }

        // Assert that we processed some lines
        assertTrue("Should have read some lines from NMEA file", lineCount > 0)
        assertTrue("Should have processed some valid NMEA sentences", processedSentences > 0)

        // Get the final accumulated fix snapshot
        val finalSnapshot = fixAccumulator.state.value

        // Assert the expected values based on the sample.nmea file content
        // The sample file contains GPS coordinates around 48°07.042'N, 11°31.005'E

        // Latitude assertions (should be around 48.117 degrees)
        assertNotNull("Latitude should be available", finalSnapshot.lat)
        finalSnapshot.lat?.let { lat ->
            assertTrue("Latitude should be positive (Northern hemisphere)", lat > 0)
            assertTrue("Latitude should be around 48 degrees", lat > 48.0 && lat < 49.0)
        }

        // Longitude assertions (should be around 11.517 degrees)
        assertNotNull("Longitude should be available", finalSnapshot.lon)
        finalSnapshot.lon?.let { lon ->
            assertTrue("Longitude should be positive (Eastern hemisphere)", lon > 0)
            assertTrue("Longitude should be around 11 degrees", lon > 11.0 && lon < 12.0)
        }

        // Altitude assertions (should be around 545.9 meters MSL from last GGA)
        assertNotNull("Altitude MSL should be available", finalSnapshot.altMsl)
        finalSnapshot.altMsl?.let { alt ->
            assertTrue("Altitude should be positive", alt > 0)
            assertTrue("Altitude should be around 545 meters", alt > 540.0 && alt < 550.0)
        }

        // Satellite count assertions (GSA shows satellites 04,05,09,12,24 = 5 used)
        assertNotNull("Satellites used count should be available", finalSnapshot.satsUsed)
        finalSnapshot.satsUsed?.let { sats ->
            assertTrue("Should have satellites in use", sats > 0)
            assertTrue("Should have reasonable number of satellites", sats <= 12)
        }

        // HDOP assertions (should be 1.3 from GSA sentences)
        assertNotNull("HDOP should be available", finalSnapshot.hdop)
        finalSnapshot.hdop?.let { hdop ->
            assertTrue("HDOP should be positive", hdop > 0)
            assertTrue("HDOP should be reasonable", hdop < 10.0)
        }

        // Speed assertions (should be around 22.4 knots = ~11.5 m/s from RMC)
        assertNotNull("Speed should be available", finalSnapshot.speedMps)
        finalSnapshot.speedMps?.let { speed ->
            assertTrue("Speed should be positive", speed > 0)
            assertTrue("Speed should be reasonable", speed < 50.0) // < 50 m/s
        }

        // Course assertions (should be 84.4 degrees from RMC)
        assertNotNull("Course should be available", finalSnapshot.courseDeg)
        finalSnapshot.courseDeg?.let { course ->
            assertTrue("Course should be within valid range", course >= 0 && course < 360)
        }

        // Geoid separation assertions (should be 46.9 meters from GGA)
        assertNotNull("Geoid separation should be available", finalSnapshot.geoidSeparation)
        finalSnapshot.geoidSeparation?.let { separation ->
            assertTrue("Geoid separation should be reasonable", separation > 0 && separation < 100)
        }

        // Ellipsoidal altitude should be calculated (MSL + geoid separation)
        assertNotNull("Ellipsoidal altitude should be calculated", finalSnapshot.altEllipsoidal)
        if (finalSnapshot.altMsl != null && finalSnapshot.geoidSeparation != null) {
            val expectedEllipsoidal = finalSnapshot.altMsl!! + finalSnapshot.geoidSeparation!!
            assertEquals(
                "Ellipsoidal altitude should equal MSL + geoid separation",
                expectedEllipsoidal,
                finalSnapshot.altEllipsoidal!!,
                0.1
            )
        }

        // Print final snapshot for debugging (optional)
        println("Final FixSnapshot:")
        println("  Lat: ${finalSnapshot.lat}")
        println("  Lon: ${finalSnapshot.lon}")
        println("  Alt MSL: ${finalSnapshot.altMsl}")
        println("  Alt Ellipsoidal: ${finalSnapshot.altEllipsoidal}")
        println("  Geoid Sep: ${finalSnapshot.geoidSeparation}")
        println("  Speed: ${finalSnapshot.speedMps}")
        println("  Course: ${finalSnapshot.courseDeg}")
        println("  Sats Used: ${finalSnapshot.satsUsed}")
        println("  HDOP: ${finalSnapshot.hdop}")
        println("  Processed $processedSentences sentences from $lineCount lines")
    }

    @Test
    fun testIndividualSentenceTypes() = runBlocking {
        // Test that each major sentence type is properly processed
        val context = InstrumentationRegistry.getInstrumentation().context
        val assetManager = context.assets
        val inputStream = assetManager.open("sample.nmea")
        val reader = BufferedReader(InputStreamReader(inputStream))

        var ggaCount = 0
        var rmcCount = 0
        var gsaCount = 0
        var gsvCount = 0

        reader.useLines { lines ->
            lines.forEach { line ->
                val trimmedLine = line.trim()

                if (trimmedLine.isNotEmpty() && trimmedLine.startsWith('$')) {
                    val sentence = nmeaRegistry.parse(trimmedLine)

                    when (sentence) {
                        is GGA -> {
                            ggaCount++
                            fixAccumulator.accept(sentence)
                        }
                        is RMC -> {
                            rmcCount++
                            fixAccumulator.accept(sentence)
                        }
                        is GSA -> {
                            gsaCount++
                            fixAccumulator.accept(sentence)
                        }
                        is GSV -> {
                            gsvCount++
                            fixAccumulator.accept(sentence)
                        }
                    }
                }
            }
        }

        // Assert that we found different sentence types
        assertTrue("Should have processed GGA sentences", ggaCount > 0)
        assertTrue("Should have processed RMC sentences", rmcCount > 0)
        assertTrue("Should have processed GSA sentences", gsaCount > 0)
        assertTrue("Should have processed GSV sentences", gsvCount > 0)

        println("Sentence counts: GGA=$ggaCount, RMC=$rmcCount, GSA=$gsaCount, GSV=$gsvCount")
    }
}
