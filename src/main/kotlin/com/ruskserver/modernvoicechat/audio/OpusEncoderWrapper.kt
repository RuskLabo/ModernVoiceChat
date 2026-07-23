package com.ruskserver.modernvoicechat.audio

import de.maxhenkel.opus4j.OpusEncoder
import org.slf4j.LoggerFactory

/**
 * Opusエンコーダのラッパークラス (Opus4J使用)。
 */
class OpusEncoderWrapper(
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    initialBitrateBps: Int = 32000
) {
    private val logger = LoggerFactory.getLogger(OpusEncoderWrapper::class.java)
    private var encoder: OpusEncoder? = null

    var currentBitrate: Int = initialBitrateBps
        private set

    init {
        try {
            encoder = OpusEncoder(sampleRate, channels, OpusEncoder.Application.VOIP)
            logger.info("OpusEncoder initialized successfully")
        } catch (e: Throwable) {
            logger.error("Failed to initialize OpusEncoder (native library may be missing): ${e.message}", e)
        }
    }

    fun setBitrate(bitrateBps: Int) {
        val clamped = bitrateBps.coerceIn(8000, 64000)
        currentBitrate = clamped
    }

    fun setPacketLossPercentage(lossPercentage: Int) {
    }

    /**
     * PCM 16bitデータ（960 shortサンプル）をOpusフレームへエンコードする。
     */
    fun encode(pcm: ShortArray, frameSize: Int = 960): ByteArray {
        val enc = encoder ?: return ByteArray(0)
        return try {
            enc.encode(pcm) ?: ByteArray(0)
        } catch (e: Throwable) {
            ByteArray(0)
        }
    }
}
