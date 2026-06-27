package app.surrealar.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.FileOutputStream
import java.io.BufferedWriter

/**
 * Rolling file logger with compression for NMEA data.
 * Maintains rolling logs with specified max file size and automatically
 * compresses old files to save space.
 */
class LogZip(
    private val context: Context,
    private val logName: String = "nmea",
    private val maxFileSizeMB: Int = 5,
    private val maxRolledFiles: Int = 10
) {
    companion object {
        private const val TAG = "LogZip"
    }

    private val logDir = File(context.getExternalFilesDir("logs"), logName).apply { mkdirs() }
    private val mutex = Mutex()
    private var currentWriter: BufferedWriter? = null
    private var currentFile: File? = null
    private var currentFileSizeBytes = 0L
    private val maxFileSizeBytes = maxFileSizeMB * 1024L * 1024L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /**
     * Write a line to the current log file, rolling over if size limit exceeded.
     */
    suspend fun writeLine(line: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ensureCurrentFile()

                    val lineWithNewline = line + "\n"
                    val lineBytes = lineWithNewline.toByteArray(Charsets.UTF_8)

                    // Check if we need to roll over
                    if (currentFileSizeBytes + lineBytes.size > maxFileSizeBytes) {
                        rollOverFile()
                        ensureCurrentFile()
                    }

                    currentWriter?.write(lineWithNewline)
                    currentWriter?.flush()
                    currentFileSizeBytes += lineBytes.size

                } catch (e: IOException) {
                    Log.e(TAG, "Error writing to log file", e)
                }
            }
        }
    }

    /**
     * Close current log file and clean up resources.
     */
    suspend fun close() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    currentWriter?.close()
                    currentWriter = null
                    currentFile = null
                    currentFileSizeBytes = 0L
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing log file", e)
                }
            }
        }
    }

    /**
     * Get list of available log files (both current and archived).
     */
    suspend fun getLogFiles(): List<File> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                logDir.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
            }
        }
    }

    /**
     * Export all logs to a single ZIP file for sharing/analysis.
     */
    suspend fun exportLogsToZip(): File? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val exportDir = File(context.getExternalFilesDir("exports"), "logs")
                    exportDir.mkdirs()

                    val timestamp = dateFormat.format(Date())
                    val zipFile = File(exportDir, "${logName}_export_$timestamp.zip")

                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        logDir.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                val entry = ZipEntry(file.name)
                                zos.putNextEntry(entry)
                                file.inputStream().use { input ->
                                    input.copyTo(zos)
                                }
                                zos.closeEntry()
                            }
                        }
                    }

                    Log.d(TAG, "Exported logs to: ${zipFile.absolutePath}")
                    zipFile
                } catch (e: Exception) {
                    Log.e(TAG, "Error exporting logs to ZIP", e)
                    null
                }
            }
        }
    }

    /**
     * Create a temporary zip file containing the most recent NMEA chunk and app configuration
     * for quick sharing and field issue reproduction.
     */
    suspend fun zipRecent(): File? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    // Create temp directory for the quick share zip
                    val tempDir = File(context.cacheDir, "temp_logs")
                    tempDir.mkdirs()

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val zipFile = File(tempDir, "nmea_recent_$timestamp.zip")

                    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                        // Add the most recent NMEA log file (current or latest)
                        val recentNmeaFile = getMostRecentLogFile()
                        if (recentNmeaFile != null) {
                            val nmeaEntry = ZipEntry("recent_nmea.log")
                            zos.putNextEntry(nmeaEntry)

                            // Get last chunk of data (e.g., last 1000 lines or 500KB)
                            val recentLines = getRecentLogLines(recentNmeaFile, maxLines = 1000)
                            zos.write(recentLines.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                        }

                        // Add app configuration
                        val configEntry = ZipEntry("app_config.txt")
                        zos.putNextEntry(configEntry)
                        val appConfig = collectAppConfiguration()
                        zos.write(appConfig.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()

                        // Add device info if available
                        val deviceInfoEntry = ZipEntry("device_info.txt")
                        zos.putNextEntry(deviceInfoEntry)
                        val deviceInfo = collectDeviceInfo()
                        zos.write(deviceInfo.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }

                    Log.d(TAG, "Created recent logs zip: ${zipFile.absolutePath}")
                    zipFile
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating recent logs zip", e)
                    null
                }
            }
        }
    }

    /**
     * Get the most recent log file (current file or latest completed file)
     */
    private fun getMostRecentLogFile(): File? {
        // First try current file if it exists and has content
        currentFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                return file
            }
        }

        // Otherwise get the most recently modified file in log directory
        return logDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Get recent lines from a log file (tail functionality)
     */
    private fun getRecentLogLines(file: File, maxLines: Int = 1000): String {
        return try {
            val allLines = file.readLines()
            val recentLines = if (allLines.size > maxLines) {
                allLines.takeLast(maxLines)
            } else {
                allLines
            }
            recentLines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading recent log lines from ${file.name}", e)
            "Error reading log file: ${e.message}\n"
        }
    }

    /**
     * Collect current app configuration for debugging
     */
    private fun collectAppConfiguration(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            appendLine("=== App Configuration Report ===")
            appendLine("Generated: $timestamp")
            appendLine()

            // App version and build info
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("App Version: ${packageInfo.versionName}")
                appendLine("Version Code: ${packageInfo.longVersionCode}")
                appendLine("Package: ${packageInfo.packageName}")
            } catch (e: Exception) {
                appendLine("App Version: Unable to retrieve (${e.message})")
            }
            appendLine()

            // Device information
            appendLine("=== Device Information ===")
            appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
            appendLine("Model: ${android.os.Build.MODEL}")
            appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
            appendLine("API Level: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Hardware: ${android.os.Build.HARDWARE}")
            appendLine()

            // NMEA logging configuration
            appendLine("=== NMEA Logging Configuration ===")
            appendLine("Log Name: $logName")
            appendLine("Max File Size: ${maxFileSizeMB}MB")
            appendLine("Max Rolled Files: $maxRolledFiles")
            appendLine("Log Directory: ${logDir.absolutePath}")
            appendLine("Current File: ${currentFile?.name ?: "None"}")
            appendLine("Current File Size: ${currentFileSizeBytes / 1024}KB")
            appendLine()

            // Log directory status
            appendLine("=== Log Directory Status ===")
            val logFiles = logDir.listFiles() ?: emptyArray()
            appendLine("Total Files: ${logFiles.size}")

            logFiles.sortedByDescending { it.lastModified() }.take(10).forEach { file ->
                val sizeKB = file.length() / 1024
                val modified = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
                appendLine("  ${file.name} - ${sizeKB}KB - $modified")
            }

            if (logFiles.size > 10) {
                appendLine("  ... and ${logFiles.size - 10} more files")
            }
            appendLine()

            // Memory and storage info
            appendLine("=== System Resources ===")
            val runtime = Runtime.getRuntime()
            appendLine("Max Memory: ${runtime.maxMemory() / 1024 / 1024}MB")
            appendLine("Total Memory: ${runtime.totalMemory() / 1024 / 1024}MB")
            appendLine("Free Memory: ${runtime.freeMemory() / 1024 / 1024}MB")

            // Available storage
            try {
                val availableBytes = logDir.freeSpace
                appendLine("Available Storage: ${availableBytes / 1024 / 1024}MB")
            } catch (e: Exception) {
                appendLine("Available Storage: Unable to determine")
            }
        }
    }

    /**
     * Collect device and connection information
     */
    private fun collectDeviceInfo(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            appendLine("=== Device Connection Information ===")
            appendLine("Generated: $timestamp")
            appendLine()

            // Network information
            appendLine("=== Network Status ===")
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNetwork = connectivityManager?.activeNetwork
                val networkInfo = connectivityManager?.getNetworkInfo(activeNetwork)

                appendLine("Network Available: ${activeNetwork != null}")
                appendLine("Network Type: ${networkInfo?.typeName ?: "Unknown"}")
                appendLine("Connected: ${networkInfo?.isConnected ?: false}")

                // WiFi information
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val wifiInfo = wifiManager?.connectionInfo
                if (wifiInfo != null) {
                    appendLine("WiFi SSID: ${wifiInfo.ssid}")
                    appendLine("WiFi BSSID: ${wifiInfo.bssid}")
                    appendLine("WiFi Signal: ${wifiInfo.rssi} dBm")
                }
            } catch (e: Exception) {
                appendLine("Network Status: Unable to retrieve (${e.message})")
            }
            appendLine()

            // Location services
            appendLine("=== Location Services ===")
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                val gpsEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ?: false
                val networkEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ?: false

                appendLine("GPS Enabled: $gpsEnabled")
                appendLine("Network Location Enabled: $networkEnabled")

                val lastKnownGps = try {
                    locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                } catch (e: SecurityException) {
                    null
                }

                if (lastKnownGps != null) {
                    appendLine("Last GPS Fix: ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(lastKnownGps.time))}")
                    appendLine("GPS Accuracy: ${lastKnownGps.accuracy}m")
                } else {
                    appendLine("Last GPS Fix: None available")
                }
            } catch (e: Exception) {
                appendLine("Location Services: Unable to retrieve (${e.message})")
            }
            appendLine()

            // Bluetooth information
            appendLine("=== Bluetooth Status ===")
            try {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val bluetoothAdapter = bluetoothManager?.adapter

                appendLine("Bluetooth Available: ${bluetoothAdapter != null}")
                appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled ?: false}")

                if (bluetoothAdapter?.isEnabled == true) {
                    try {
                        val bondedDevices = bluetoothAdapter.bondedDevices
                        appendLine("Paired Devices: ${bondedDevices.size}")
                        bondedDevices.take(5).forEach { device ->
                            appendLine("  ${device.name} (${device.address})")
                        }
                    } catch (e: SecurityException) {
                        appendLine("Paired Devices: Permission required")
                    }
                }
            } catch (e: Exception) {
                appendLine("Bluetooth Status: Unable to retrieve (${e.message})")
            }
            appendLine()

            appendLine("=== Log Generation Complete ===")
        }
    }

    private fun ensureCurrentFile() {
        if (currentWriter == null || currentFile == null) {
            val timestamp = dateFormat.format(Date())
            currentFile = File(logDir, "${logName}_$timestamp.log")
            currentWriter = BufferedWriter(FileWriter(currentFile, true))
            currentFileSizeBytes = currentFile?.length() ?: 0L

            Log.d(TAG, "Opened log file: ${currentFile?.absolutePath}")
        }
    }

    private suspend fun rollOverFile() {
        try {
            // Close current file
            currentWriter?.close()

            // Compress the rolled file
            currentFile?.let { file ->
                compressFile(file)
            }

            // Reset for new file
            currentWriter = null
            currentFile = null
            currentFileSizeBytes = 0L

            // Cleanup old files
            cleanup()

        } catch (e: IOException) {
            Log.e(TAG, "Error during file rollover", e)
        }
    }

    private fun compressFile(file: File) {
        try {
            val compressedFile = File(file.parent, file.nameWithoutExtension + ".zip")

            ZipOutputStream(FileOutputStream(compressedFile)).use { zos ->
                val entry = ZipEntry(file.name)
                zos.putNextEntry(entry)
                file.inputStream().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }

            // Delete original file after successful compression
            if (compressedFile.exists() && file.delete()) {
                Log.d(TAG, "Compressed and deleted: ${file.name} -> ${compressedFile.name}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error compressing file: ${file.name}", e)
        }
    }

    /**
     * Get the total size of all log files in megabytes.
     */
    suspend fun getTotalSizeMB(): Double {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val files = logDir.listFiles() ?: return@withLock 0.0
                    val totalBytes = files.sumOf { file ->
                        if (file.isFile) file.length() else 0L
                    }
                    totalBytes.toDouble() / (1024.0 * 1024.0)
                } catch (e: Exception) {
                    Log.e(TAG, "Error calculating total log size", e)
                    0.0
                }
            }
        }
    }

    /**
     * Clean up old log files beyond the retention limit.
     */
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@withLock

                    // Keep current file + maxRolledFiles compressed files
                    val filesToDelete = files.drop(maxRolledFiles + 1)

                    filesToDelete.forEach { file ->
                        if (file.delete()) {
                            Log.d(TAG, "Deleted old log file: ${file.name}")
                        } else {
                            Log.w(TAG, "Failed to delete old log file: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during cleanup", e)
                }
            }
        }
    }
}
