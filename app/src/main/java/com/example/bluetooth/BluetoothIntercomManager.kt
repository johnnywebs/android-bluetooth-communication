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
import android.util.Log
import com.example.model.DiscoveredHost
import com.example.model.IntercomPeer
import com.example.model.RoomInfo
import com.example.model.RoomState
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

    private val _incomingAudio = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val incomingAudio: SharedFlow<Pair<String, ByteArray>> = _incomingAudio.asSharedFlow()

    private val _activeTalkers = MutableStateFlow<Set<String>>(emptySet())
    val activeTalkers: StateFlow<Set<String>> = _activeTalkers.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // Host state
    private var serverSocket: BluetoothServerSocket? = null
    private var hostAcceptJob: Job? = null
    private val clientSockets = ConcurrentHashMap<String, BluetoothSocket>()

    // Client state
    private var clientSocket: BluetoothSocket? = null
    private var clientReadJob: Job? = null

    // General state
    private var myPeerId: String = UUID.randomUUID().toString().take(8)
    private var myCallsign: String = "Alpha-1"
    private val sequenceNum = AtomicInteger(0)
    private val talkerTimeoutMap = ConcurrentHashMap<String, Long>()

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    device?.let { dev ->
                        val name = dev.name ?: "Bluetooth Device (${dev.address.takeLast(5)})"
                        val host = DiscoveredHost(
                            deviceName = name,
                            deviceAddress = dev.address,
                            roomName = if (name.contains("Room", ignoreCase = true) || name.contains("Channel", ignoreCase = true)) name else "$name Channel",
                            rssi = rssi
                        )
                        val currentList = _discoveredHosts.value.toMutableList()
                        if (currentList.none { it.deviceAddress == dev.address }) {
                            currentList.add(host)
                            _discoveredHosts.value = currentList
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)

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
            context.unregisterReceiver(discoveryReceiver)
        } catch (_: Exception) {}
        leaveRoom()
    }

    // ---------------- HOSTING ----------------

    @SuppressLint("MissingPermission")
    fun startHostRoom(roomName: String, pin: String, callsign: String) {
        leaveRoom()
        myCallsign = callsign
        myPeerId = "H-" + UUID.randomUUID().toString().take(6)

        val room = RoomInfo(
            name = roomName,
            pin = pin,
            hostCallsign = callsign,
            isHost = true,
            hostAddress = bluetoothAdapter?.address ?: "HOST"
        )
        _currentRoom.value = room

        // ONLY the host user is initially in the room
        val selfPeer = IntercomPeer(
            id = myPeerId,
            callsign = callsign,
            isHost = true,
            isSelf = true
        )
        _peers.value = listOf(selfPeer)

        if (bluetoothAdapter == null) {
            _roomState.value = RoomState.IN_ROOM
            scope.launch { _errorMessage.emit("Bluetooth hardware not detected on this device.") }
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _roomState.value = RoomState.IN_ROOM
            scope.launch { _errorMessage.emit("Bluetooth is currently disabled. Please enable Bluetooth.") }
            return
        }

        _roomState.value = RoomState.IN_ROOM

        hostAcceptJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    IntercomProtocol.SERVICE_NAME,
                    IntercomProtocol.ORBIT_SERVICE_UUID
                )
                Log.d(TAG, "Host listening on RFCOMM: $roomName (PIN: $pin)")

                while (isActive) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        Log.d(TAG, "Server socket closed or accept interrupted")
                        break
                    } ?: break

                    launch(Dispatchers.IO) {
                        handleIncomingClient(socket, pin)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting host server", e)
                _errorMessage.emit("Bluetooth server error: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun handleIncomingClient(socket: BluetoothSocket, expectedPin: String) {
        var clientId: String? = null
        try {
            val input = socket.inputStream
            val output = socket.outputStream

            // Handshake request
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
                Log.w(TAG, "PIN Mismatch! Got $clientPin, expected $expectedPin")
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
            clientSockets[assignedId] = socket

            // Accept handshake
            val acceptPayload = "$assignedId:${_currentRoom.value?.name}".toByteArray(Charsets.UTF_8)
            IntercomProtocol.writePacket(output, IntercomProtocol.TYPE_HANDSHAKE_ACCEPT, acceptPayload)

            // Add real peer
            val newPeer = IntercomPeer(
                id = assignedId,
                callsign = clientCallsign,
                isHost = false,
                isSelf = false
            )
            val updatedPeers = _peers.value.toMutableList().apply { add(newPeer) }
            _peers.value = updatedPeers
            broadcastPeerListToAll()

            Log.d(TAG, "Client connected: $clientCallsign ($assignedId)")

            // Read client packets (audio, pings, leave)
            while (scope.isActive && socket.isConnected) {
                val clientPacket = IntercomProtocol.readPacket(input) ?: break
                when (clientPacket.first) {
                    IntercomProtocol.TYPE_AUDIO_FRAME -> {
                        val parsed = IntercomProtocol.parseAudioPayload(clientPacket.second)
                        if (parsed != null) {
                            val (senderId, _, pcmAudio) = parsed
                            markTalkerActive(senderId)
                            _incomingAudio.emit(Pair(senderId, pcmAudio))

                            // Relay audio to all OTHER connected clients (Star network)
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
                    IntercomProtocol.TYPE_LEAVE -> {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client socket disconnected: $clientId")
        } finally {
            clientId?.let { id ->
                clientSockets.remove(id)
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

        clientSockets.values.forEach { socket ->
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
        clientSockets.forEach { (peerId, socket) ->
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

    // ---------------- CLIENT JOINING ----------------

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (bluetoothAdapter == null) {
            scope.launch { _errorMessage.emit("Bluetooth not supported on this device.") }
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            scope.launch { _errorMessage.emit("Please turn on Bluetooth to scan for nearby intercoms.") }
            return
        }

        try {
            _discoveredHosts.value = emptyList()
            _isScanning.value = true
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            val started = bluetoothAdapter.startDiscovery()
            if (!started) {
                _isScanning.value = false
                scope.launch { _errorMessage.emit("Could not start Bluetooth discovery.") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
            _isScanning.value = false
            scope.launch { _errorMessage.emit("Scan failed: ${e.localizedMessage}") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        _isScanning.value = false
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun joinRoom(host: DiscoveredHost, enteredPin: String, callsign: String) {
        leaveRoom()
        myCallsign = callsign
        _roomState.value = RoomState.CONNECTING

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            scope.launch { _errorMessage.emit("Bluetooth must be enabled to connect.") }
            _roomState.value = RoomState.IDLE
            return
        }

        clientReadJob = scope.launch(Dispatchers.IO) {
            try {
                val device = bluetoothAdapter.getRemoteDevice(host.deviceAddress)
                val socket = device.createInsecureRfcommSocketToServiceRecord(IntercomProtocol.ORBIT_SERVICE_UUID)
                clientSocket = socket

                stopDiscovery()
                socket.connect()

                val output = socket.outputStream
                val input = socket.inputStream

                // Send Handshake
                val handshakePayload = "$callsign:$enteredPin".toByteArray(Charsets.UTF_8)
                IntercomProtocol.writePacket(output, IntercomProtocol.TYPE_HANDSHAKE_REQ, handshakePayload)

                // Wait for response
                val response = IntercomProtocol.readPacket(input)
                if (response == null) {
                    throw IOException("Connection dropped during handshake")
                }

                if (response.first == IntercomProtocol.TYPE_HANDSHAKE_REJECT) {
                    val reason = String(response.second, Charsets.UTF_8)
                    if (reason == "INVALID_PIN") {
                        _errorMessage.emit("Incorrect Room PIN. Access Denied.")
                    } else {
                        _errorMessage.emit("Connection rejected: $reason")
                    }
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

                // Initially self in room until host sends peer list
                _peers.value = listOf(
                    IntercomPeer(
                        id = myPeerId,
                        callsign = callsign,
                        isHost = false,
                        isSelf = true
                    )
                )

                // Process client incoming stream
                while (isActive && socket.isConnected) {
                    val packet = IntercomProtocol.readPacket(input) ?: break
                    when (packet.first) {
                        IntercomProtocol.TYPE_AUDIO_FRAME -> {
                            val parsed = IntercomProtocol.parseAudioPayload(packet.second)
                            if (parsed != null) {
                                val (senderId, _, pcmAudio) = parsed
                                if (senderId != myPeerId) {
                                    markTalkerActive(senderId)
                                    _incomingAudio.emit(Pair(senderId, pcmAudio))
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
                                val (senderId, loc) = parsed
                                updatePeerLocation(senderId, loc)
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
        val payload = IntercomProtocol.buildAudioPayload(
            senderId = myPeerId,
            seq = sequenceNum.incrementAndGet(),
            audioPcm = pcmAudio
        )

        val room = _currentRoom.value ?: return

        if (room.isHost) {
            // As host, broadcast to all real connected clients
            clientSockets.values.forEach { socket ->
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
            // As client, send to host
            clientSocket?.let { socket ->
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

    // ---------------- LOCATION & DISTANCE TELEMETRY ----------------

    fun broadcastLocalLocation(location: com.example.model.UserLocation) {
        val payload = IntercomProtocol.buildLocationPayload(myPeerId, location)
        updatePeerLocation(myPeerId, location)

        val room = _currentRoom.value ?: return
        if (room.isHost) {
            clientSockets.values.forEach { socket ->
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
            clientSocket?.let { socket ->
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

    private fun relayLocationToOthers(originalSenderId: String, payload: ByteArray) {
        clientSockets.forEach { (peerId, socket) ->
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

    fun updatePeerLocation(peerId: String, location: com.example.model.UserLocation) {
        _peers.value = _peers.value.map { peer ->
            if (peer.id == peerId) {
                peer.copy(location = location)
            } else {
                peer
            }
        }
    }

    fun updatePeerDistances(myLocation: com.example.model.UserLocation) {
        _peers.value = _peers.value.map { peer ->
            if (peer.isSelf) {
                peer.copy(location = myLocation, distanceMeters = 0f, bearingDegrees = 0f)
            } else if (peer.location != null) {
                val (dist, bearing) = com.example.location.LocationTracker.calculateDistanceAndBearing(
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
        hostAcceptJob?.cancel()
        hostAcceptJob = null

        clientReadJob?.cancel()
        clientReadJob = null

        clientSockets.values.forEach {
            try {
                IntercomProtocol.writePacket(
                    it.outputStream,
                    IntercomProtocol.TYPE_LEAVE,
                    myPeerId.toByteArray(Charsets.UTF_8)
                )
                it.close()
            } catch (_: Exception) {}
        }
        clientSockets.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        clientSocket = null

        _currentRoom.value = null
        _peers.value = emptyList()
        _activeTalkers.value = emptySet()
        talkerTimeoutMap.clear()
        _roomState.value = RoomState.IDLE
    }
}
