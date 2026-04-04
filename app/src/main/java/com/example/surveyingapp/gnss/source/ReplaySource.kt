package com.example.surveyingapp.gnss.source

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/**
 * GNSS source implementation that replays NMEA data from a bundled asset file.
 * Emits lines with small delays to simulate a live GNSS feed for testing purposes.
 */
class ReplaySource(
    private val context: Context,
    private val assetFileName: String = "sample.nmea",
    private val delayBetweenLines: Long = 1000L, // 1 second between lines
    override val name: String = "Replay ($assetFileName)"
) : GnssSource {

    override fun lines(): Flow<String> = flow {
        var reader: BufferedReader? = null

        try {
            // Open the asset file
            val inputStream = context.assets.open(assetFileName)
            reader = BufferedReader(InputStreamReader(inputStream))

            var lineCount = 0

            // Read lines in a loop, restarting from beginning when reaching end
            while (coroutineContext.isActive) {
                val line = reader?.readLine()

                if (line == null) {
                    // End of file reached or reader is null - restart from beginning
                    reader?.close()
                    val newInputStream = context.assets.open(assetFileName)
                    reader = BufferedReader(InputStreamReader(newInputStream))
                    lineCount = 0
                    continue
                }

                // Emit clean NMEA line (trim any whitespace)
                val cleanLine = line.trim()
                if (cleanLine.isNotEmpty()) {
                    emit(cleanLine)
                    lineCount++

                    // Add delay to simulate live feed timing
                    delay(delayBetweenLines)
                }
            }
        } catch (e: Exception) {
            // If file doesn't exist or other IO error, emit a warning and stop
            emit("\$GPGGA,000000,0000.000,N,00000.000,E,0,00,99.9,0.0,M,0.0,M,,*62")
        } finally {
            // Clean up resources
            try {
                reader?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }.flowOn(Dispatchers.IO)
}
