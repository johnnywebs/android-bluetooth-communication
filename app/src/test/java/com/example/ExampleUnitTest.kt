package com.example

import com.example.audio.ImaAdpcmCodec
import com.example.bluetooth.IntercomProtocol
import com.example.model.UserLocation
import com.example.model.formatBearing
import com.example.model.formatDistance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun audio_payload_serialization_and_deserialization() {
        val originalSender = "Scout-1"
        val originalSeq = 42
        val dummyPcm = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val serialized = IntercomProtocol.buildAudioPayload(originalSender, originalSeq, dummyPcm)
        val parsed = IntercomProtocol.parseAudioPayload(serialized)

        assertNotNull(parsed)
        assertEquals(originalSender, parsed!!.first)
        assertEquals(originalSeq, parsed.second)
        assertArrayEquals(dummyPcm, parsed.third)
    }

    @Test
    fun location_payload_serialization_and_deserialization() {
        val sender = "Rider-Alpha"
        val location = UserLocation(
            latitude = 37.774929,
            longitude = -122.419416,
            altitude = 15.2,
            accuracy = 3.5f,
            timestamp = 1700000000000L
        )

        val serialized = IntercomProtocol.buildLocationPayload(sender, location)
        val parsed = IntercomProtocol.parseLocationPayload(serialized)

        assertNotNull(parsed)
        assertEquals(sender, parsed!!.first)
        assertEquals(location.latitude, parsed.second.latitude, 0.000001)
        assertEquals(location.longitude, parsed.second.longitude, 0.000001)
        assertEquals(location.accuracy, parsed.second.accuracy, 0.01f)
    }

    @Test
    fun distance_and_bearing_formatting() {
        assertEquals("< 1 m", formatDistance(0.5f))
        assertEquals("45 m", formatDistance(45.2f))
        assertEquals("1.5 km", formatDistance(1500f))
        assertEquals("Locating...", formatDistance(null))

        assertEquals("0° N", formatBearing(0f))
        assertEquals("90° E", formatBearing(90f))
        assertEquals("180° S", formatBearing(180f))
        assertEquals("270° W", formatBearing(270f))
    }

    @Test
    fun adpcm_codec_compresses_and_reconstructs_audio() {
        val sampleCount = 320
        val pcmBuffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sample = (sin(i * 0.1) * 12000).toInt().toShort()
            pcmBuffer.putShort(sample)
        }
        val originalPcm = pcmBuffer.array()

        val encoded = ImaAdpcmCodec.encode(originalPcm)
        // 640 bytes PCM -> 166 bytes ADPCM
        assertTrue(encoded.size < originalPcm.size / 3)

        val decoded = ImaAdpcmCodec.decode(encoded)
        assertEquals(originalPcm.size, decoded.size)

        val origShorts = ByteBuffer.wrap(originalPcm).order(ByteOrder.LITTLE_ENDIAN)
        val decShorts = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        var totalError = 0L
        for (i in 0 until sampleCount) {
            totalError += abs(origShorts.getShort().toInt() - decShorts.getShort().toInt())
        }
        val meanError = totalError / sampleCount
        assertTrue("ADPCM mean error too high: $meanError", meanError < 800)
    }

    @Test
    fun advertise_room_payload_fits_ble_limit_and_roundtrips() {
        val payload = IntercomProtocol.encodeAdvertiseRoomPayload("Squad Alpha", "Leader-1")
        assertTrue(payload.size <= 25)
        val decoded = IntercomProtocol.decodeAdvertiseRoomPayload(payload)
        assertNotNull(decoded)
        assertEquals("Squad Alpha", decoded!!.first)
        assertEquals("Leader-1", decoded.second)

        // Long names must still fit within 25 bytes
        val longPayload = IntercomProtocol.encodeAdvertiseRoomPayload(
            "VeryLongRoomNameThatExceedsBleLimit",
            "VeryLongHostCallsign"
        )
        assertTrue(longPayload.size <= 25)
        assertNotNull(IntercomProtocol.decodeAdvertiseRoomPayload(longPayload))
    }

    @Test
    fun packet_accumulator_reassembles_chunked_ble_packets() {
        val acc = IntercomProtocol.PacketAccumulator()
        val p1 = IntercomProtocol.encodePacket(IntercomProtocol.TYPE_HANDSHAKE_REQ, "Alpha:1234".toByteArray())
        val p2 = IntercomProtocol.encodePacket(IntercomProtocol.TYPE_PEER_UPDATE, "H-1,Alpha,true".toByteArray())

        // Prepend noise bytes and concatenate p1 + p2, then slice into 11-byte BLE chunks
        val stream = byteArrayOf(0x00, 0x12) + p1 + p2
        val extracted = mutableListOf<Pair<Byte, ByteArray>>()
        var i = 0
        while (i < stream.size) {
            val end = minOf(i + 11, stream.size)
            extracted.addAll(acc.append(stream.copyOfRange(i, end)))
            i = end
        }

        assertEquals(2, extracted.size)
        assertEquals(IntercomProtocol.TYPE_HANDSHAKE_REQ, extracted[0].first)
        assertEquals("Alpha:1234", String(extracted[0].second))
        assertEquals(IntercomProtocol.TYPE_PEER_UPDATE, extracted[1].first)
        assertEquals("H-1,Alpha,true", String(extracted[1].second))
    }
}
