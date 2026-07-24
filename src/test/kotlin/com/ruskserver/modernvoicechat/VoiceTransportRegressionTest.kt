package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.audio.AudioRecorder
import com.ruskserver.modernvoicechat.audio.CapturedAudioFrame
import com.ruskserver.modernvoicechat.audio.VoiceJitterBuffer
import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.VoicePacket
import com.ruskserver.modernvoicechat.transport.VoiceRouteType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class VoiceTransportRegressionTest {
    @Test
    fun `recorder fans out every frame without stealing from transmitter`() {
        val recorder = AudioRecorder()
        val loopback = recorder.subscribe()
        val frame = CapturedAudioFrame(shortArrayOf(1, 2, 3), true, 123L)

        recorder.publishFrame(frame)

        assertArrayEquals(frame.pcm, recorder.readFrame()!!.pcm)
        assertArrayEquals(frame.pcm, loopback.readFrame()!!.pcm)
        loopback.close()
    }

    @Test
    fun `jitter buffer restores reordered datagrams and resets for a new epoch`() {
        val buffer = VoiceJitterBuffer(targetFrames = 3)
        val sender = UUID.randomUUID()
        val base = System.nanoTime()
        fun packet(sequence: Long, epoch: Long) = VoicePacket(
            senderUuid = sender,
            sequenceNumber = sequence,
            opusData = byteArrayOf(sequence.toByte()),
            sessionEpoch = epoch
        )

        assertTrue(buffer.offer(packet(1, 10), base))
        assertFalse(buffer.offer(packet(3, 10), base + 1))
        assertFalse(buffer.offer(packet(2, 10), base + 2))

        val sequences = (1..3).map {
            (buffer.poll(base + TimeUnit.MILLISECONDS.toNanos(60)) as
                VoiceJitterBuffer.PollResult.Packet).packet.sequenceNumber
        }
        assertEquals(listOf(1L, 2L, 3L), sequences)
        assertTrue(buffer.offer(packet(1, 11), base + TimeUnit.SECONDS.toNanos(1)))
    }

    @Test
    fun `voice packet preserves session epoch and server routing type`() {
        val packet = VoicePacket(
            senderUuid = UUID.randomUUID(),
            sequenceNumber = 42,
            opusData = byteArrayOf(1, 2, 3),
            sessionEpoch = 99,
            routeType = VoiceRouteType.DIRECT
        )

        val decoded = VoicePacket.fromBytes(packet.toBytes())

        assertEquals(99L, decoded.sessionEpoch)
        assertEquals(VoiceRouteType.DIRECT, decoded.routeType)
    }

    @Test
    fun `isolated players neither send nor receive proximity voice`() {
        val router = SFURouter(24.0)
        val privateA = UUID.randomUUID()
        val privateB = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        listOf(privateA, privateB, outsider).forEachIndexed { index, uuid ->
            router.updatePosition(
                uuid,
                PlayerPosition(index.toDouble(), 64.0, 0.0, "minecraft:overworld")
            )
        }
        router.addDirectLink(privateA, privateB, isolateProximity = true)

        assertEquals(setOf(privateB), router.getDirectRecipientsForSender(privateA))
        assertTrue(router.getProximityRecipientsForSender(privateA).isEmpty())
        assertFalse(router.getProximityRecipientsForSender(outsider).contains(privateA))
        assertFalse(router.getProximityRecipientsForSender(outsider).contains(privateB))
    }
}
