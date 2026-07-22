package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.audio.OpusEncoderWrapper
import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.QuicVoiceClient
import com.ruskserver.modernvoicechat.transport.QuicVoiceServer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Tag("benchmark")
class VoiceChatBenchmarkTest {

    @Test
    fun benchmark16PlayersVoiceTraffic() {
        val playerCount = 16
        val framesToSendPerPlayer = 50 // 1秒分 (20ms * 50 = 1000ms)
        val testPort = 24458

        val router = SFURouter(maxDistance = 24.0)
        val server = QuicVoiceServer(testPort, router)
        server.start()

        val serverAddress = InetSocketAddress("127.0.0.1", testPort)
        val players = (1..playerCount).map { UUID.randomUUID() }
        val clients = mutableListOf<QuicVoiceClient>()

        val totalReceivedBytes = AtomicLong(0)
        val totalReceivedPackets = AtomicLong(0)

        val totalExpectedForwardedPackets = (playerCount * (playerCount - 1) * framesToSendPerPlayer).toLong()
        val latch = CountDownLatch((playerCount * (playerCount - 1) * framesToSendPerPlayer))

        // 1. 全16人を同一エリア(可聴域内)へ配置 & クライアント初期化
        for (i in 0 until playerCount) {
            val uuid = players[i]
            router.updatePosition(uuid, PlayerPosition(0.0, 64.0, 0.0, "minecraft:overworld"))

            val client = QuicVoiceClient(uuid, serverAddress)
            clients.add(client)
            client.start()
        }

        server.packetRouterHandler = { packet, _ ->
            val recipients = router.getRecipientsForSender(packet.senderUuid)
            for (recipientUuid in recipients) {
                val targetClient = clients.find { it.playerUuid == recipientUuid }
                if (targetClient != null) {
                    totalReceivedBytes.addAndGet(packet.opusData.size.toLong() + 48)
                    totalReceivedPackets.incrementAndGet()
                    targetClient.handlePacketDirectly(packet)
                }
            }
        }

        for (client in clients) {
            client.sendHandler = { packet ->
                server.routeIncomingPacket(packet, InetSocketAddress("127.0.0.1", testPort))
            }
        }

        // 2. 16人分のダミーOpus音声フレーム (32kbps相当 = 80 byte / 20ms) 生成
        val encoder = OpusEncoderWrapper(48000, 1, 32000)
        val dummyPcm = ShortArray(960) { (Math.sin(it * 0.1) * 5000).toInt().toShort() }
        val opusFrame = encoder.encode(dummyPcm, 960)

        val startTime = System.nanoTime()

        // 3. 16人全員が同時発話 (1秒間分 = 50フレーム) - 高速連打テスト
        for (frame in 1..framesToSendPerPlayer) {
            for (client in clients) {
                client.sendAudioFrame(opusFrame, 0.0, 64.0, 0.0)
            }
        }

        val durationNs = System.nanoTime() - startTime
        val durationMs = (durationNs / 1_000_000.0).coerceAtLeast(0.001)

        // クライアント ＆ サーバーの停止
        for (client in clients) client.stop()
        server.stop()

        // 5. 転送量・帯域メトリクス統計計算
        val totalBytes = totalReceivedBytes.get()
        val totalPackets = totalReceivedPackets.get()

        val totalKb = totalBytes / 1024.0
        val totalMb = totalKb / 1024.0

        val totalKbps = totalKb / (durationMs / 1000.0)
        val kbpsPerPlayer = totalKbps / playerCount
        val bpsPerPlayer = kbpsPerPlayer * 8

        println("=========================================================================")
        println("       MODERN VOICE CHAT - 16 PLAYERS TRAFFIC BENCHMARK RESULT           ")
        println("=========================================================================")
        println(" プレイヤー数                 : $playerCount 人 (全員が可聴領域で同時発話)")
        println(" 発話フレーム数 (1人あたり)  : $framesToSendPerPlayer フレーム (20ms * 50 = 1秒分)")
        println(" 計測時間                     : ${String.format("%.2f", durationMs)} ms")
        println("-------------------------------------------------------------------------")
        println(" サーバー総中継パケット数     : $totalPackets / $totalExpectedForwardedPackets pkts")
        println(" サーバー総中継データ量       : ${String.format("%.2f", totalKb)} KB (${String.format("%.3f", totalMb)} MB)")
        println("-------------------------------------------------------------------------")
        println(" プレイヤー1人あたりの平均受信用帯域 : ${String.format("%.2f", kbpsPerPlayer)} KB/s (${String.format("%.1f", bpsPerPlayer)} kbps)")
        println(" 16人全員の合計サーバーデータ転送レート : ${String.format("%.2f", totalKbps)} KB/s (${String.format("%.2f", totalKbps * 8 / 1024.0)} Mbps)")
        println("=========================================================================")
    }
}
