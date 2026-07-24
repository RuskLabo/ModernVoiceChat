package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.QuicVoiceClient
import com.ruskserver.modernvoicechat.transport.QuicVoiceServer
import com.ruskserver.modernvoicechat.transport.VoicePacket
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RadioQuicRoutingTest {

    @Test
    fun `radio reaches matching frequency beyond proximity range with reduced quality`() {
        val router = SFURouter(24.0)
        val sender = UUID.randomUUID()
        val receiver = UUID.randomUUID()
        val senderToken = UUID.randomUUID()
        val receiverToken = UUID.randomUUID()
        router.updatePosition(sender, PlayerPosition(0.0, 64.0, 0.0, "overworld"))
        router.updatePosition(receiver, PlayerPosition(850.0, 64.0, 0.0, "overworld"))

        val server = QuicVoiceServer(24460, router, Files.createTempDirectory("mvc-radio-quic"))
        server.packetAuthenticator = { uuid, token ->
            (uuid == sender && token == senderToken) ||
                (uuid == receiver && token == receiverToken)
        }
        server.radioTransmittingProvider = { it == sender }
        server.radioFrequencyProvider = { 144.0 }
        server.start()

        val address = InetSocketAddress("127.0.0.1", 24460)
        val senderClient = QuicVoiceClient(
            sender, address, sessionToken = senderToken,
            expectedCertificateFingerprint = server.certificateFingerprint
        )
        val receiverClient = QuicVoiceClient(
            receiver, address, sessionToken = receiverToken,
            expectedCertificateFingerprint = server.certificateFingerprint
        )
        val received = CountDownLatch(1)
        var receivedQuality = 1.0f
        receiverClient.setPacketListener {
            receivedQuality = it.quality
            received.countDown()
        }

        try {
            assertTrue(senderClient.start())
            assertTrue(receiverClient.start())
            senderClient.sendHandler?.invoke(
                VoicePacket(sender, 1L, byteArrayOf(1, 2, 3), isRadio = true)
            )
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertTrue(receivedQuality in 0.0f..0.6f)
        } finally {
            senderClient.stop()
            receiverClient.stop()
            server.stop()
        }
    }
}
