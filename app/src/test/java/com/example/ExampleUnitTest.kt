package com.example

import com.example.bluetooth.IntercomProtocol
import com.example.model.UserLocation
import com.example.model.formatBearing
import com.example.model.formatDistance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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
}
