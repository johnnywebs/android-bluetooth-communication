package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioDeviceManager
import com.example.audio.AudioEngine
import com.example.bluetooth.BluetoothIntercomManager
import com.example.location.LocationTracker
import com.example.model.AudioDeviceItem
import com.example.model.AudioSettings
import com.example.model.DiscoveredHost
import com.example.model.IntercomPeer
import com.example.model.IntercomUiState
import com.example.model.RoomState
import com.example.model.UserLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IntercomViewModel(application: Application) : AndroidViewModel(application) {

    val audioDeviceManager = AudioDeviceManager(application)
    val audioEngine = AudioEngine(application, viewModelScope)
    val bluetoothIntercom = BluetoothIntercomManager(application, viewModelScope)
    val locationTracker = LocationTracker(application)

    private val _uiState = MutableStateFlow(IntercomUiState())
    val uiState: StateFlow<IntercomUiState> = _uiState.asStateFlow()

    private var locationBroadcastJob: Job? = null

    init {
        // Wire audio engine capture to bluetooth transmission
        audioEngine.onAudioChunkCaptured = { pcmChunk ->
            bluetoothIntercom.broadcastLocalAudio(pcmChunk)
        }

        // Wire incoming bluetooth audio to audio engine playback
        viewModelScope.launch {
            bluetoothIntercom.incomingAudio.collect { (_, pcmChunk) ->
                audioEngine.playAudioChunk(pcmChunk)
            }
        }

        // Sync local audio level for VU meter
        viewModelScope.launch {
            audioEngine.localAudioLevel.collectLatest { level ->
                _uiState.update { it.copy(localAudioLevel = level) }
            }
        }

        // Sync VOX trigger state
        viewModelScope.launch {
            audioEngine.isVoiceTriggered.collectLatest {
                updateTransmissionState()
            }
        }

        // Sync Bluetooth room state
        viewModelScope.launch {
            bluetoothIntercom.roomState.collectLatest { state ->
                _uiState.update { it.copy(roomState = state) }
                if (state == RoomState.IN_ROOM) {
                    audioEngine.start()
                    startLocationTracking()
                } else if (state == RoomState.IDLE) {
                    stopLocationTracking()
                    if (!_uiState.value.isAudioTestActive) {
                        audioEngine.stop()
                    }
                }
            }
        }

        // Sync current room
        viewModelScope.launch {
            bluetoothIntercom.currentRoom.collectLatest { room ->
                _uiState.update { it.copy(currentRoom = room) }
            }
        }

        // Sync peers and calculate relative distances
        viewModelScope.launch {
            bluetoothIntercom.peers.collectLatest { peerList ->
                val myLoc = _uiState.value.myLocation
                val mappedPeers = if (myLoc != null) {
                    peerList.map { peer ->
                        if (peer.isSelf) {
                            peer.copy(location = myLoc, distanceMeters = 0f, bearingDegrees = 0f)
                        } else if (peer.location != null) {
                            val (dist, bearing) = LocationTracker.calculateDistanceAndBearing(myLoc, peer.location)
                            peer.copy(distanceMeters = dist, bearingDegrees = bearing)
                        } else {
                            peer
                        }
                    }
                } else {
                    peerList
                }
                _uiState.update { it.copy(peers = mappedPeers) }
            }
        }

        // Sync current GPS location
        viewModelScope.launch {
            locationTracker.currentLocation.collectLatest { loc ->
                if (loc != null) {
                    _uiState.update { it.copy(myLocation = loc) }
                    bluetoothIntercom.updatePeerDistances(loc)
                    bluetoothIntercom.broadcastLocalLocation(loc)
                }
            }
        }

        // Sync discovered hosts
        viewModelScope.launch {
            bluetoothIntercom.discoveredHosts.collectLatest { hosts ->
                _uiState.update { it.copy(discoveredHosts = hosts) }
            }
        }

        // Sync scanning state
        viewModelScope.launch {
            bluetoothIntercom.isScanning.collectLatest { isScanning ->
                _uiState.update { it.copy(isScanning = isScanning) }
            }
        }

        // Sync Bluetooth radio state
        viewModelScope.launch {
            bluetoothIntercom.isBluetoothEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(isBluetoothEnabled = enabled) }
            }
        }

        // Sync error messages
        viewModelScope.launch {
            bluetoothIntercom.errorMessage.collectLatest { err ->
                _uiState.update { it.copy(errorMessage = err) }
            }
        }

        // Sync available audio devices
        viewModelScope.launch {
            audioDeviceManager.availableInputs.collectLatest { inputs ->
                _uiState.update { it.copy(availableInputDevices = inputs) }
            }
        }
        viewModelScope.launch {
            audioDeviceManager.availableOutputs.collectLatest { outputs ->
                _uiState.update { it.copy(availableOutputDevices = outputs) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.stop()
        audioDeviceManager.release()
        bluetoothIntercom.release()
        stopLocationTracking()
    }

    // ---------------- Actions ----------------

    fun updateCallsign(newCallsign: String) {
        _uiState.update { it.copy(callsign = newCallsign.trim()) }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(isDarkTheme = !it.isDarkTheme) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun hostRoom(roomName: String, pin: String) {
        val callsign = _uiState.value.callsign.ifBlank { "Host-1" }
        bluetoothIntercom.startHostRoom(
            roomName = roomName.ifBlank { "Intercom Channel" },
            pin = pin.ifBlank { "0000" },
            callsign = callsign
        )
    }

    fun startScanning() {
        bluetoothIntercom.startDiscovery()
    }

    fun stopScanning() {
        bluetoothIntercom.stopDiscovery()
    }

    fun joinRoom(host: DiscoveredHost, pin: String) {
        val callsign = _uiState.value.callsign.ifBlank { "Unit-2" }
        bluetoothIntercom.joinRoom(host, pin, callsign)
    }

    fun leaveRoom() {
        bluetoothIntercom.leaveRoom()
        stopLocationTracking()
        if (!_uiState.value.isAudioTestActive) {
            audioEngine.stop()
        }
        _uiState.update {
            it.copy(
                isTransmitting = false,
                isMapTabSelected = false,
                selectedPeerForTracking = null,
                settings = it.settings.copy(isHandsfreeLocked = false)
            )
        }
        audioEngine.isPttPressed = false
        audioEngine.isHandsfreeLocked = false
    }

    // ---------------- Location & Distance Tracking ----------------

    fun startLocationTracking() {
        locationTracker.startTracking()
        locationBroadcastJob?.cancel()
        locationBroadcastJob = viewModelScope.launch {
            while (isActive) {
                delay(4000)
                _uiState.value.myLocation?.let { loc ->
                    bluetoothIntercom.broadcastLocalLocation(loc)
                    bluetoothIntercom.updatePeerDistances(loc)
                }
            }
        }
    }

    fun stopLocationTracking() {
        locationBroadcastJob?.cancel()
        locationBroadcastJob = null
        locationTracker.stopTracking()
    }

    fun setMapTabSelected(selected: Boolean) {
        _uiState.update { it.copy(isMapTabSelected = selected) }
    }

    fun setRadarRange(rangeMeters: Float) {
        _uiState.update { it.copy(radarRangeMeters = rangeMeters) }
    }

    fun selectPeerForTracking(peer: IntercomPeer?) {
        _uiState.update { it.copy(selectedPeerForTracking = peer) }
    }

    // ---------------- Push-To-Talk & VOX ----------------

    fun setPttPressed(pressed: Boolean) {
        audioEngine.isPttPressed = pressed
        updateTransmissionState()
    }

    fun toggleHandsfreeLock() {
        val newState = !_uiState.value.settings.isHandsfreeLocked
        audioEngine.isHandsfreeLocked = newState
        _uiState.update {
            it.copy(settings = it.settings.copy(isHandsfreeLocked = newState))
        }
        updateTransmissionState()
    }

    fun toggleVox() {
        val newVox = !_uiState.value.settings.isVoxEnabled
        val newSettings = _uiState.value.settings.copy(isVoxEnabled = newVox)
        _uiState.update { it.copy(settings = newSettings) }
        audioEngine.settings = newSettings
        updateTransmissionState()
    }

    private fun updateTransmissionState() {
        val isTx = audioEngine.isPttPressed ||
                audioEngine.isHandsfreeLocked ||
                (_uiState.value.settings.isVoxEnabled && audioEngine.isVoiceTriggered.value) ||
                _uiState.value.isAudioTestActive

        _uiState.update { it.copy(isTransmitting = isTx) }
    }

    // ---------------- Audio Settings & Routing ----------------

    fun setMicGain(gain: Float) {
        val newSettings = _uiState.value.settings.copy(micGainMultiplier = gain)
        _uiState.update { it.copy(settings = newSettings) }
        audioEngine.settings = newSettings
    }

    fun setNoiseGateThreshold(threshold: Float) {
        val newSettings = _uiState.value.settings.copy(noiseGateThreshold = threshold)
        _uiState.update { it.copy(settings = newSettings) }
        audioEngine.settings = newSettings
    }

    fun toggleHardwareNoiseSuppression(enabled: Boolean) {
        val newSettings = _uiState.value.settings.copy(isHardwareNoiseSuppressionEnabled = enabled)
        _uiState.update { it.copy(settings = newSettings) }
        audioEngine.settings = newSettings
    }

    fun toggleHardwareEchoCancellation(enabled: Boolean) {
        val newSettings = _uiState.value.settings.copy(isHardwareEchoCancellationEnabled = enabled)
        _uiState.update { it.copy(settings = newSettings) }
        audioEngine.settings = newSettings
    }

    fun selectInputDevice(device: AudioDeviceItem) {
        audioDeviceManager.selectInputDevice(device.id)
        val newSettings = _uiState.value.settings.copy(inputDeviceId = device.id)
        _uiState.update { it.copy(settings = newSettings) }
    }

    fun selectOutputDevice(device: AudioDeviceItem) {
        audioDeviceManager.selectOutputDevice(device.id)
        val newSettings = _uiState.value.settings.copy(outputDeviceId = device.id)
        _uiState.update { it.copy(settings = newSettings) }
    }

    fun toggleAudioLoopbackTest() {
        val active = !_uiState.value.isAudioTestActive
        _uiState.update { it.copy(isAudioTestActive = active) }
        audioEngine.isLoopbackTestActive = active
        if (active) {
            audioEngine.start()
        } else if (_uiState.value.roomState == RoomState.IDLE) {
            audioEngine.stop()
        }
        updateTransmissionState()
    }
}
