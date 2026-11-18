package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.model.DiscoveredHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedDeque

class BleClientSession(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope,
    private val onHostDiscovered: (DiscoveredHost) -> Unit,
    private val onScanStateChanged: (Boolean) -> Unit,
    private val onScanError: (String) -> Unit,
    private val onSessionReady: () -> Unit,
    private val onPacketReceived: (type: Byte, payload: ByteArray) -> Unit,
    private val onDisconnected: (reason: String?) -> Unit
) {
    private val TAG = "BleClientSession"

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var charControlRx: BluetoothGattCharacteristic? = null
    private var charControlTx: BluetoothGattCharacteristic? = null
    private var charAudioUp: BluetoothGattCharacteristic? = null
    private var charAudioDown: BluetoothGattCharacteristic? = null

    private val controlAccumulator = IntercomProtocol.PacketAccumulator()
    private val audioAccumulator = IntercomProtocol.PacketAccumulator()

    private val controlWriteQueue = ConcurrentLinkedDeque<ByteArray>()
    private val audioWriteQueue = ConcurrentLinkedDeque<ByteArray>()
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var mtu: Int = 23
    @Volatile private var isConnected = false
    @Volatile private var gattOperationSignal: CompletableDeferred<Boolean>? = null
    private var writerJob: Job? = null
    private var mtuFallbackJob: Job? = null

    // ---------------- BLE APP-ONLY ROOM SCANNING ----------------

    @SuppressLint("MissingPermission")
    fun startScan(timeoutMs: Long = 20_000L): Boolean {
        stopScan()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            return false
        }

        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            onScanError("Bluetooth LE scanner unavailable on this device.")
            return false
        }
        scanner = leScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(IntercomProtocol.ORBIT_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { processScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with errorCode: $errorCode")
                onScanStateChanged(false)
                onScanError("Bluetooth scan failed (code $errorCode). Ensure Location & Bluetooth are enabled.")
            }
        }

        scanCallback = callback
        return try {
            leScanner.startScan(listOf(filter), settings, callback)
            onScanStateChanged(true)
            scanTimeoutJob = scope.launch {
                delay(timeoutMs)
                stopScan()
            }
            Log.d(TAG, "Started app-filtered BLE room scan")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan", e)
            onScanStateChanged(false)
            onScanError("Scan failed: ${e.localizedMessage}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return

        // Extra verification: confirm Orbit Intercom Service UUID is present
        val hasOrbitUuid = record.serviceUuids?.any { it.uuid == IntercomProtocol.ORBIT_SERVICE_UUID } == true
        if (!hasOrbitUuid) return

        val mfgData = record.getManufacturerSpecificData(IntercomProtocol.MANUFACTURER_ID)
        val decoded = IntercomProtocol.decodeAdvertiseRoomPayload(mfgData)

        val roomName = decoded?.first ?: "Orbit Channel"
        val hostCallsign = decoded?.second ?: (result.device.name ?: "Host (${result.device.address.takeLast(5)})")

        val host = DiscoveredHost(
            deviceName = hostCallsign,
            deviceAddress = result.device.address,
            roomName = roomName,
            rssi = result.rssi,
            isBle = true,
            lastSeenMs = System.currentTimeMillis()
        )
        onHostDiscovered(host)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            scanCallback?.let { scanner?.stopScan(it) }
        } catch (_: Exception) {}
        scanCallback = null
        onScanStateChanged(false)
    }

    // ---------------- BLE GATT CLIENT SESSION ----------------

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String): Boolean {
        disconnect()
        val adapter = bluetoothAdapter ?: return false
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid device address: $deviceAddress", e)
            return false
        }

        stopScan()
        controlAccumulator.clear()
        audioAccumulator.clear()
        controlWriteQueue.clear()
        audioWriteQueue.clear()
        mtu = 23

        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            onDisconnected("Could not initiate Bluetooth connection.")
            return false
        }
        bluetoothGatt = gatt
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        isConnected = false
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
        writerJob?.cancel()
        writerJob = null
        gattOperationSignal?.complete(false)
        gattOperationSignal = null

        controlWriteQueue.clear()
        audioWriteQueue.clear()

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null

        charControlRx = null
        charControlTx = null
        charAudioUp = null
        charAudioDown = null
    }

    fun sendControlPacket(type: Byte, payload: ByteArray) {
        if (!isConnected) return
        val framed = IntercomProtocol.encodePacket(type, payload)
        controlWriteQueue.addLast(framed)
        wakeChannel.trySend(Unit)
    }

    fun sendAudioPacket(payload: ByteArray) {
        if (!isConnected) return
        val framed = IntercomProtocol.encodePacket(IntercomProtocol.TYPE_AUDIO_FRAME, payload)
        while (audioWriteQueue.size >= 2) {
            audioWriteQueue.pollFirst()
        }
        audioWriteQueue.addLast(framed)
        wakeChannel.trySend(Unit)
    }

    private fun startWriterLoop() {
        writerJob?.cancel()
        writerJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                var sentSomething = false

                val controlData = controlWriteQueue.pollFirst()
                if (controlData != null) {
                    val rx = charControlRx
                    if (rx != null) {
                        writeChunkedCharacteristic(rx, controlData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    }
                    sentSomething = true
                }

                if (!sentSomething) {
                    val audioData = audioWriteQueue.pollFirst()
                    if (audioData != null) {
                        val audioChar = charAudioUp
                        if (audioChar != null) {
                            writeChunkedCharacteristic(audioChar, audioData, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        }
                        sentSomething = true
                    }
                }

                if (!sentSomething) {
                    wakeChannel.receive()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeChunkedCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        fullData: ByteArray,
        writeType: Int
    ) {
        val gatt = bluetoothGatt ?: return
        val maxChunk = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < fullData.size && isConnected) {
            val end = minOf(offset + maxChunk, fullData.size)
            val chunk = fullData.copyOfRange(offset, end)
            offset = end

            val signal = CompletableDeferred<Boolean>()
            gattOperationSignal = signal

            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, chunk, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = chunk
                    characteristic.writeType = writeType
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            } catch (e: Exception) {
                Log.w(TAG, "writeCharacteristic failed: ${e.message}")
                false
            }

            if (!ok) {
                gattOperationSignal = null
                break
            }

            withTimeoutOrNull(1000L) {
                signal.await()
            }
            gattOperationSignal = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            return false
        }
        val descriptor = characteristic.getDescriptor(IntercomProtocol.CCCD_UUID) ?: return true

        val signal = CompletableDeferred<Boolean>()
        gattOperationSignal = signal

        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeDescriptor failed: ${e.message}")
            false
        }

        if (!ok) {
            gattOperationSignal = null
            return false
        }

        val result = withTimeoutOrNull(1500L) { signal.await() } ?: false
        gattOperationSignal = null
        return result
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT Connected to host, requesting high priority & MTU")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                val mtuRequested = gatt.requestMtu(512)
                if (!mtuRequested) {
                    gatt.discoverServices()
                } else {
                    mtuFallbackJob = scope.launch {
                        delay(1500)
                        if (bluetoothGatt === gatt && charControlRx == null) {
                            gatt.discoverServices()
                        }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = isConnected
                Log.d(TAG, "GATT Disconnected (status=$status)")
                disconnect()
                onDisconnected(if (wasConnected) null else "Could not connect to room host (status $status).")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
            mtuFallbackJob?.cancel()
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            Log.d(TAG, "Negotiated BLE MTU: $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect()
                onDisconnected("Bluetooth service discovery failed.")
                return
            }

            val service = gatt.getService(IntercomProtocol.ORBIT_SERVICE_UUID)
            if (service == null) {
                disconnect()
                onDisconnected("Device is not hosting an Orbit Intercom room.")
                return
            }

            charControlRx = service.getCharacteristic(IntercomProtocol.CHAR_CONTROL_RX_UUID)
            charControlTx = service.getCharacteristic(IntercomProtocol.CHAR_CONTROL_TX_UUID)
            charAudioUp = service.getCharacteristic(IntercomProtocol.CHAR_AUDIO_UP_UUID)
            charAudioDown = service.getCharacteristic(IntercomProtocol.CHAR_AUDIO_DOWN_UUID)

            if (charControlRx == null || charControlTx == null || charAudioUp == null || charAudioDown == null) {
                disconnect()
                onDisconnected("Incompatible Orbit Intercom channel.")
                return
            }

            scope.launch(Dispatchers.IO) {
                val txEnabled = enableNotifications(charControlTx!!)
                val audioEnabled = enableNotifications(charAudioDown!!)
                if (txEnabled && audioEnabled) {
                    isConnected = true
                    startWriterLoop()
                    onSessionReady()
                } else {
                    disconnect()
                    onDisconnected("Failed to subscribe to Bluetooth voice stream.")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            gattOperationSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            gattOperationSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleIncomingNotification(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingNotification(characteristic.uuid, value)
        }
    }

    private fun handleIncomingNotification(uuid: java.util.UUID, value: ByteArray) {
        if (value.isEmpty()) return
        val packets = when (uuid) {
            IntercomProtocol.CHAR_CONTROL_TX_UUID -> controlAccumulator.append(value)
            IntercomProtocol.CHAR_AUDIO_DOWN_UUID -> audioAccumulator.append(value)
            else -> emptyList()
        }
        packets.forEach { (type, payload) ->
            onPacketReceived(type, payload)
        }
    }
}
