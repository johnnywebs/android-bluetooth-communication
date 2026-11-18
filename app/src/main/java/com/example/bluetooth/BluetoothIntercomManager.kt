package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.audio.ImaAdpcmCodec
import com.example.location.LocationTracker
import com.example.model.DiscoveredHost
import com.example.model.IntercomPeer
import com.example.model.RoomInfo
import com.example.model.RoomState
import com.example.model.UserLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BluetoothIntercomManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "BluetoothIntercom"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // State flows
    private val _roomState = MutableStateFlow(RoomState.IDLE)
    val roomState: StateFlow<RoomState> = _roomState.asStateFlow()

    private val _currentRoom = MutableStateFlow<RoomInfo?>(null)
    val currentRoom: StateFlow<RoomInfo?> = _currentRoom.asStateFlow()

    private val _peers = MutableStateFlow<List<IntercomPeer>>(emptyList())
    val peers: StateFlow<List<IntercomPeer>> = _peers.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = _discoveredHosts.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _incomingAudio = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val incomingAudio: SharedFlow<Pair<String, ByteArray>> = _incomingAudio.asSharedFlow()

    private val _activeTalkers = MutableStateFlow<Set<String>>(emptySet())
    val activeTalkers: StateFlow<Set<String>> = _activeTalkers.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // Classic RFCOMM fallback state (only used if BLE Peripheral advertising is unsupported)
    private var serverSocket: BluetoothServerSocket? = null
    private var hostAcceptJob: Job? = null
    private val classicClientSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private var classicClientSocket: BluetoothSocket? = null
    private var classicClientReadJob: Job? = null

    // BLE state
    private val bleAddressToPeerId = ConcurrentHashMap<String, String>()
    private val peerIdToBleAddress = ConcurrentHashMap<String, String>()
    private var pendingJoinPin: String = ""
    private var pendingJoinHost: DiscoveredHost? = null

    // General state
    private var myPeerId: String = UUID.randomUUID().toString().take(8)
    private var myCallsign: String = "Alpha-1"
    private var myLatestLocation: UserLocation? = null
    private val sequenceNum = AtomicInteger(0)
    private val talkerTimeoutMap = ConcurrentHashMap<String, Long>()

    private val bleHostServer = BleHostServer(
        context = context,
        bluetoothManager = bluetoothManager,
        bluetoothAdapter = bluetoothAdapter,
        scope = scope,
        onPacketReceived = { deviceAddress, type, payload ->
            handleBleHostPacket(deviceAddress, type, payload)
        },
        onClientDisconnected = { deviceAddress ->
            handleBleClientDisconnected(deviceAddress)
        },
        onHostError = { error ->
            scope.launch { _errorMessage.emit(error) }
        }
    )

    private val bleClientSession = BleClientSession(
        context = context,
        bluetoothAdapter = bluetoothAdapter,
        scope = scope,
        onHostDiscovered = { host ->
            upsertDiscoveredHost(host)
        },
        onScanStateChanged = { scanning ->
            _isScanning.value = scanning
        },
        onScanError = { error ->
            scope.launch { _errorMessage.emit(error) }
        },
        onSessionReady = {
            sendBleHandshakeRequest()
        },
        onPacketReceived = { type, payload ->
            handleBleClientPacket(type, payload)
        },
        onDisconnected = { reason ->
            if (_roomState.value == RoomState.CONNECTING || _roomState.value == RoomState.IN_ROOM) {
                if (!reason.isNullOrBlank()) {
                    scope.launch { _errorMessage.emit(reason) }
                }
                leaveRoom()
            }
        }
    )

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val enabled = state == BluetoothAdapter.STATE_ON
                    _isBluetoothEnabled.value = enabled
                    if (!enabled) {
                        stopDiscovery()
                        if (_roomState.value != RoomState.IDLE) {
                            leaveRoom()
                        }
                    }
                }
                BluetoothDevice.ACTION_UUID -> {
                    // Classic SDP fallback: ONLY add device if it exposes ORBIT_SERVICE_UUID
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val parcelUuids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, android.os.Parcelable::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    }

                    val hasOrbitService = parcelUuids?.any {
                        (it as? ParcelUuid)?.uuid == IntercomProtocol.ORBIT_SERVICE_UUID
                    } == true

                    if (device != null && hasOrbitService) {
                        val name = device.name ?: "Orbit Host (${device.address.takeLast(5)})"
                        upsertDiscoveredHost(
                            DiscoveredHost(
                                deviceName = name,
                                deviceAddress = device.address,
                                roomName = "$name Channel",
                                rssi = -60,
                                isBle = false
                            )
                        )
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_UUID)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothStateReceiver, filter)
        }

        // Periodic cleanup of active talker flags
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(200)
                val now = System.currentTimeMillis()
                val expired = talkerTimeoutMap.filter { now - it.value > 450 }.keys
                if (expired.isNotEmpty()) {
                    expired.forEach { talkerTimeoutMap.remove(it) }
                    _activeTalkers.value = talkerTimeoutMap.keys.toSet()
                    updatePeerTransmissionStates()
                }
            }
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {}
        leaveRoom()
        stopDiscovery()
    }

    private fun upsertDiscoveredHost(host: DiscoveredHost) {
        val currentList = _discoveredHosts.value.toMutableList()
        val existingIdx = currentList.indexOfFirst { it.deviceAddress == host.deviceAddress }
        if (existingIdx >= 0) {
            val existing = currentList[existingIdx]
            // Preserve richer roomName if scan response arrived previously
            val resolvedRoom = if (host.roomName != "Orbit Channel" || existing.roomName == "Orbit Channel") {
                host.roomName
            } else {
                existing.roomName
            }
            val resolvedName = if (host.deviceName.startsWith("Host (") && !existing.deviceName.startsWith("Host (")) {
                existing.deviceName
            } else {
                host.deviceName
            }
            currentList[existingIdx] = host.copy(roomName = resolvedRoom, deviceName = resolvedName)
        } else {
            currentList.add(host)
        }
        _discoveredHosts.value = currentList.sortedByDescending { it.rssi }
    }

    // ---------------- HOSTING ----------------

    @SuppressLint("MissingPermission")
    fun startHostRoom(roomName: String, pin: String, callsign: String) {
        leaveRoom()
        stopDiscovery()

        myCallsign = callsign
        myPeerId = "H-" + UUID.randomUUID().toString().take(6)

        val room = RoomInfo(
            name = roomName,
            pin = pin,
            hostCallsign = callsign,
            isHost = true,
            hostAddress = "HOST"
        )
        _currentRoom.value = room

        val selfPeer = IntercomPeer(
            id = myPeerId,
            callsign = callsign,
            isHost = true,
            isSelf = true,
            location = myLatestLocation
        )
        _peers.value = listOf(selfPeer)

        if (bluetoothAdapter == null) {
            _roomState.value = RoomState.IN_ROOM
            scope.launch { _errorMessage.emit("Bluetooth hardware not detected on this device.") }
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _isBluetoothEnabled.value = false
            _roomState.value = RoomState.IDLE
            scope.launch { _errorMessage.emit("Bluetooth is disabled. Please turn on Bluetooth to host a room.") }
            return
        }

        _isBluetoothEnabled.value = true
        _roomState.value = RoomState.IN_ROOM

        // Start BLE Host Server + Advertising (primary discovery & transport)
        val bleStarted = if (bleHostServer.isAdvertisingSupported()) {
            bleHostServer.start(roomName, callsign)
        } else {
            false
        }

        if (!bleStarted) {
            // Fallback to Classic RFCOMM server if BLE Peripheral advertising is unsupported
            startClassicRfcommHost(roomName, pin)
        } else {
            Log.d(TAG, "Hosted Orbit Intercom via BLE GATT: $roomName")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicRfcommHost(roomName: String, pin: String) {
        val adapter = bluetoothAdapter ?: return
        hostAcceptJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
                    IntercomProtocol.SERVICE_NAME,
                    IntercomProtocol.ORBIT_SERVICE_UUID
                )
                Log.d(TAG, "Host listening on Classic RFCOMM fallback: $roomName")

                while (isActive) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        break
                    } ?: break

                    launch(Dispatchers.IO) {
                        handleIncomingClassicClient(socket, pin)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Classic RFCOMM server", e)
                _errorMessage.emit("Bluetooth server error: ${e.localizedMessage}")
            }
        }
    }

    private fun handleBleHostPacket(deviceAddress: String, type: Byte, payload: ByteArray) {
        val expectedPin = _currentRoom.value?.pin ?: return

        when (type) {
            IntercomProtocol.TYPE_HANDSHAKE_REQ -> {
                val handshakeData = String(payload, Charsets.UTF_8).split(":")
                if (handshakeData.size < 2) {
                    bleHostServer.disconnectClient(deviceAddress)
                    return
                }
                val clientCallsign = handshakeData[0]
                val clientPin = handshakeData[1]

                if (clientPin != expectedPin) {
                    Log.w(TAG, "BLE PIN Mismatch from $deviceAddress")
                    bleHostServer.sendControlPacket(
                        deviceAddress,
                        IntercomProtocol.TYPE_HANDSHAKE_REJECT,
                        "INVALID_PIN".toByteArray(Charsets.UTF_8)
                    )
                    scope.launch {
                        delay(300)
                        bleHostServer.disconnectClient(deviceAddress)
                    }
                    return
                }

                val assignedId = "P-" + UUID.randomUUID().toString().take(6)
                bleAddressToPeerId[deviceAddress] = assignedId
                peerIdToBleAddress[assignedId] = deviceAddress

                val acceptPayload = "$assignedId:${_currentRoom.value?.name}".toByteArray(Charsets.UTF_8)
                bleHostServer.sendControlPacket(deviceAddress, IntercomProtocol.TYPE_HANDSHAKE_ACCEPT, acceptPayload)

                val newPeer = IntercomPeer(
                    id = assignedId,
                    callsign = clientCallsign,
                    isHost = false,
                    isSelf = false
                )
                _peers.value = _peers.value + newPeer
                broadcastPeerListToAll()

                // Send host's current GPS location so new client immediately calculates distance
                myLatestLocation?.let { loc ->
                    val locPayload = IntercomProtocol.buildLocationPayload(myPeerId, loc)
                    bleHostServer.sendControlPacket(deviceAddress, IntercomProtocol.TYPE_LOCATION_UPDATE, locPayload)
                }

                Log.d(TAG, "BLE Client admitted: $clientCallsign ($assignedId)")
            }

            IntercomProtocol.TYPE_AUDIO_FRAME -> {
                val verifiedPeerId = bleAddressToPeerId[deviceAddress] ?: return
                val parsed = IntercomProtocol.parseAudioPayload(payload) ?: return
                val (_, _, encodedAudio) = parsed
                val pcmAudio = ImaAdpcmCodec.decode(encodedAudio)
                if (pcmAudio.isNotEmpty()) {
                    markTalkerActive(verifiedPeerId)
                    scope.launch { _incomingAudio.emit(Pair(verifiedPeerId, pcmAudio)) }
                }
                // Relay to all other clients
                relayAudioToOthers(verifiedPeerId, payload)
            }

            IntercomProtocol.TYPE_LOCATION_UPDATE -> {
                val verifiedPeerId = bleAddressToPeerId[deviceAddress] ?: return
                val parsed = IntercomProtocol.parseLocationPayload(payload) ?: return
                updatePeerLocation(verifiedPeerId, parsed.second)
                relayLocationToOthers(verifiedPeerId, payload)
            }

            IntercomProtocol.TYPE_LEAVE -> {
                handleBleClientDisconnected(deviceAddress)
                bleHostServer.disconnectClient(deviceAddress)
            }
        }
    }

    private fun handleBleClientDisconnected(deviceAddress: String) {
        val peerId = bleAddressToPeerId.remove(deviceAddress) ?: return
        peerIdToBleAddress.remove(peerId)
        _peers.value = _peers.value.filter { it.id != peerId }
        broadcastPeerListToAll()
    }

    private suspend fun handleIncomingClassicClient(socket: BluetoothSocket, expectedPin: String) {
        var clientId: String? = null
        try {
            val input = socket.inputStream
            val output = socket.outputStream

            val packet = IntercomProtocol.readPacket(input)
            if (packet == null || packet.first != IntercomProtocol.TYPE_HANDSHAKE_REQ) {
                socket.close()
                return
            }

            val handshakeData = String(packet.second, Charsets.UTF_8).split(":")
            if (handshakeData.size < 2) {
                socket.close()
                return
            }
            val clientCallsign = handshakeData[0]
            val clientPin = handshakeData[1]

            if (clientPin != expectedPin) {
                IntercomProtocol.writePacket(
                    output,
                    IntercomProtocol.TYPE_HANDSHAKE_REJECT,
                    "INVALID_PIN".toByteArray(Charsets.UTF_8)
                )
                socket.close()
                return
            }

            val assignedId = "P-" + UUID.randomUUID().toString().take(6)
            clientId = assignedId
            classicClientSockets[assignedId] = socket

            val acceptPayload = "$assignedId:${_currentRoom.value?.name}".toByteArray(Charsets.UTF_8)
            IntercomProtocol.writePacket(output, IntercomProtocol.TYPE_HANDSHAKE_ACCEPT, acceptPayload)

            val newPeer = IntercomPeer(
                id = assignedId,
                callsign = clientCallsign,
                isHost = false,
                isSelf = false
            )
            _peers.value = _peers.value + newPeer
            broadcastPeerListToAll()

            while (scope.isActive && socket.isConnected) {
                val clientPacket = IntercomProtocol.readPacket(input) ?: break
                when (clientPacket.first) {
                    IntercomProtocol.TYPE_AUDIO_FRAME -> {
                        val parsed = IntercomProtocol.parseAudioPayload(clientPacket.second)
                        if (parsed != null) {
                            val (senderId, _, encodedAudio) = parsed
                            val pcmAudio = ImaAdpcmCodec.decode(encodedAudio)
                            if (pcmAudio.isNotEmpty()) {
                                markTalkerActive(senderId)
                                _incomingAudio.emit(Pair(senderId, pcmAudio))
                            }
                            relayAudioToOthers(senderId, clientPacket.second)
                        }
                    }
                    IntercomProtocol.TYPE_LOCATION_UPDATE -> {
                        val parsed = IntercomProtocol.parseLocationPayload(clientPacket.second)
                        if (parsed != null) {
                            val (senderId, loc) = parsed
                            updatePeerLocation(senderId, loc)
                            relayLocationToOthers(senderId, clientPacket.second)
                        }
                    }
                    IntercomProtocol.TYPE_LEAVE -> break
                }
            }
        } catch (_: Exception) {
            Log.d(TAG, "Classic client socket disconnected: $clientId")
        } finally {
            clientId?.let { id ->
                classicClientSockets.remove(id)
                _peers.value = _peers.value.filter { it.id != id }
                broadcastPeerListToAll()
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun broadcastPeerListToAll() {
        val serializedPeers = _peers.value.joinToString(";") {
            "${it.id},${it.callsign},${it.isHost}"
        }.toByteArray(Charsets.UTF_8)

        peerIdToBleAddress.values.forEach { address ->
            bleHostServer.sendControlPacket(address, IntercomProtocol.TYPE_PEER_UPDATE, serializedPeers)
        }

        classicClientSockets.values.forEach { socket ->
            try {
                IntercomProtocol.writePacket(
                    socket.outputStream,
                    IntercomProtocol.TYPE_PEER_UPDATE,
                    serializedPeers
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting peer update", e)
            }
        }
    }

    private fun relayAudioToOthers(originalSenderId: String, audioPayload: ByteArray) {
        peerIdToBleAddress.forEach { (peerId, address) ->
            if (peerId != originalSenderId) {
                bleHostServer.sendAudioPacket(address, audioPayload)
            }
        }

        classicClientSockets.forEach { (peerId, socket) ->
            if (peerId != originalSenderId) {
                try {
                    IntercomProtocol.writePacket(
                        socket.outputStream,
                        IntercomProtocol.TYPE_AUDIO_FRAME,
                        audioPayload
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error relaying audio to peer $peerId", e)
                }
            }
        }
    }

    // ---------------- CLIENT SCANNING & JOINING ----------------

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (bluetoothAdapter == null) {
            scope.launch { _errorMessage.emit("Bluetooth not supported on this device.") }
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _isBluetoothEnabled.value = false
            scope.launch { _errorMessage.emit("Please turn on Bluetooth to scan for Orbit Intercom rooms.") }
            return
        }

        _isBluetoothEnabled.value = true
        _discoveredHosts.value = emptyList()

        // Primary app-filtered BLE scan (only discovers rooms advertising ORBIT_SERVICE_UUID)
        val started = bleClientSession.startScan(timeoutMs = 20_000L)
        if (!started) {
            _isScanning.value = false
        }
    }

    fun stopDiscovery() {
        bleClientSession.stopScan()
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun joinRoom(host: DiscoveredHost, enteredPin: String, callsign: String) {
        leaveRoom()
        myCallsign = callsign
        pendingJoinPin = enteredPin
        pendingJoinHost = host
        _roomState.value = RoomState.CONNECTING

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _isBluetoothEnabled.value = false
            scope.launch { _errorMessage.emit("Bluetooth must be enabled to connect.") }
            _roomState.value = RoomState.IDLE
            return
        }

        if (host.isBle) {
            val started = bleClientSession.connect(host.deviceAddress)
            if (!started) {
                _roomState.value = RoomState.IDLE
                scope.launch { _errorMessage.emit("Unable to connect to room host.") }
            }
        } else {
            joinClassicRfcommRoom(host, enteredPin, callsign)
        }
    }

    private fun sendBleHandshakeRequest() {
        val handshakePayload = "$myCallsign:$pendingJoinPin".toByteArray(Charsets.UTF_8)
        bleClientSession.sendControlPacket(IntercomProtocol.TYPE_HANDSHAKE_REQ, handshakePayload)
    }

    private fun handleBleClientPacket(type: Byte, payload: ByteArray) {
        when (type) {
            IntercomProtocol.TYPE_HANDSHAKE_REJECT -> {
                val reason = String(payload, Charsets.UTF_8)
                scope.launch {
                    if (reason == "INVALID_PIN") {
                        _errorMessage.emit("Incorrect Room PIN. Access Denied.")
                    } else {
                        _errorMessage.emit("Connection rejected: $reason")
                    }
                }
                leaveRoom()
            }

            IntercomProtocol.TYPE_HANDSHAKE_ACCEPT -> {
                val acceptData = String(payload, Charsets.UTF_8).split(":")
                myPeerId = acceptData.getOrNull(0) ?: "P-Client"
                val host = pendingJoinHost
                val roomName = acceptData.getOrNull(1) ?: host?.roomName ?: "Orbit Channel"

                _currentRoom.value = RoomInfo(
                    name = roomName,
                    pin = pendingJoinPin,
                    hostCallsign = host?.deviceName ?: "Host",
                    isHost = false,
                    hostAddress = host?.deviceAddress ?: ""
                )
                _roomState.value = RoomState.IN_ROOM

                _peers.value = listOf(
                    IntercomPeer(
                        id = myPeerId,
                        callsign = myCallsign,
                        isHost = false,
                        isSelf = true,
                        location = myLatestLocation
                    )
                )

                myLatestLocation?.let { broadcastLocalLocation(it) }
            }

            IntercomProtocol.TYPE_PEER_UPDATE -> {
                val rawPeers = String(payload, Charsets.UTF_8).split(";")
                val peerList = rawPeers.mapNotNull { raw ->
                    val parts = raw.split(",")
                    if (parts.size >= 3) {
                        val existingLoc = _peers.value.firstOrNull { it.id == parts[0] }?.location
                        IntercomPeer(
                            id = parts[0],
                            callsign = parts[1],
                            isHost = parts[2].toBoolean(),
                            isSelf = parts[0] == myPeerId,
                            location = if (parts[0] == myPeerId) myLatestLocation else existingLoc
                        )
                    } else null
                }
                _peers.value = peerList
                myLatestLocation?.let { updatePeerDistances(it) }
            }

            IntercomProtocol.TYPE_AUDIO_FRAME -> {
                val parsed = IntercomProtocol.parseAudioPayload(payload) ?: return
                val (senderId, _, encodedAudio) = parsed
                if (senderId != myPeerId) {
                    val pcmAudio = ImaAdpcmCodec.decode(encodedAudio)
                    if (pcmAudio.isNotEmpty()) {
                        markTalkerActive(senderId)
                        scope.launch { _incomingAudio.emit(Pair(senderId, pcmAudio)) }
                    }
                }
            }

            IntercomProtocol.TYPE_LOCATION_UPDATE -> {
                val parsed = IntercomProtocol.parseLocationPayload(payload) ?: return
                updatePeerLocation(parsed.first, parsed.second)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinClassicRfcommRoom(host: DiscoveredHost, enteredPin: String, callsign: String) {
        val adapter = bluetoothAdapter ?: return
        classicClientReadJob = scope.launch(Dispatchers.IO) {
            try {
                val device = adapter.getRemoteDevice(host.deviceAddress)
                val socket = device.createInsecureRfcommSocketToServiceRecord(IntercomProtocol.ORBIT_SERVICE_UUID)
                classicClientSocket = socket

                stopDiscovery()
                socket.connect()

                val output = socket.outputStream
                val input = socket.inputStream

                val handshakePayload = "$callsign:$enteredPin".toByteArray(Charsets.UTF_8)
                IntercomProtocol.writePacket(output, IntercomProtocol.TYPE_HANDSHAKE_REQ, handshakePayload)

                val response = IntercomProtocol.readPacket(input)
                    ?: throw IOException("Connection dropped during handshake")

                if (response.first == IntercomProtocol.TYPE_HANDSHAKE_REJECT) {
                    val reason = String(response.second, Charsets.UTF_8)
                    _errorMessage.emit(
                        if (reason == "INVALID_PIN") "Incorrect Room PIN. Access Denied."
                        else "Connection rejected: $reason"
                    )
                    leaveRoom()
                    return@launch
                }

                if (response.first != IntercomProtocol.TYPE_HANDSHAKE_ACCEPT) {
                    throw IOException("Unexpected handshake packet: ${response.first}")
                }

                val acceptData = String(response.second, Charsets.UTF_8).split(":")
                myPeerId = acceptData.getOrNull(0) ?: "P-Client"
                val roomName = acceptData.getOrNull(1) ?: host.roomName

                _currentRoom.value = RoomInfo(
                    name = roomName,
                    pin = enteredPin,
                    hostCallsign = host.deviceName,
                    isHost = false,
                    hostAddress = host.deviceAddress
                )
                _roomState.value = RoomState.IN_ROOM

                _peers.value = listOf(
                    IntercomPeer(
                        id = myPeerId,
                        callsign = callsign,
                        isHost = false,
                        isSelf = true,
                        location = myLatestLocation
                    )
                )

                while (isActive && socket.isConnected) {
                    val packet = IntercomProtocol.readPacket(input) ?: break
                    when (packet.first) {
                        IntercomProtocol.TYPE_AUDIO_FRAME -> {
                            val parsed = IntercomProtocol.parseAudioPayload(packet.second)
                            if (parsed != null) {
                                val (senderId, _, encodedAudio) = parsed
                                if (senderId != myPeerId) {
                                    val pcmAudio = ImaAdpcmCodec.decode(encodedAudio)
                                    if (pcmAudio.isNotEmpty()) {
                                        markTalkerActive(senderId)
                                        _incomingAudio.emit(Pair(senderId, pcmAudio))
                                    }
                                }
                            }
                        }
                        IntercomProtocol.TYPE_PEER_UPDATE -> {
                            val rawPeers = String(packet.second, Charsets.UTF_8).split(";")
                            val peerList = rawPeers.mapNotNull { raw ->
                                val parts = raw.split(",")
                                if (parts.size >= 3) {
                                    val currentLoc = _peers.value.firstOrNull { it.id == parts[0] }?.location
                                    IntercomPeer(
                                        id = parts[0],
                                        callsign = parts[1],
                                        isHost = parts[2].toBoolean(),
                                        isSelf = parts[0] == myPeerId,
                                        location = currentLoc
                                    )
                                } else null
                            }
                            _peers.value = peerList
                        }
                        IntercomProtocol.TYPE_LOCATION_UPDATE -> {
                            val parsed = IntercomProtocol.parseLocationPayload(packet.second)
                            if (parsed != null) {
                                updatePeerLocation(parsed.first, parsed.second)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Join room error", e)
                    _errorMessage.emit("Could not connect: ${e.localizedMessage ?: "Device unreachable"}")
                }
                leaveRoom()
            }
        }
    }

    // ---------------- AUDIO TRANSMISSION ----------------

    fun broadcastLocalAudio(pcmAudio: ByteArray) {
        val room = _currentRoom.value ?: return
        val compressedAudio = ImaAdpcmCodec.encode(pcmAudio)
        if (compressedAudio.isEmpty()) return

        val payload = IntercomProtocol.buildAudioPayload(
            senderId = myPeerId,
            seq = sequenceNum.incrementAndGet(),
            audioData = compressedAudio
        )

        if (room.isHost) {
            peerIdToBleAddress.values.forEach { address ->
                bleHostServer.sendAudioPacket(address, payload)
            }
            classicClientSockets.values.forEach { socket ->
                try {
                    IntercomProtocol.writePacket(
                        socket.outputStream,
                        IntercomProtocol.TYPE_AUDIO_FRAME,
                        payload
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting host audio", e)
                }
            }
        } else {
            if (pendingJoinHost?.isBle == true) {
                bleClientSession.sendAudioPacket(payload)
            } else {
                classicClientSocket?.let { socket ->
                    try {
                        IntercomProtocol.writePacket(
                            socket.outputStream,
                            IntercomProtocol.TYPE_AUDIO_FRAME,
                            payload
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending client audio to host", e)
                    }
                }
            }
        }
    }

    // ---------------- LOCATION & DISTANCE TELEMETRY ----------------

    fun broadcastLocalLocation(location: UserLocation) {
        myLatestLocation = location
        val payload = IntercomProtocol.buildLocationPayload(myPeerId, location)
        updatePeerLocation(myPeerId, location)

        val room = _currentRoom.value ?: return
        if (room.isHost) {
            peerIdToBleAddress.values.forEach { address ->
                bleHostServer.sendControlPacket(address, IntercomProtocol.TYPE_LOCATION_UPDATE, payload)
            }
            classicClientSockets.values.forEach { socket ->
                try {
                    IntercomProtocol.writePacket(
                        socket.outputStream,
                        IntercomProtocol.TYPE_LOCATION_UPDATE,
                        payload
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting host location", e)
                }
            }
        } else {
            if (pendingJoinHost?.isBle == true) {
                bleClientSession.sendControlPacket(IntercomProtocol.TYPE_LOCATION_UPDATE, payload)
            } else {
                classicClientSocket?.let { socket ->
                    try {
                        IntercomProtocol.writePacket(
                            socket.outputStream,
                            IntercomProtocol.TYPE_LOCATION_UPDATE,
                            payload
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending client location to host", e)
                    }
                }
            }
        }
    }

    private fun relayLocationToOthers(originalSenderId: String, payload: ByteArray) {
        peerIdToBleAddress.forEach { (peerId, address) ->
            if (peerId != originalSenderId) {
                bleHostServer.sendControlPacket(address, IntercomProtocol.TYPE_LOCATION_UPDATE, payload)
            }
        }
        classicClientSockets.forEach { (peerId, socket) ->
            if (peerId != originalSenderId) {
                try {
                    IntercomProtocol.writePacket(
                        socket.outputStream,
                        IntercomProtocol.TYPE_LOCATION_UPDATE,
                        payload
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error relaying location to peer $peerId", e)
                }
            }
        }
    }

    fun updatePeerLocation(peerId: String, location: UserLocation) {
        val myLoc = myLatestLocation
        _peers.value = _peers.value.map { peer ->
            if (peer.id == peerId) {
                if (!peer.isSelf && myLoc != null) {
                    val (dist, bearing) = LocationTracker.calculateDistanceAndBearing(myLoc, location)
                    peer.copy(location = location, distanceMeters = dist, bearingDegrees = bearing)
                } else {
                    peer.copy(location = location)
                }
            } else {
                peer
            }
        }
    }

    fun updatePeerDistances(myLocation: UserLocation) {
        myLatestLocation = myLocation
        _peers.value = _peers.value.map { peer ->
            if (peer.isSelf) {
                peer.copy(location = myLocation, distanceMeters = 0f, bearingDegrees = 0f)
            } else if (peer.location != null) {
                val (dist, bearing) = LocationTracker.calculateDistanceAndBearing(
                    from = myLocation,
                    to = peer.location
                )
                peer.copy(distanceMeters = dist, bearingDegrees = bearing)
            } else {
                peer
            }
        }
    }

    private fun markTalkerActive(senderId: String) {
        talkerTimeoutMap[senderId] = System.currentTimeMillis()
        _activeTalkers.value = talkerTimeoutMap.keys.toSet()
        updatePeerTransmissionStates()
    }

    private fun updatePeerTransmissionStates() {
        val activeSet = _activeTalkers.value
        _peers.value = _peers.value.map { peer ->
            val isTalking = activeSet.contains(peer.id)
            peer.copy(
                isTransmitting = isTalking,
                audioLevel = if (isTalking) 0.85f else 0f
            )
        }
    }

    fun leaveRoom() {
        bleHostServer.stop()
        bleClientSession.disconnect()

        bleAddressToPeerId.clear()
        peerIdToBleAddress.clear()
        pendingJoinPin = ""
        pendingJoinHost = null

        hostAcceptJob?.cancel()
        hostAcceptJob = null

        classicClientReadJob?.cancel()
        classicClientReadJob = null

        classicClientSockets.values.forEach {
            try {
                IntercomProtocol.writePacket(
                    it.outputStream,
                    IntercomProtocol.TYPE_LEAVE,
                    myPeerId.toByteArray(Charsets.UTF_8)
                )
                it.close()
            } catch (_: Exception) {}
        }
        classicClientSockets.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            classicClientSocket?.close()
        } catch (_: Exception) {}
        classicClientSocket = null

        _currentRoom.value = null
        _peers.value = emptyList()
        _activeTalkers.value = emptySet()
        talkerTimeoutMap.clear()
        _roomState.value = RoomState.IDLE
    }
}
