package com.example.surveyingapp.gnss.replay

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
 * [NmeaLineSource] that replays NMEA sentences from a bundled asset file (demo/testing only).
 *
 * Useful for development and testing without a physical receiver. Each line is
 * emitted with a configurable delay so the output looks like a live feed. When
 * the file ends, playback loops back to the beginning automatically.
 *
 * If the asset file cannot be opened, the source emits a single invalid GGA
 * sentence and stops, rather than crashing the pipeline.
 */
class AssetNmeaReplaySource(
    private val context: Context,
    private val assetFileName: String = "sample.nmea",
    private val delayBetweenLines: Long = 1000L,
    override val name: String = "Replay ($assetFileName)"
) : NmeaLineSource {

    override fun lines(): Flow<String> = flow {
        var reader: BufferedReader? = null

        try {
            val inputStream = context.assets.open(assetFileName)
            reader = BufferedReader(InputStreamReader(inputStream))

            var lineCount = 0

            while (coroutineContext.isActive) {
                val line = reader?.readLine()

                if (line == null) {
                    // End of file — reopen and loop from the start
                    reader?.close()
                    val newInputStream = context.assets.open(assetFileName)
                    reader = BufferedReader(InputStreamReader(newInputStream))
                    lineCount = 0
                    continue
                }

                val cleanLine = line.trim()
                if (cleanLine.isNotEmpty()) {
                    emit(cleanLine)
                    lineCount++
                    delay(delayBetweenLines)
                }
            }
        } catch (e: Exception) {
            // Emit a placeholder invalid fix so the pipeline does not stall silently
            emit("\$GPGGA,000000,0000.000,N,00000.000,E,0,00,99.9,0.0,M,0.0,M,,*62")
        } finally {
            try {
                reader?.close()
            } catch (e: Exception) {
                // Ignore close errors during shutdown
            }
        }
    }.flowOn(Dispatchers.IO)
}
