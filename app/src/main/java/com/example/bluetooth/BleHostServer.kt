package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class BleHostServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager?,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope,
    private val onPacketReceived: (deviceAddress: String, type: Byte, payload: ByteArray) -> Unit,
    private val onClientDisconnected: (deviceAddress: String) -> Unit,
    private val onHostError: (String) -> Unit
) {
    private val TAG = "BleHostServer"

    private data class OutboundNotification(
        val characteristicUuid: java.util.UUID,
        val data: ByteArray,
        val isAudio: Boolean
    )

    private inner class ClientConnection(val device: BluetoothDevice) {
        val address: String = device.address
        val controlAccumulator = IntercomProtocol.PacketAccumulator()
        val audioAccumulator = IntercomProtocol.PacketAccumulator()
        @Volatile var mtu: Int = 23
        @Volatile var notifySignal: CompletableDeferred<Boolean>? = null
        val controlQueue = ConcurrentLinkedDeque<ByteArray>()
        val audioQueue = ConcurrentLinkedDeque<ByteArray>()
        val wakeChannel = Channel<Unit>(Channel.CONFLATED)
        var senderJob: Job? = null
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private var charControlRx: BluetoothGattCharacteristic? = null
    private var charControlTx: BluetoothGattCharacteristic? = null
    private var charAudioUp: BluetoothGattCharacteristic? = null
    private var charAudioDown: BluetoothGattCharacteristic? = null

    private val clients = ConcurrentHashMap<String, ClientConnection>()
    @Volatile private var pendingRoomName: String = ""
    @Volatile private var pendingHostCallsign: String = ""
    @Volatile private var isRunning = false

    fun isAdvertisingSupported(): Boolean {
        return bluetoothAdapter?.bluetoothLeAdvertiser != null
    }

    @SuppressLint("MissingPermission")
    fun start(roomName: String, hostCallsign: String): Boolean {
        stop()
        if (bluetoothManager == null || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            return false
        }

        val leAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser is unavailable on this device")
            return false
        }

        advertiser = leAdvertiser
        pendingRoomName = roomName
        pendingHostCallsign = hostCallsign
        isRunning = true

        try {
            val server = bluetoothManager.openGattServer(context, gattServerCallback)
            if (server == null) {
                Log.e(TAG, "openGattServer returned null")
                isRunning = false
                return false
            }
            gattServer = server

            val service = BluetoothGattService(
                IntercomProtocol.ORBIT_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            val rx = BluetoothGattCharacteristic(
                IntercomProtocol.CHAR_CONTROL_RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val tx = BluetoothGattCharacteristic(
                IntercomProtocol.CHAR_CONTROL_TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        IntercomProtocol.CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            val audioUp = BluetoothGattCharacteristic(
                IntercomProtocol.CHAR_AUDIO_UP_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val audioDown = BluetoothGattCharacteristic(
                IntercomProtocol.CHAR_AUDIO_DOWN_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        IntercomProtocol.CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }

            charControlRx = rx
            charControlTx = tx
            charAudioUp = audioUp
            charAudioDown = audioDown

            service.addCharacteristic(rx)
            service.addCharacteristic(tx)
            service.addCharacteristic(audioUp)
            service.addCharacteristic(audioDown)

            val added = server.addService(service)
            if (!added) {
                Log.e(TAG, "addService returned false")
                stop()
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BleHostServer", e)
            stop()
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (!isRunning) return
        val leAdvertiser = advertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Primary packet: 128-bit Orbit Service UUID (21 bytes total with flags)
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(IntercomProtocol.ORBIT_SERVICE_UUID))
            .build()

        // Scan response: Room name + Host callsign in Manufacturer Data
        val roomPayload = IntercomProtocol.encodeAdvertiseRoomPayload(pendingRoomName, pendingHostCallsign)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(IntercomProtocol.MANUFACTURER_ID, roomPayload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "BLE Advertising active for room: $pendingRoomName")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE Advertising failed with code: $errorCode")
                onHostError("Bluetooth room advertising failed (error $errorCode)")
            }
        }
        advertiseCallback = callback
        try {
            leAdvertiser.startAdvertising(settings, advData, scanResponse, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE advertising", e)
            onHostError("Unable to advertise Bluetooth room: ${e.localizedMessage}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isRunning = false
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (_: Exception) {}
        advertiseCallback = null
        advertiser = null

        clients.values.forEach { conn ->
            conn.senderJob?.cancel()
            conn.notifySignal?.complete(false)
            try {
                gattServer?.cancelConnection(conn.device)
            } catch (_: Exception) {}
        }
        clients.clear()

        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (_: Exception) {}
        gattServer = null
    }

    fun sendControlPacket(deviceAddress: String, type: Byte, payload: ByteArray) {
        val conn = clients[deviceAddress] ?: return
        val framed = IntercomProtocol.encodePacket(type, payload)
        conn.controlQueue.addLast(framed)
        conn.wakeChannel.trySend(Unit)
    }

    fun sendAudioPacket(deviceAddress: String, payload: ByteArray) {
        val conn = clients[deviceAddress] ?: return
        val framed = IntercomProtocol.encodePacket(IntercomProtocol.TYPE_AUDIO_FRAME, payload)
        // Drop stale queued audio frames to keep latency low
        while (conn.audioQueue.size >= 2) {
            conn.audioQueue.pollFirst()
        }
        conn.audioQueue.addLast(framed)
        conn.wakeChannel.trySend(Unit)
    }

    @SuppressLint("MissingPermission")
    fun disconnectClient(deviceAddress: String) {
        val conn = clients.remove(deviceAddress) ?: return
        conn.senderJob?.cancel()
        conn.notifySignal?.complete(false)
        try {
            gattServer?.cancelConnection(conn.device)
        } catch (_: Exception) {}
    }

    private fun startSenderLoop(conn: ClientConnection) {
        conn.senderJob?.cancel()
        conn.senderJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                var sentSomething = false

                // Control packets have priority over audio
                val controlPacket = conn.controlQueue.pollFirst()
                if (controlPacket != null) {
                    val tx = charControlTx
                    if (tx != null) {
                        sendChunkedNotification(conn, tx, controlPacket)
                    }
                    sentSomething = true
                }

                if (!sentSomething) {
                    val audioPacket = conn.audioQueue.pollFirst()
                    if (audioPacket != null) {
                        val audioChar = charAudioDown
                        if (audioChar != null) {
                            sendChunkedNotification(conn, audioChar, audioPacket)
                        }
                        sentSomething = true
                    }
                }

                if (!sentSomething) {
                    conn.wakeChannel.receive()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendChunkedNotification(
        conn: ClientConnection,
        characteristic: BluetoothGattCharacteristic,
        fullData: ByteArray
    ) {
        val server = gattServer ?: return
        val maxChunk = (conn.mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < fullData.size && isRunning) {
            val end = minOf(offset + maxChunk, fullData.size)
            val chunk = fullData.copyOfRange(offset, end)
            offset = end

            val signal = CompletableDeferred<Boolean>()
            conn.notifySignal = signal

            val dispatched = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val res = server.notifyCharacteristicChanged(conn.device, characteristic, false, chunk)
                    res == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = chunk
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(conn.device, characteristic, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "notifyCharacteristicChanged failed for ${conn.address}: ${e.message}")
                false
            }

            if (!dispatched) {
                conn.notifySignal = null
                break
            }

            withTimeoutOrNull(800L) {
                signal.await()
            }
            conn.notifySignal = null
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT Service registered, starting BLE advertising")
                startAdvertising()
            } else {
                Log.e(TAG, "onServiceAdded failed with status: $status")
                onHostError("Failed to register Bluetooth intercom service (status $status)")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "BLE client connected: $address")
                val conn = ClientConnection(device)
                clients[address] = conn
                startSenderLoop(conn)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "BLE client disconnected: $address")
                val removed = clients.remove(address)
                removed?.senderJob?.cancel()
                removed?.notifySignal?.complete(false)
                onClientDisconnected(address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            clients[device.address]?.let { conn ->
                conn.mtu = mtu
                Log.d(TAG, "Client ${device.address} MTU updated to $mtu")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (value == null || value.isEmpty()) return

            val conn = clients[device.address] ?: return
            val packets = when (characteristic.uuid) {
                IntercomProtocol.CHAR_CONTROL_RX_UUID -> conn.controlAccumulator.append(value)
                IntercomProtocol.CHAR_AUDIO_UP_UUID -> conn.audioAccumulator.append(value)
                else -> emptyList()
            }

            packets.forEach { (type, payload) ->
                onPacketReceived(device.address, type, payload)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            clients[device.address]?.notifySignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }
}
