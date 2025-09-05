package com.example.surveyingapp.util

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Discover Emlid Reach RS2/RS2+ via BLE by probing for Nordic UART Service (NUS).
 * We DO NOT attempt to read IP over GATT (not provided by Reach). After BLE, use LAN discovery.
 */

data class ReachBleDevice(
    val bluetoothDevice: BluetoothDevice,
    val deviceName: String?,
    val rssi: Int,
    val hasNus: Boolean = false,
    val manufacturer: String? = null,
    val model: String? = null,
    val serial: String? = null
)

object ReachBleHelper {
    private const val TAG = "ReachBleHelper"
    private const val BLE_SCAN_TIMEOUT_MS = 10_000L
    private const val GATT_CONNECT_TIMEOUT_MS = 5_000L
    private const val GATT_DISCOVER_TIMEOUT_MS = 4_000L
    private const val GATT_OP_TIMEOUT_MS = 3_000L

    // Nordic UART Service (used by Emlid Reach)
    private val NUS_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_RX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone -> device (WRITE/WRITE_NO_RESPONSE)
    private val NUS_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // device -> phone (NOTIFY)

    // Device Information Service (optional, to confirm it’s a GNSS receiver / vendor strings)
    private val DIS_SERVICE = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    private val DIS_MODEL = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")
    private val DIS_SERIAL = UUID.fromString("00002A25-0000-1000-8000-00805F9B34FB")
    private val DIS_MANUF = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")

