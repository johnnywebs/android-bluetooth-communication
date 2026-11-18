package com.example.bluetooth

import com.example.model.UserLocation
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

object IntercomProtocol {
    val ORBIT_SERVICE_UUID: UUID = UUID.fromString("e0cbf06c-cd8b-4647-bb8a-263b43f0f974")
    val CHAR_CONTROL_RX_UUID: UUID = UUID.fromString("e0cbf06d-cd8b-4647-bb8a-263b43f0f974")
    val CHAR_CONTROL_TX_UUID: UUID = UUID.fromString("e0cbf06e-cd8b-4647-bb8a-263b43f0f974")
    val CHAR_AUDIO_UP_UUID: UUID = UUID.fromString("e0cbf06f-cd8b-4647-bb8a-263b43f0f974")
    val CHAR_AUDIO_DOWN_UUID: UUID = UUID.fromString("e0cbf070-cd8b-4647-bb8a-263b43f0f974")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val SERVICE_NAME = "OrbitIntercomService"
    const val MANUFACTURER_ID = 0x4F52 // "OR"

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
    const val TYPE_ROOM_INFO_REQ: Byte = 0x09
    const val TYPE_ROOM_INFO_RESP: Byte = 0x0A

    fun encodePacket(type: Byte, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(7 + payload.size)
        val dos = DataOutputStream(baos)
        dos.writeByte(MAGIC_BYTE_1.toInt())
        dos.writeByte(MAGIC_BYTE_2.toInt())
        dos.writeByte(type.toInt())
        dos.writeInt(payload.size)
        dos.write(payload)
        dos.flush()
        return baos.toByteArray()
    }

    fun writePacket(outputStream: OutputStream, type: Byte, payload: ByteArray) {
        val bytes = encodePacket(type, payload)
        synchronized(outputStream) {
            outputStream.write(bytes)
            outputStream.flush()
        }
    }

    fun readPacket(inputStream: InputStream): Pair<Byte, ByteArray>? {
        val dis = DataInputStream(inputStream)
        var b1 = dis.readByte()
        var b2 = dis.readByte()
        while (b1 != MAGIC_BYTE_1 || b2 != MAGIC_BYTE_2) {
            b1 = b2
            b2 = dis.readByte()
        }

        val type = dis.readByte()
        val length = dis.readInt()
        if (length < 0 || length > 16384) {
            return null
        }

        val payload = ByteArray(length)
        dis.readFully(payload)
        return Pair(type, payload)
    }

    /**
     * Accumulates BLE GATT chunks and extracts complete framed packets.
     */
    class PacketAccumulator {
        private var buffer = ByteArray(0)

        @Synchronized
        fun append(chunk: ByteArray): List<Pair<Byte, ByteArray>> {
            if (chunk.isEmpty()) return emptyList()
            val combined = ByteArray(buffer.size + chunk.size)
            System.arraycopy(buffer, 0, combined, 0, buffer.size)
            System.arraycopy(chunk, 0, combined, buffer.size, chunk.size)
            buffer = combined

            val packets = mutableListOf<Pair<Byte, ByteArray>>()
            var offset = 0

            while (offset + 7 <= buffer.size) {
                if (buffer[offset] != MAGIC_BYTE_1 || buffer[offset + 1] != MAGIC_BYTE_2) {
                    offset++
                    continue
                }

                val type = buffer[offset + 2]
                val length = ((buffer[offset + 3].toInt() and 0xFF) shl 24) or
                        ((buffer[offset + 4].toInt() and 0xFF) shl 16) or
                        ((buffer[offset + 5].toInt() and 0xFF) shl 8) or
                        (buffer[offset + 6].toInt() and 0xFF)

                if (length < 0 || length > 16384) {
                    offset += 2
                    continue
                }

                if (offset + 7 + length > buffer.size) {
                    // Wait for remaining chunks
                    break
                }

                val payload = ByteArray(length)
                System.arraycopy(buffer, offset + 7, payload, 0, length)
                packets.add(Pair(type, payload))
                offset += 7 + length
            }

            if (offset > 0) {
                val remaining = buffer.size - offset
                buffer = if (remaining > 0) {
                    buffer.copyOfRange(offset, buffer.size)
                } else {
                    ByteArray(0)
                }
            }

            // Cap runaway buffer on malformed traffic
            if (buffer.size > 32768) {
                buffer = ByteArray(0)
            }

            return packets
        }

        @Synchronized
        fun clear() {
            buffer = ByteArray(0)
        }
    }

    fun buildAudioPayload(senderId: String, seq: Int, audioData: ByteArray): ByteArray {
        val idBytes = senderId.toByteArray(Charsets.UTF_8).take(16).toByteArray()
        val paddedId = ByteArray(16)
        System.arraycopy(idBytes, 0, paddedId, 0, idBytes.size)

        val buffer = ByteBuffer.allocate(16 + 4 + audioData.size)
        buffer.put(paddedId)
        buffer.putInt(seq)
        buffer.put(audioData)
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

    /**
     * Packs [roomName] and [hostCallsign] into <= 25 bytes for BLE Scan Response Manufacturer Data.
     * Format: [version=1(1B)][roomLen(1B)][roomBytes][callsignBytes]
     */
    fun encodeAdvertiseRoomPayload(roomName: String, hostCallsign: String): ByteArray {
        val cleanRoom = roomName.trim().ifBlank { "Orbit Channel" }
        val cleanHost = hostCallsign.trim().ifBlank { "Host" }

        val roomBytes = truncateUtf8(cleanRoom, 15)
        val hostBytes = truncateUtf8(cleanHost, 23 - roomBytes.size)

        val out = ByteArray(2 + roomBytes.size + hostBytes.size)
        out[0] = 0x01 // Protocol v1 marker
        out[1] = roomBytes.size.toByte()
        System.arraycopy(roomBytes, 0, out, 2, roomBytes.size)
        System.arraycopy(hostBytes, 0, out, 2 + roomBytes.size, hostBytes.size)
        return out
    }

    fun decodeAdvertiseRoomPayload(data: ByteArray?): Pair<String, String>? {
        if (data == null || data.size < 3) return null
        if (data[0] != 0x01.toByte()) return null
        val roomLen = data[1].toInt() and 0xFF
        if (2 + roomLen > data.size) return null

        val roomName = String(data, 2, roomLen, Charsets.UTF_8).ifBlank { "Orbit Channel" }
        val hostLen = data.size - (2 + roomLen)
        val hostCallsign = if (hostLen > 0) {
            String(data, 2 + roomLen, hostLen, Charsets.UTF_8).ifBlank { "Host" }
        } else {
            "Host"
        }
        return Pair(roomName, hostCallsign)
    }

    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var len = text.length
        while (len > 0) {
            val sub = text.substring(0, len).toByteArray(Charsets.UTF_8)
            if (sub.size <= maxBytes) return sub
            len--
        }
        return ByteArray(0)
    }
}
