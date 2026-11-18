package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.model.AudioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE = 16000 // 16kHz wideband voice
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_SHORTS = 320 // 20ms audio frame
        const val FRAME_SIZE_BYTES = FRAME_SIZE_SHORTS * 2
    }

    private val TAG = "AudioEngine"

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var recordingJob: Job? = null
    private var isRecording = false

    // State flows
    private val _localAudioLevel = MutableStateFlow(0f)
    val localAudioLevel: StateFlow<Float> = _localAudioLevel.asStateFlow()

    private val _isVoiceTriggered = MutableStateFlow(false)
    val isVoiceTriggered: StateFlow<Boolean> = _isVoiceTriggered.asStateFlow()

    // Dynamic settings
    var settings: AudioSettings = AudioSettings()
        set(value) {
            field = value
            updateHardwareEffects()
        }

    var isPttPressed: Boolean = false
    var isHandsfreeLocked: Boolean = false
    var isLoopbackTestActive: Boolean = false

    var onAudioChunkCaptured: ((ByteArray) -> Unit)? = null

    private var voxHangoverUntilMs = 0L

    fun start() {
        if (isRecording) return
        initAudioTrack()
        initAudioRecord()
        startCaptureLoop()
    }

    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null

        releaseHardwareEffects()
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufferSize = max(minBufferSize, FRAME_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord VOICE_COMMUNICATION failed, falling back to MIC")
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    bufferSize
                )
            }

            attachHardwareEffects()
            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "AudioRecord initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord", e)
        }
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val bufferSize = max(minBufferSize, FRAME_SIZE_BYTES * 4)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_OUT)
            .setEncoding(ENCODING)
            .build()

        try {
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    private fun attachHardwareEffects() {
        val audioSessionId = audioRecord?.audioSessionId ?: return
        if (audioSessionId == 0) return

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = settings.isHardwareNoiseSuppressionEnabled
                }
                Log.d(TAG, "Hardware NoiseSuppressor enabled: ${noiseSuppressor?.enabled}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor not supported", e)
        }

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = settings.isHardwareEchoCancellationEnabled
                }
                Log.d(TAG, "Hardware AcousticEchoCanceler enabled: ${echoCanceler?.enabled}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AcousticEchoCanceler not supported", e)
        }
    }

    private fun updateHardwareEffects() {
        try {
            noiseSuppressor?.enabled = settings.isHardwareNoiseSuppressionEnabled
            echoCanceler?.enabled = settings.isHardwareEchoCancellationEnabled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update hardware effects", e)
        }
    }

    private fun releaseHardwareEffects() {
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
            echoCanceler?.release()
            echoCanceler = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing effects", e)
        }
    }

    private fun startCaptureLoop() {
        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(FRAME_SIZE_SHORTS)
            val byteBuffer = ByteArray(FRAME_SIZE_BYTES)

            while (isActive && isRecording) {
                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    delay(20)
                    continue
                }

                val readCount = record.read(shortBuffer, 0, FRAME_SIZE_SHORTS)
                if (readCount <= 0) {
                    delay(10)
                    continue
                }

                // 1. Calculate RMS & apply mic gain
                var sumSquare = 0.0
                val gain = settings.micGainMultiplier

                for (i in 0 until readCount) {
                    // Apply software mic gain
                    val sample = (shortBuffer[i] * gain).toInt()
                    val clamped = min(Short.MAX_VALUE.toInt(), max(Short.MIN_VALUE.toInt(), sample)).toShort()
                    shortBuffer[i] = clamped
                    sumSquare += clamped * clamped
                }

                val rms = sqrt(sumSquare / readCount) / Short.MAX_VALUE.toDouble()
                val normalizedLevel = min(1.0, max(0.0, rms * 3.5)).toFloat() // scale for responsive VU display
                _localAudioLevel.value = normalizedLevel

                // 2. VOX & Noise Gate logic
                val now = System.currentTimeMillis()
                val isAboveGate = normalizedLevel > settings.noiseGateThreshold

                if (isAboveGate) {
                    voxHangoverUntilMs = now + 350 // 350ms hangover to prevent clipping between words
                    _isVoiceTriggered.value = true
                } else if (now > voxHangoverUntilMs) {
                    _isVoiceTriggered.value = false
                }

                val shouldTransmit = isPttPressed ||
                        isHandsfreeLocked ||
                        (settings.isVoxEnabled && (isAboveGate || now <= voxHangoverUntilMs)) ||
                        isLoopbackTestActive

                if (shouldTransmit) {
                    // Convert ShortArray to ByteArray (Little-Endian)
                    ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortBuffer, 0, readCount)

                    if (isLoopbackTestActive) {
                        // Loop back directly to track for microphone testing
                        audioTrack?.write(byteBuffer, 0, readCount * 2)
                    } else {
                        val packet = byteBuffer.copyOf(readCount * 2)
                        onAudioChunkCaptured?.invoke(packet)
                    }
                }
            }
        }
    }

    fun playAudioChunk(audioBytes: ByteArray) {
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.write(audioBytes, 0, audioBytes.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio chunk", e)
        }
    }
}
