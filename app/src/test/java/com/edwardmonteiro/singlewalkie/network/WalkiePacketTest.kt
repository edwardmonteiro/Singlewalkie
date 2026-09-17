package com.edwardmonteiro.singlewalkie.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkiePacketTest {
    @Test
    fun roundTripPreservesPacket() {
        val original = WalkiePacket(
            type = PacketType.AUDIO,
            sequence = 42,
            timestampMs = 123456789L,
            payload = byteArrayOf(1, 2, 3, 4),
        )
        val decoded = WalkiePacket.decode(original.encode())!!

        assertEquals(original.type, decoded.type)
        assertEquals(original.sequence, decoded.sequence)
        assertEquals(original.timestampMs, decoded.timestampMs)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun rejectsInvalidMagic() {
        val data = WalkiePacket(PacketType.PING, 1).encode()
        data[0] = 0
        assertNull(WalkiePacket.decode(data))
    }
}
