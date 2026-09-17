package com.edwardmonteiro.singlewalkie.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PacketType(val code: Byte) {
    HELLO(1),
    PING(2),
    PONG(3),
    PTT_START(4),
    AUDIO(5),
    PTT_END(6);

    companion object {
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}

data class WalkiePacket(
    val type: PacketType,
    val sequence: Int,
    val timestampMs: Long = System.currentTimeMillis(),
    val payload: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "Payload too large: ${payload.size}" }
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(type.code)
            .putInt(sequence)
            .putLong(timestampMs)
            .putShort(payload.size.toShort())
            .put(payload)
            .array()
    }

    companion object {
        private const val MAGIC = 0x53574B31 // SWK1
        const val HEADER_SIZE = 19
        const val MAX_PAYLOAD = 1200

        fun decode(data: ByteArray, length: Int = data.size): WalkiePacket? {
            if (length < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != MAGIC) return null
            val type = PacketType.fromCode(buffer.get()) ?: return null
            val sequence = buffer.int
            val timestamp = buffer.long
            val payloadLength = buffer.short.toInt() and 0xFFFF
            if (payloadLength > MAX_PAYLOAD || payloadLength > buffer.remaining()) return null
            val payload = ByteArray(payloadLength)
            buffer.get(payload)
            return WalkiePacket(type, sequence, timestamp, payload)
        }
    }
}
