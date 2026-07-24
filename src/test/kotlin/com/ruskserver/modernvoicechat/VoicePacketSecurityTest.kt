package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.QuicVoiceServer
import com.ruskserver.modernvoicechat.transport.VoicePacket
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class VoicePacketSecurityTest {

    @Test
    fun `packet round trip preserves authentication token`() {
        val packet = VoicePacket(
            senderUuid = UUID.randomUUID(),
            sequenceNumber = 42L,
            opusData = byteArrayOf(1, 2, 3),
            sessionToken = UUID.randomUUID()
        )

        val decoded = VoicePacket.fromBytes(packet.toBytes())

        assertEquals(packet.senderUuid, decoded.senderUuid)
        assertEquals(packet.sequenceNumber, decoded.sequenceNumber)
        assertEquals(packet.sessionToken, decoded.sessionToken)
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

    @Test
    fun `server rejects packet with invalid session token`() {
        val sender = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        val validToken = UUID.randomUUID()
        val router = SFURouter(24.0)
        router.updatePosition(sender, PlayerPosition(0.0, 64.0, 0.0, "overworld"))
        router.updatePosition(recipient, PlayerPosition(1.0, 64.0, 0.0, "overworld"))
        val server = QuicVoiceServer(0, router)
        val routedPackets = AtomicInteger()
        server.packetAuthenticator = { uuid, token -> uuid == sender && token == validToken }
        server.packetRouterHandler = { _, _ -> routedPackets.incrementAndGet() }

        server.routeIncomingPacket(
            VoicePacket(sender, 1L, byteArrayOf(1), sessionToken = UUID.randomUUID()),
            InetSocketAddress("127.0.0.1", 50000)
        )
        assertEquals(0, routedPackets.get())

        server.routeIncomingPacket(
            VoicePacket(sender, 2L, byteArrayOf(1), sessionToken = validToken),
            InetSocketAddress("127.0.0.1", 50000)
        )
        assertEquals(1, routedPackets.get())
    }
}
