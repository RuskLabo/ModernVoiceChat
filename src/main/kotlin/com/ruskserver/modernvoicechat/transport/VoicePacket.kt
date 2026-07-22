package com.ruskserver.modernvoicechat.transport

import java.nio.ByteBuffer
import java.util.UUID

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
    val quality: Float = 1.0f
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(16 + 8 + 24 + 1 + 4 + 4 + opusData.size)
        buffer.putLong(senderUuid.mostSignificantBits)
        buffer.putLong(senderUuid.leastSignificantBits)
        buffer.putLong(sequenceNumber)
        buffer.putDouble(posX)
        buffer.putDouble(posY)
        buffer.putDouble(posZ)
        buffer.put(if (isRadio) 1.toByte() else 0.toByte())
        buffer.putFloat(quality)
        buffer.putInt(opusData.size)
        buffer.put(opusData)
        return buffer.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): VoicePacket {
            val buffer = ByteBuffer.wrap(bytes)
            val most = buffer.long
            val least = buffer.long
            val seq = buffer.long
            val x = buffer.double
            val y = buffer.double
            val z = buffer.double
            val isRadio = buffer.get() != 0.toByte()
            val quality = buffer.float
            val len = buffer.int
            val opus = ByteArray(len)
            buffer.get(opus)
            return VoicePacket(UUID(most, least), seq, opus, x, y, z, isRadio, quality)
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
