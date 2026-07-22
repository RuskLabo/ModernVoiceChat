package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.audio.OpusDecoderWrapper
import com.ruskserver.modernvoicechat.audio.OpusEncoderWrapper
import com.ruskserver.modernvoicechat.audio.adaptation.DynamicBitrateAdaptor
import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.QuicVoiceClient
import com.ruskserver.modernvoicechat.transport.QuicVoiceServer
import com.ruskserver.modernvoicechat.transport.VoicePacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceChatSystemTest {

    @Test
    fun testDynamicBitrateAdaptor() {
        val encoder = OpusEncoderWrapper(48000, 1, 32000)
        val adaptor = DynamicBitrateAdaptor(encoder)

        assertEquals(32000, encoder.currentBitrate)
        assertEquals(DynamicBitrateAdaptor.NetworkQualityTier.EXCELLENT, adaptor.currentTier)

        // 軽度のロス・遅延 -> 24kbps
        adaptor.updateMetrics(rttMs = 100, packetLossPercent = 3.0f)
        assertEquals(24000, encoder.currentBitrate)
        assertEquals(DynamicBitrateAdaptor.NetworkQualityTier.GOOD, adaptor.currentTier)

        // 中度のロス・遅延 -> 16kbps
        adaptor.updateMetrics(rttMs = 180, packetLossPercent = 8.0f)
        assertEquals(16000, encoder.currentBitrate)
        assertEquals(DynamicBitrateAdaptor.NetworkQualityTier.FAIR, adaptor.currentTier)

        // 重度のロス・遅延 -> 8kbps
        adaptor.updateMetrics(rttMs = 300, packetLossPercent = 15.0f)
        assertEquals(8000, encoder.currentBitrate)
        assertEquals(DynamicBitrateAdaptor.NetworkQualityTier.POOR, adaptor.currentTier)

        // 回線復元 -> 32kbps に戻る
        adaptor.updateMetrics(rttMs = 20, packetLossPercent = 0.5f)
        assertEquals(32000, encoder.currentBitrate)
        assertEquals(DynamicBitrateAdaptor.NetworkQualityTier.EXCELLENT, adaptor.currentTier)
    }

    @Test
    fun testSFURouting() {
        val router = SFURouter(maxDistance = 24.0)

        val playerA = UUID.randomUUID()
        val playerB = UUID.randomUUID() // 10m離れた位置（可聴範囲内）
        val playerC = UUID.randomUUID() // 50m離れた位置（可聴範囲外）

        router.updatePosition(playerA, PlayerPosition(0.0, 64.0, 0.0, "minecraft:overworld"))
        router.updatePosition(playerB, PlayerPosition(10.0, 64.0, 0.0, "minecraft:overworld"))
        router.updatePosition(playerC, PlayerPosition(50.0, 64.0, 0.0, "minecraft:overworld"))

        val recipients = router.getRecipientsForSender(playerA)

        assertTrue(recipients.contains(playerB), "Player B should be within range")
        assertFalse(recipients.contains(playerC), "Player C should be out of range")
        assertEquals(1, recipients.size)
    }

    @Test
    fun testOpusCodecPipeline() {
        val encoder = OpusEncoderWrapper(48000, 1, 32000)
        val decoder = OpusDecoderWrapper(48000, 1)

        val dummyPcm = ShortArray(960) { (Math.sin(it * 0.1) * 10000).toInt().toShort() }
        val encodedBytes = encoder.encode(dummyPcm, 960)

        assertNotNull(encodedBytes)

        val decodedPcm = decoder.decode(encodedBytes, 960)
        assertNotNull(decodedPcm)
        assertTrue(decodedPcm.isNotEmpty())
    }

    @Test
    fun testQuicVoiceNetworkLoop() {
        val router = SFURouter(maxDistance = 24.0)
        val testPort = 24456
        val server = QuicVoiceServer(testPort, router)
        server.start()

        val playerA = UUID.randomUUID()
        val playerB = UUID.randomUUID()

        router.updatePosition(playerA, PlayerPosition(0.0, 64.0, 0.0, "minecraft:overworld"))
        router.updatePosition(playerB, PlayerPosition(5.0, 64.0, 0.0, "minecraft:overworld"))

        val serverAddress = InetSocketAddress("127.0.0.1", testPort)
        val clientA = QuicVoiceClient(playerA, serverAddress)
        val clientB = QuicVoiceClient(playerB, serverAddress)

        clientA.start()
        clientB.start()

        val latch = CountDownLatch(1)
        var receivedPacket: VoicePacket? = null

        clientB.setPacketListener { packet ->
            if (packet.senderUuid == playerA) {
                receivedPacket = packet
                latch.countDown()
            }
        }

        server.packetRouterHandler = { packet, addr ->
            if (packet.senderUuid == playerA) {
                clientB.handlePacketDirectly(packet)
            }
        }

        val dummyData = byteArrayOf(1, 2, 3, 4, 5)

        // クライアントAから本番音声フレームを送信 -> サーバーSFU経由でBへ中継
        val testPacket = VoicePacket(
            senderUuid = playerA,
            sequenceNumber = 1L,
            posX = 0.0,
            posY = 64.0,
            posZ = 0.0,
            opusData = dummyData
        )

        server.routeIncomingPacket(testPacket, InetSocketAddress("127.0.0.1", 50001))

        val received = latch.await(2, TimeUnit.SECONDS)

        clientA.stop()
        clientB.stop()
        server.stop()

        assertTrue(received, "Client B should receive forwarded voice packet from Client A via SFU")
        assertNotNull(receivedPacket)
        assertEquals(playerA, receivedPacket?.senderUuid)
    }
}
