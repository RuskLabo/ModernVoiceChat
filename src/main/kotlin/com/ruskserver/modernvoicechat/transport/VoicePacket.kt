package com.ruskserver.modernvoicechat.transport

import java.nio.ByteBuffer
import java.util.UUID

enum class VoiceRouteType {
    PROXIMITY,
    RADIO,
    DIRECT
}

/**
 * QUICトランスポート層で送受信される音声データパケット構造。
 */
data class VoicePacket(
    val senderUuid: UUID,
    val sequenceNumber: Long,
    val opusData: ByteArray,
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val posZ: Double = 0.0,
    val isRadio: Boolean = false,
    val quality: Float = 1.0f,
    val sessionEpoch: Long = 0L,
    val routeType: VoiceRouteType = VoiceRouteType.PROXIMITY
) {
    fun toBytes(): ByteArray {
        require(opusData.size <= MAX_OPUS_DATA_SIZE) {
            "Opus payload exceeds maximum size of $MAX_OPUS_DATA_SIZE bytes"
        }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + opusData.size)
        buffer.putLong(senderUuid.mostSignificantBits)
        buffer.putLong(senderUuid.leastSignificantBits)
        buffer.putLong(sequenceNumber)
        buffer.putDouble(posX)
        buffer.putDouble(posY)
        buffer.putDouble(posZ)
        buffer.put(if (isRadio) 1.toByte() else 0.toByte())
        buffer.putFloat(quality)
        buffer.putLong(sessionEpoch)
        buffer.put(routeType.ordinal.toByte())
        buffer.putInt(opusData.size)
        buffer.put(opusData)
        return buffer.array()
    }

    companion object {
        const val MAX_OPUS_DATA_SIZE = 4000
        const val HEADER_SIZE = 16 + 8 + 24 + 1 + 4 + 8 + 1 + 4

        fun fromBytes(bytes: ByteArray): VoicePacket {
            require(bytes.size >= HEADER_SIZE) {
                "Voice packet is too short: ${bytes.size} bytes"
            }
            val buffer = ByteBuffer.wrap(bytes)
            val most = buffer.long
            val least = buffer.long
            val seq = buffer.long
            val x = buffer.double
            val y = buffer.double
            val z = buffer.double
            val isRadio = buffer.get() != 0.toByte()
            val quality = buffer.float
            val sessionEpoch = buffer.long
            val routeOrdinal = buffer.get().toInt()
            require(routeOrdinal in VoiceRouteType.entries.indices) {
                "Invalid voice route type: $routeOrdinal"
            }
            val len = buffer.int
            require(len in 0..MAX_OPUS_DATA_SIZE) {
                "Invalid Opus payload length: $len"
            }
            require(len == buffer.remaining()) {
                "Opus payload length mismatch: declared=$len, remaining=${buffer.remaining()}"
            }
            val opus = ByteArray(len)
            buffer.get(opus)
            return VoicePacket(
                UUID(most, least), seq, opus, x, y, z, isRadio, quality,
                sessionEpoch, VoiceRouteType.entries[routeOrdinal]
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoicePacket
        return senderUuid == other.senderUuid && sequenceNumber == other.sequenceNumber
    }

    override fun hashCode(): Int {
        var result = senderUuid.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}
