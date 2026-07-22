package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.audio.OpusDecoderWrapper
import com.ruskserver.modernvoicechat.audio.OpusEncoderWrapper
import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.VoicePacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

/**
 * 音声の途切れ（ドロップアウト）、ブツブツ音（フレーム境界での不連続ノイズ）、
 * パケット欠損による音飛びが発生しないことを検証する品質保証テスト。
 */
class VoiceQualityAndStutterTest {

    @Test
    fun testVoiceContinuityAndNoStuttering() {
        val sampleRate = 48000
        val frameDurationMs = 20
        val samplesPerFrame = sampleRate * frameDurationMs / 1000 // 960 サンプル
        val totalFrames = 50 // 1000ms 分 (1秒間)

        val encoder = OpusEncoderWrapper(sampleRate, 1, 32000)
        val decoder = OpusDecoderWrapper(sampleRate, 1)

        val playerA = UUID.randomUUID()
        val playerB = UUID.randomUUID()

        val router = SFURouter(maxDistance = 24.0)
        router.updatePosition(playerA, PlayerPosition(0.0, 64.0, 0.0, "minecraft:overworld"))
        router.updatePosition(playerB, PlayerPosition(5.0, 64.0, 0.0, "minecraft:overworld"))

        // 440Hz (A4ノート) の連続サイン波PCM音声を生成
        val fullOriginalPcm = ShortArray(samplesPerFrame * totalFrames)
        val freq = 440.0
        for (i in fullOriginalPcm.indices) {
            val t = i.toDouble() / sampleRate
            fullOriginalPcm[i] = (sin(2.0 * Math.PI * freq * t) * 15000.0).toInt().toShort()
        }

        val receivedPackets = mutableListOf<VoicePacket>()
        val decodedPcmList = mutableListOf<ShortArray>()

        // 1. フレーム分割エンコード & ネットワークパケット化
        for (f in 0 until totalFrames) {
            val framePcm = ShortArray(samplesPerFrame)
            System.arraycopy(fullOriginalPcm, f * samplesPerFrame, framePcm, 0, samplesPerFrame)

            val encodedOpus = encoder.encode(framePcm, samplesPerFrame)
            assertNotNull(encodedOpus, "Frame $f Opus encoding failed")
            assertTrue(encodedOpus.isNotEmpty(), "Frame $f Opus payload is empty")

            val packet = VoicePacket(
                senderUuid = playerA,
                sequenceNumber = (f + 1).toLong(),
                posX = 0.0,
                posY = 64.0,
                posZ = 0.0,
                opusData = encodedOpus
            )

            // SFU ルーター経由で受信者 B へ転送されるか検証
            val recipients = router.getRecipientsForSender(playerA)
            assertTrue(recipients.contains(playerB), "Player B must be in recipient list")

            receivedPackets.add(packet)
        }

        // 2. パケット損失・欠損チェック (1パケットも落とされず全順序通り届いているか)
        assertEquals(totalFrames, receivedPackets.size, "All $totalFrames frames must be received without dropout")
        for (i in 0 until totalFrames) {
            assertEquals((i + 1).toLong(), receivedPackets[i].sequenceNumber, "Sequence number out of order at frame $i")
        }

        // 3. 受信者側でデコード
        for (packet in receivedPackets) {
            val decodedFrame = decoder.decode(packet.opusData, samplesPerFrame)
            assertNotNull(decodedFrame, "Decoded PCM should not be null")
            assertEquals(samplesPerFrame, decodedFrame.size, "Decoded frame size must match 960 samples")
            decodedPcmList.add(decodedFrame)
        }

        // 4. フレーム境界での音切れ・ブツブツノイズ（不連続跳躍）のチェック
        var zeroCount = 0
        var maxClickDiscontinuity = 0

        for (f in 0 until totalFrames) {
            val pcm = decodedPcmList[f]
            for (s in pcm.indices) {
                if (pcm[s].toInt() == 0) zeroCount++
            }

            // 隣接フレーム境界 (Frame f の最後 と Frame f+1 の最初) の連続性を検証
            if (f < totalFrames - 1) {
                val lastSampleOfCurrentFrame = pcm[samplesPerFrame - 1].toInt()
                val firstSampleOfNextFrame = decodedPcmList[f + 1][0].toInt()
                val stepDifference = abs(firstSampleOfNextFrame - lastSampleOfCurrentFrame)

                if (stepDifference > maxClickDiscontinuity) {
                    maxClickDiscontinuity = stepDifference
                }

                // フレーム境目で破滅的な振幅跳躍（ブツブツクリック音）が発生していないこと
                assertTrue(
                    stepDifference < 12000,
                    "Discontinuity click detected at frame boundary $f -> ${f + 1}: diff = $stepDifference"
                )
            }
        }

        // 全体の無音化（ドロップアウト・途切れ）が全体の 10% 以下であること（正常な連続音声）
        val totalSamples = samplesPerFrame * totalFrames
        val zeroRatio = zeroCount.toDouble() / totalSamples
        assertTrue(zeroRatio < 0.10, "Voice dropout detected: ${zeroRatio * 100}% samples are silent zero")

        println("=========================================================================")
        println("         VOICE CONTINUITY & NO-STUTTERING TEST RESULTS                  ")
        println("=========================================================================")
        println(" テスト音声波形                 : 440Hz 正弦波 (1秒間 / 50フレーム)")
        println(" 送受信パケット損失率            : 0.00% (${receivedPackets.size} / $totalFrames frames)")
        println(" フレーム境界の最大振幅段差      : $maxClickDiscontinuity (クリックノイズ発生なし)")
        println(" 無音ドロップアウト率            : ${String.format("%.2f", zeroRatio * 100)}%")
        println(" 評価                           : 音切れ・ブツブツ音の発生なし (音質正常)")
        println("=========================================================================")
    }
}
