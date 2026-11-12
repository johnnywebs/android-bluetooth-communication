package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.model.AudioDeviceItem
import com.example.model.AudioDeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioDeviceManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val TAG = "AudioDeviceManager"

    private val _availableInputs = MutableStateFlow<List<AudioDeviceItem>>(emptyList())
    val availableInputs: StateFlow<List<AudioDeviceItem>> = _availableInputs.asStateFlow()

    private val _availableOutputs = MutableStateFlow<List<AudioDeviceItem>>(emptyList())
    val availableOutputs: StateFlow<List<AudioDeviceItem>> = _availableOutputs.asStateFlow()

    private val _selectedInputId = MutableStateFlow("builtin_mic")
    val selectedInputId: StateFlow<String> = _selectedInputId.asStateFlow()

    private val _selectedOutputId = MutableStateFlow("builtin_speaker")
    val selectedOutputId: StateFlow<String> = _selectedOutputId.asStateFlow()

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                Log.d(TAG, "SCO audio state: $state")
                refreshDevices()
            }
        }
    }

    init {
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scoReceiver, filter)
        }
        refreshDevices()
    }

    fun release() {
        try {
            context.unregisterReceiver(scoReceiver)
        } catch (_: Exception) {}
        stopBluetoothSco()
    }

    fun refreshDevices() {
        val inputs = mutableListOf<AudioDeviceItem>()
        val outputs = mutableListOf<AudioDeviceItem>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices
            val currentCommDevice = audioManager.communicationDevice

            // Inputs
            inputs.add(
                AudioDeviceItem(
                    id = "builtin_mic",
                    name = "Built-in Microphone",
                    type = AudioDeviceType.BUILTIN_MIC,
                    isInput = true,
                    isSelected = _selectedInputId.value == "builtin_mic"
                )
            )

            // Outputs
            outputs.add(
                AudioDeviceItem(
                    id = "builtin_speaker",
                    name = "Speakerphone (Loud)",
                    type = AudioDeviceType.BUILTIN_SPEAKER,
                    isInput = false,
                    isSelected = _selectedOutputId.value == "builtin_speaker"
                )
            )
            outputs.add(
                AudioDeviceItem(
                    id = "builtin_earpiece",
                    name = "Phone Earpiece (Private)",
                    type = AudioDeviceType.BUILTIN_EARPIECE,
                    isInput = false,
                    isSelected = _selectedOutputId.value == "builtin_earpiece"
                )
            )

            for (device in commDevices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                        val label = "Bluetooth Headset (${device.productName})"
                        if (device.isSource) {
                            inputs.add(
                                AudioDeviceItem(
                                    id = "bt_input_${device.id}",
                                    name = label,
                                    type = AudioDeviceType.BLUETOOTH_SCO,
                                    isInput = true,
                                    isSelected = _selectedInputId.value == "bt_input_${device.id}"
                                )
                            )
                        }
                        if (device.isSink) {
                            outputs.add(
                                AudioDeviceItem(
                                    id = "bt_output_${device.id}",
                                    name = label,
                                    type = AudioDeviceType.BLUETOOTH_SCO,
                                    isInput = false,
                                    isSelected = _selectedOutputId.value == "bt_output_${device.id}"
                                )
                            )
                        }
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                        if (device.isSource) {
                            inputs.add(
                                AudioDeviceItem(
                                    id = "wired_input_${device.id}",
                                    name = "Wired Headset Mic",
                                    type = AudioDeviceType.WIRED_HEADSET,
                                    isInput = true,
                                    isSelected = _selectedInputId.value == "wired_input_${device.id}"
                                )
                            )
                        }
                        if (device.isSink) {
                            outputs.add(
                                AudioDeviceItem(
                                    id = "wired_output_${device.id}",
                                    name = "Wired Headphones",
                                    type = AudioDeviceType.WIRED_HEADSET,
                                    isInput = false,
                                    isSelected = _selectedOutputId.value == "wired_output_${device.id}"
                                )
                            )
                        }
                    }
                }
            }
        } else {
            // Pre-Android 12 fallback
            inputs.add(
                AudioDeviceItem(
                    id = "builtin_mic",
                    name = "Built-in Microphone",
                    type = AudioDeviceType.BUILTIN_MIC,
                    isInput = true,
                    isSelected = _selectedInputId.value == "builtin_mic"
                )
            )
            inputs.add(
                AudioDeviceItem(
                    id = "bt_sco_mic",
                    name = "Bluetooth Headset Mic (SCO)",
                    type = AudioDeviceType.BLUETOOTH_SCO,
                    isInput = true,
                    isSelected = _selectedInputId.value == "bt_sco_mic"
                )
            )

            outputs.add(
                AudioDeviceItem(
                    id = "builtin_speaker",
                    name = "Speakerphone (Loud)",
                    type = AudioDeviceType.BUILTIN_SPEAKER,
                    isInput = false,
                    isSelected = _selectedOutputId.value == "builtin_speaker"
                )
            )
            outputs.add(
                AudioDeviceItem(
                    id = "builtin_earpiece",
                    name = "Phone Earpiece (Private)",
                    type = AudioDeviceType.BUILTIN_EARPIECE,
                    isInput = false,
                    isSelected = _selectedOutputId.value == "builtin_earpiece"
                )
            )
            outputs.add(
                AudioDeviceItem(
                    id = "bt_sco_out",
                    name = "Bluetooth Headset Speaker (SCO)",
                    type = AudioDeviceType.BLUETOOTH_SCO,
                    isInput = false,
                    isSelected = _selectedOutputId.value == "bt_sco_out"
                )
            )
        }

        _availableInputs.value = inputs
        _availableOutputs.value = outputs
    }

    fun selectOutputDevice(deviceId: String) {
        _selectedOutputId.value = deviceId
        Log.d(TAG, "Selected output device: $deviceId")

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = audioManager.availableCommunicationDevices
                when {
                    deviceId == "builtin_speaker" -> {
                        stopBluetoothSco()
                        val speaker = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null) {
                            audioManager.setCommunicationDevice(speaker)
                        } else {
                            audioManager.isSpeakerphoneOn = true
                        }
                    }
                    deviceId == "builtin_earpiece" -> {
                        stopBluetoothSco()
                        val earpiece = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                        if (earpiece != null) {
                            audioManager.setCommunicationDevice(earpiece)
                        } else {
                            audioManager.isSpeakerphoneOn = false
                        }
                    }
                    deviceId.startsWith("bt_") -> {
                        val rawId = deviceId.removePrefix("bt_output_").toIntOrNull()
                        val btDevice = commDevices.firstOrNull { it.id == rawId }
                            ?: commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                        if (btDevice != null) {
                            audioManager.setCommunicationDevice(btDevice)
                        } else {
                            startBluetoothSco()
                        }
                    }
                    else -> {
                        audioManager.clearCommunicationDevice()
                    }
                }
            } else {
                when (deviceId) {
                    "builtin_speaker" -> {
                        stopBluetoothSco()
                        audioManager.isSpeakerphoneOn = true
                    }
                    "builtin_earpiece" -> {
                        stopBluetoothSco()
                        audioManager.isSpeakerphoneOn = false
                    }
                    "bt_sco_out" -> {
                        startBluetoothSco()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply output device routing", e)
        }
        refreshDevices()
    }

    fun selectInputDevice(deviceId: String) {
        _selectedInputId.value = deviceId
        Log.d(TAG, "Selected input device: $deviceId")

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (deviceId.contains("bt_")) {
                startBluetoothSco()
            } else {
                if (!_selectedOutputId.value.contains("bt_")) {
                    stopBluetoothSco()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply input device routing", e)
        }
        refreshDevices()
    }

    private fun startBluetoothSco() {
        try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.d(TAG, "Bluetooth SCO started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Bluetooth SCO", e)
        }
    }

    private fun stopBluetoothSco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                Log.d(TAG, "Bluetooth SCO stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Bluetooth SCO", e)
        }
    }
}
