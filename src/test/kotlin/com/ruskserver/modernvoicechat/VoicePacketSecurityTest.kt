package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.transport.VoicePacket
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.UUID

class VoicePacketSecurityTest {

    @Test
    fun `packet round trip preserves voice data`() {
        val packet = VoicePacket(
            senderUuid = UUID.randomUUID(),
            sequenceNumber = 42L,
            opusData = byteArrayOf(1, 2, 3)
        )

        val decoded = VoicePacket.fromBytes(packet.toBytes())

        assertEquals(packet.senderUuid, decoded.senderUuid)
        assertEquals(packet.sequenceNumber, decoded.sequenceNumber)
        assertArrayEquals(packet.opusData, decoded.opusData)
    }

    @Test
    fun `declared payload larger than datagram is rejected before allocation`() {
        val bytes = ByteArray(VoicePacket.HEADER_SIZE)
        ByteBuffer.wrap(bytes)
            .position(VoicePacket.HEADER_SIZE - Int.SIZE_BYTES)
            .putInt(Int.MAX_VALUE)

        assertThrows(IllegalArgumentException::class.java) {
            VoicePacket.fromBytes(bytes)
        }
    }

}
