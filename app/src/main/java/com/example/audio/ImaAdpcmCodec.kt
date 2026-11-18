package com.example.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 4:1 IMA-ADPCM Audio Codec for 16-bit Mono PCM frames.
 * Reduces 16kHz 16-bit PCM bandwidth from 32 KB/s to ~8 KB/s for low-latency Bluetooth streaming.
 */
object ImaAdpcmCodec {

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private const val HEADER_SIZE = 6

    fun encode(pcmBytes: ByteArray): ByteArray {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount == 0) return ByteArray(0)

        val pcmBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            samples[i] = pcmBuffer.getShort()
        }

        var predictor = samples[0].toInt()
        var stepIndex = 0

        val remainingSamples = sampleCount - 1
        val adpcmPayloadBytes = (remainingSamples + 1) / 2
        val outBuffer = ByteBuffer.allocate(HEADER_SIZE + adpcmPayloadBytes).order(ByteOrder.LITTLE_ENDIAN)

        outBuffer.putShort(predictor.toShort())
        outBuffer.put(stepIndex.toByte())
        outBuffer.put(0.toByte()) // reserved
        outBuffer.putShort(sampleCount.toShort())

        var highNibble = false
        var currentByte = 0

        for (i in 1 until sampleCount) {
            val sample = samples[i].toInt()
            var diff = sample - predictor
            var sign = 0
            if (diff < 0) {
                sign = 8
                diff = -diff
            }

            var step = STEP_TABLE[stepIndex]
            var delta = 0
            var vpdiff = step shr 3

            if (diff >= step) {
                delta = 4
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 2
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 1
                vpdiff += step
            }

            if (sign != 0) {
                predictor -= vpdiff
            } else {
                predictor += vpdiff
            }
            predictor = max(-32768, min(32767, predictor))

            val nibble = delta or sign
            stepIndex += INDEX_TABLE[nibble]
            stepIndex = max(0, min(88, stepIndex))

            if (!highNibble) {
                currentByte = nibble and 0x0F
                highNibble = true
            } else {
                currentByte = currentByte or ((nibble and 0x0F) shl 4)
                outBuffer.put(currentByte.toByte())
                highNibble = false
                currentByte = 0
            }
        }

        if (highNibble) {
            outBuffer.put(currentByte.toByte())
        }

        return outBuffer.array()
    }

    fun decode(adpcmBytes: ByteArray): ByteArray {
        if (adpcmBytes.size < HEADER_SIZE) return ByteArray(0)

        val inBuffer = ByteBuffer.wrap(adpcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        var predictor = inBuffer.getShort().toInt()
        var stepIndex = inBuffer.get().toInt() and 0xFF
        inBuffer.get() // reserved
        val sampleCount = inBuffer.getShort().toInt() and 0xFFFF

        if (sampleCount <= 0 || stepIndex > 88) return ByteArray(0)

        val outBuffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        outBuffer.putShort(predictor.toShort())

        var samplesDecoded = 1
        while (samplesDecoded < sampleCount && inBuffer.hasRemaining()) {
            val packed = inBuffer.get().toInt() and 0xFF
            for (shift in 0..4 step 4) {
                if (samplesDecoded >= sampleCount) break
                val nibble = (packed shr shift) and 0x0F
                val step = STEP_TABLE[stepIndex]

                var vpdiff = step shr 3
                if ((nibble and 4) != 0) vpdiff += step
                if ((nibble and 2) != 0) vpdiff += step shr 1
                if ((nibble and 1) != 0) vpdiff += step shr 2

                if ((nibble and 8) != 0) {
                    predictor -= vpdiff
                } else {
                    predictor += vpdiff
                }
                predictor = max(-32768, min(32767, predictor))

                stepIndex += INDEX_TABLE[nibble]
                stepIndex = max(0, min(88, stepIndex))

                outBuffer.putShort(predictor.toShort())
                samplesDecoded++
            }
        }

        return outBuffer.array()
    }
}
