package com.ruskserver.modernvoicechat.audio

import com.ruskserver.modernvoicechat.transport.VoicePacket
import java.util.TreeMap
import java.util.concurrent.TimeUnit

class VoiceJitterBuffer(
    private val targetFrames: Int = 3,
    private val maximumStartupDelayNanos: Long = TimeUnit.MILLISECONDS.toNanos(60),
    private val inactivityResetNanos: Long = TimeUnit.SECONDS.toNanos(2)
) {
    sealed interface PollResult {
        data class Packet(val packet: VoicePacket) : PollResult
        data object ConcealLoss : PollResult
        data object None : PollResult
    }

    private data class QueuedPacket(val packet: VoicePacket, val arrivedNanos: Long)

    private val packets = TreeMap<Long, QueuedPacket>()
    private var epoch: Long? = null
    private var expectedSequence: Long? = null
    private var started = false
    private var consecutiveLosses = 0
    private var lastArrivalNanos = Long.MIN_VALUE

    @Synchronized
    fun offer(packet: VoicePacket, nowNanos: Long = System.nanoTime()): Boolean {
        val mustReset = epoch != packet.sessionEpoch ||
            (lastArrivalNanos != Long.MIN_VALUE &&
                nowNanos - lastArrivalNanos >= inactivityResetNanos)
        if (mustReset) reset(packet.sessionEpoch)
        lastArrivalNanos = nowNanos
        val expected = expectedSequence
        if (expected != null && packet.sequenceNumber < expected) return mustReset
        packets.putIfAbsent(packet.sequenceNumber, QueuedPacket(packet, nowNanos))
        while (packets.size > 16) packets.pollFirstEntry()
        return mustReset
    }

    @Synchronized
    fun poll(nowNanos: Long = System.nanoTime()): PollResult {
        if (packets.isEmpty()) {
            started = false
            expectedSequence = null
            consecutiveLosses = 0
            return PollResult.None
        }
        if (!started) {
            val oldest = packets.firstEntry().value
            if (packets.size < targetFrames &&
                nowNanos - oldest.arrivedNanos < maximumStartupDelayNanos
            ) return PollResult.None
            started = true
            expectedSequence = packets.firstKey()
        }

        var expected = expectedSequence ?: packets.firstKey()
        packets.remove(expected)?.let {
            expectedSequence = expected + 1
            consecutiveLosses = 0
            return PollResult.Packet(it.packet)
        }

        val nextAvailable = packets.firstKey()
        if (nextAvailable > expected) {
            if (consecutiveLosses >= 3 || nextAvailable - expected > 128) {
                expected = nextAvailable
                expectedSequence = expected
                consecutiveLosses = 0
                return poll(nowNanos)
            }
            expectedSequence = expected + 1
            consecutiveLosses++
            return PollResult.ConcealLoss
        }
        return PollResult.None
    }

    @Synchronized
    fun clear() {
        reset(null)
    }

    private fun reset(newEpoch: Long?) {
        packets.clear()
        epoch = newEpoch
        expectedSequence = null
        started = false
        consecutiveLosses = 0
        lastArrivalNanos = Long.MIN_VALUE
    }
}
