package com.example.model

enum class RoomState {
    IDLE,
    SCANNING,
    CONNECTING,
    HOSTING,
    IN_ROOM
}

enum class AudioDeviceType {
    BUILTIN_MIC,
    BUILTIN_SPEAKER,
    BUILTIN_EARPIECE,
    WIRED_HEADSET,
    BLUETOOTH_SCO,
    BLUETOOTH_A2DP
}

data class AudioDeviceItem(
    val id: String,
    val name: String,
    val type: AudioDeviceType,
    val isInput: Boolean,
    val isSelected: Boolean = false
)

data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class IntercomPeer(
    val id: String,
    val callsign: String,
    val isHost: Boolean = false,
    val isTransmitting: Boolean = false,
    val audioLevel: Float = 0f, // 0.0 to 1.0
    val isSelf: Boolean = false,
    val deviceAddress: String = "",
    val location: UserLocation? = null,
    val distanceMeters: Float? = null,
    val bearingDegrees: Float? = null
)

data class RoomInfo(
    val name: String,
    val pin: String,
    val hostCallsign: String,
    val isHost: Boolean,
    val hostAddress: String = ""
)

data class DiscoveredHost(
    val deviceName: String,
    val deviceAddress: String,
    val roomName: String = "",
    val rssi: Int = 0,
    val isBle: Boolean = true,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class AudioSettings(
    val inputDeviceId: String = "default_input",
    val outputDeviceId: String = "default_output",
    val micGainMultiplier: Float = 1.8f, // 1.0x to 5.0x
    val noiseGateThreshold: Float = 0.22f, // 0.0 to 1.0 (RMS threshold for VOX)
    val isVoxEnabled: Boolean = false,
    val isHandsfreeLocked: Boolean = false,
    val isHardwareNoiseSuppressionEnabled: Boolean = true,
    val isHardwareEchoCancellationEnabled: Boolean = true
)

data class IntercomUiState(
    val callsign: String = "Alpha-1",
    val roomState: RoomState = RoomState.IDLE,
    val currentRoom: RoomInfo? = null,
    val peers: List<IntercomPeer> = emptyList(),
    val isTransmitting: Boolean = false,
    val localAudioLevel: Float = 0f, // 0.0 to 1.0 for VU meter
    val settings: AudioSettings = AudioSettings(),
    val availableInputDevices: List<AudioDeviceItem> = emptyList(),
    val availableOutputDevices: List<AudioDeviceItem> = emptyList(),
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val isScanning: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isDarkTheme: Boolean = true,
    val isBluetoothEnabled: Boolean = true,
    val isAudioTestActive: Boolean = false,
    val myLocation: UserLocation? = null,
    val radarRangeMeters: Float = 100f, // 25m, 50m, 100m, 250m, 500m, 1000m
    val isMapTabSelected: Boolean = false,
    val selectedPeerForTracking: IntercomPeer? = null
)

fun formatDistance(meters: Float?): String {
    if (meters == null) return "Locating..."
    return when {
        meters < 1f -> "< 1 m"
        meters < 1000f -> "${meters.toInt()} m"
        else -> String.format("%.1f km", meters / 1000f)
    }
}

fun formatBearing(bearingDegrees: Float?): String {
    if (bearingDegrees == null) return ""
    val normalized = (bearingDegrees % 360 + 360) % 360
    val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    val index = ((normalized + 11.25f) / 22.5f).toInt() % 16
    return "${normalized.toInt()}° ${directions[index]}"
}
