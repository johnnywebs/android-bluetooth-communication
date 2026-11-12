package com.example.bluetooth

import com.example.model.UserLocation
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

object IntercomProtocol {
    val ORBIT_SERVICE_UUID: UUID = UUID.fromString("e0cbf06c-cd8b-4647-bb8a-263b43f0f974")
    const val SERVICE_NAME = "OrbitIntercomService"

    const val MAGIC_BYTE_1: Byte = 0x4F // 'O'
    const val MAGIC_BYTE_2: Byte = 0x52 // 'R'

    const val TYPE_HANDSHAKE_REQ: Byte = 0x01
    const val TYPE_HANDSHAKE_ACCEPT: Byte = 0x02
    const val TYPE_HANDSHAKE_REJECT: Byte = 0x03
    const val TYPE_PEER_UPDATE: Byte = 0x04
    const val TYPE_AUDIO_FRAME: Byte = 0x05
    const val TYPE_PING: Byte = 0x06
    const val TYPE_LEAVE: Byte = 0x07
    const val TYPE_LOCATION_UPDATE: Byte = 0x08

    fun writePacket(outputStream: OutputStream, type: Byte, payload: ByteArray) {
        synchronized(outputStream) {
            val dos = DataOutputStream(outputStream)
            dos.writeByte(MAGIC_BYTE_1.toInt())
            dos.writeByte(MAGIC_BYTE_2.toInt())
            dos.writeByte(type.toInt())
            dos.writeInt(payload.size)
            dos.write(payload)
            dos.flush()
        }
    }

    fun readPacket(inputStream: InputStream): Pair<Byte, ByteArray>? {
        val dis = DataInputStream(inputStream)
        // Scan for magic bytes
        var b1 = dis.readByte()
        var b2 = dis.readByte()
        while (b1 != MAGIC_BYTE_1 || b2 != MAGIC_BYTE_2) {
            b1 = b2
            b2 = dis.readByte()
        }

        val type = dis.readByte()
        val length = dis.readInt()
        if (length < 0 || length > 16384) {
            return null // Malformed or invalid length
        }

        val payload = ByteArray(length)
        dis.readFully(payload)
        return Pair(type, payload)
    }

    fun buildAudioPayload(senderId: String, seq: Int, audioPcm: ByteArray): ByteArray {
        val idBytes = senderId.toByteArray(Charsets.UTF_8).take(16).toByteArray()
        val paddedId = ByteArray(16)
        System.arraycopy(idBytes, 0, paddedId, 0, idBytes.size)

        val buffer = ByteBuffer.allocate(16 + 4 + audioPcm.size)
        buffer.put(paddedId)
        buffer.putInt(seq)
        buffer.put(audioPcm)
        return buffer.array()
    }

    fun parseAudioPayload(payload: ByteArray): Triple<String, Int, ByteArray>? {
        if (payload.size < 20) return null
        val buffer = ByteBuffer.wrap(payload)
        val idBytes = ByteArray(16)
        buffer.get(idBytes)
        val senderId = String(idBytes, Charsets.UTF_8).trimEnd('\u0000')
        val seq = buffer.getInt()
        val audioBytes = ByteArray(payload.size - 20)
        buffer.get(audioBytes)
        return Triple(senderId, seq, audioBytes)
    }

    fun buildLocationPayload(senderId: String, location: UserLocation): ByteArray {
        val idBytes = senderId.toByteArray(Charsets.UTF_8).take(16).toByteArray()
        val paddedId = ByteArray(16)
        System.arraycopy(idBytes, 0, paddedId, 0, idBytes.size)

        val buffer = ByteBuffer.allocate(16 + 8 + 8 + 8 + 4 + 8)
        buffer.put(paddedId)
        buffer.putDouble(location.latitude)
        buffer.putDouble(location.longitude)
        buffer.putDouble(location.altitude)
        buffer.putFloat(location.accuracy)
        buffer.putLong(location.timestamp)
        return buffer.array()
    }

    fun parseLocationPayload(payload: ByteArray): Pair<String, UserLocation>? {
        if (payload.size < 52) return null
        val buffer = ByteBuffer.wrap(payload)
        val idBytes = ByteArray(16)
        buffer.get(idBytes)
        val senderId = String(idBytes, Charsets.UTF_8).trimEnd('\u0000')
        val lat = buffer.getDouble()
        val lon = buffer.getDouble()
        val alt = buffer.getDouble()
        val acc = buffer.getFloat()
        val time = buffer.getLong()

        val location = UserLocation(
            latitude = lat,
            longitude = lon,
            altitude = alt,
            accuracy = acc,
            timestamp = time
        )
        return Pair(senderId, location)
    }
}