    /**
     * Scan broadly, then for strong RSSI hits do a quick GATT probe to see if NUS is present.
     * Emits ReachBleDevice with hasNus=true when we positively identify it.
     */
    @SuppressLint("MissingPermission")
    fun discoverReachDevicesBle(context: Context): Flow<ReachBleDevice> = callbackFlow {
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btMgr.adapter ?: run { close(); return@callbackFlow }
        if (!adapter.isEnabled) { close(); return@callbackFlow }

        val scanner = adapter.bluetoothLeScanner ?: run { close(); return@callbackFlow }
        val seen = ConcurrentHashMap<String, Int>() // address -> last RSSI
        val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val scanCb = object : ScanCallback() {
            override fun onScanResult(cbType: Int, res: ScanResult) {
                val dev = res.device
                val rssi = res.rssi
                val nameFromAdv = res.scanRecord?.deviceName
                val nameFromDevice = try { dev.name } catch (_: SecurityException) { null }
                val displayName = nameFromAdv ?: nameFromDevice

                Log.d(TAG, "BLE device: name=$displayName addr=${dev.address} RSSI=$rssi")

                // Heuristics: only probe decent signals and avoid re-probing too often
                val last = seen.put(dev.address, rssi)
                if (last != null && abs(last - rssi) < 3) return

                // Quick pre-filter: many devices won’t advertise UUIDs/names. We still probe top candidates.
                val likely =
                    (displayName?.contains("reach", true) == true) ||
                            (displayName?.contains("rs2", true) == true) ||
                            (displayName?.contains("emlid", true) == true) ||
                            rssi >= -70 // good proximity → cheap to probe

                if (!likely) return

                // Launch a one-shot probe (cancel duplicates by address)
                ioScope.launch {
                    runCatching {
                        probeGattForReach(context, dev)
                    }.onSuccess { info ->
                        if (info != null) trySend(info)
                    }.onFailure { e ->
                        Log.d(TAG, "Probe failed for ${dev.address}: ${e.message}")
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                close()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        try {
            Log.d(TAG, "Starting BLE scan (broad, no filters)…")
            scanner.startScan(emptyList(), settings, scanCb)

            // Auto-stop after timeout
            ioScope.launch {
                delay(BLE_SCAN_TIMEOUT_MS)
                safeStop(scanner, scanCb)
                Log.d(TAG, "BLE scan timeout reached")
                // Flow stays open; your caller can cancel/close when done.
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start scan", t); close()
        }

        awaitClose {
            ioScope.cancel()
            safeStop(scanner, scanCb)
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    private suspend fun probeGattForReach(ctx: Context, dev: BluetoothDevice): ReachBleDevice? =
        withContext(Dispatchers.IO) {
            var gatt: BluetoothGatt? = null
            try {
                // 1) Connect
                gatt = connectGattSuspending(ctx, dev, GATT_CONNECT_TIMEOUT_MS) ?: return@withContext null

                // 2) Discover services
                val ok = discoverServicesSuspending(gatt, GATT_DISCOVER_TIMEOUT_MS)
                if (!ok) return@withContext null

                // 3) Identify NUS
                val hasNus = gatt.services.any { it.uuid == NUS_SERVICE }
                if (!hasNus) {
                    Log.d(TAG, "No NUS on ${dev.address}")
                    return@withContext ReachBleDevice(dev, dev.name, rssi = 0, hasNus = false)
                }

                // 4) (Optional) Read DIS
                var manuf: String? = null
                var model: String? = null
                var serial: String? = null

                gatt.getService(DIS_SERVICE)?.let { dis ->
                    manuf = dis.getCharacteristic(DIS_MANUF)?.let { readStringChar(gatt, it) }
                    model = dis.getCharacteristic(DIS_MODEL)?.let { readStringChar(gatt, it) }
                    serial = dis.getCharacteristic(DIS_SERIAL)?.let { readStringChar(gatt, it) }
                }

                val name = try { dev.name } catch (_: SecurityException) { null }
                ReachBleDevice(
                    bluetoothDevice = dev,
                    deviceName = name,
                    rssi = 0, // unknown at probe time; fill from scan stream if you want
                    hasNus = true,
                    manufacturer = manuf,
                    model = model,
                    serial = serial
                )
            } finally {
                try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
            }
        }

    // -----------------------------
    // ---- Suspend GATT helpers ----
    // -----------------------------

    @SuppressLint("MissingPermission")
    private suspend fun connectGattSuspending(
        ctx: Context,
        dev: BluetoothDevice,
        timeoutMs: Long
    ): BluetoothGatt? = suspendCancellableCoroutine { cont ->
        var resolved = false
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && !resolved) {
                    resolved = true; cont.resume(g) {}
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !resolved) {
                    resolved = true; cont.resume(null) {}
                }
            }
        }
        val gatt = try {
            // Use TRANSPORT_LE; on older APIs this overload may fallback automatically
            dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            cont.resume(null) {}; return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { try { gatt.close() } catch (_: Throwable) {} }
        // Timeout
        GlobalScope.launch {
            delay(timeoutMs)
            if (!resolved) {
                resolved = true
                cont.resume(null) {}
                try { gatt.disconnect(); gatt.close() } catch (_: Throwable) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverServicesSuspending(
        gatt: BluetoothGatt,
        timeoutMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        var done = false
        val cb = object : BluetoothGattCallback() {
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (!done) { done = true; cont.resume(status == BluetoothGatt.GATT_SUCCESS) {} }
            }
        }
        // Swap in a temporary callback to capture discovery result
        // (Most SDKs require one callback; if yours does, merge callbacks instead.)
        gatt.setCharacteristicNotificationWorkaround(cb)

        if (!gatt.discoverServices()) {
            cont.resume(false) {}
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { /* no-op */ }
        GlobalScope.launch {
            delay(timeoutMs)
            if (!done) { done = true; cont.resume(false) {} }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readStringChar(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic
    ): String? = suspendCancellableCoroutine { cont ->
        var finished = false
        val cb = object : BluetoothGattCallback() {
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (c.uuid == ch.uuid && !finished) {
                    finished = true
                    val v = if (status == BluetoothGatt.GATT_SUCCESS) c.value else null
                    cont.resume(v?.toString(StandardCharsets.UTF_8)?.trim()) {}
                }
            }
        }
        gatt.setCharacteristicNotificationWorkaround(cb)
        if (!gatt.readCharacteristic(ch)) {
            cont.resume(null) {}; return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { /* no-op */ }
        GlobalScope.launch {
            delay(GATT_OP_TIMEOUT_MS)
            if (!finished) { finished = true; cont.resume(null) {} }
        }
    }

    // Small helper so we can “install” a temporary callback for a single op.
    // If your app already has a single central BluetoothGattCallback, merge
    // these branches there instead of swapping callbacks like this.
    private fun BluetoothGatt.setCharacteristicNotificationWorkaround(cb: BluetoothGattCallback) {
        try {
            val f = BluetoothGatt::class.java.getDeclaredField("mCallback")
            f.isAccessible = true
            f.set(this, cb)
        } catch (_: Throwable) {
            // If the platform forbids this, migrate to a single shared callback pattern.
        }
    }

    private fun safeStop(scanner: BluetoothLeScanner, cb: ScanCallback) {
        try { scanner.stopScan(cb) } catch (_: Throwable) {}
    }
}
