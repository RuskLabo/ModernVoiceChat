package com.ruskserver.modernvoicechat.audio

import de.maxhenkel.opus4j.OpusDecoder
import org.slf4j.LoggerFactory

/**
 * Opusデコーダのラッパークラス (Opus4J使用)。
 */
class OpusDecoderWrapper(
    val sampleRate: Int = 48000,
    val channels: Int = 1
) {
    private val logger = LoggerFactory.getLogger(OpusDecoderWrapper::class.java)
    private var decoder: OpusDecoder? = null

    init {
        try {
            decoder = OpusDecoder(sampleRate, channels)
            logger.info("OpusDecoder initialized successfully")
        } catch (e: Throwable) {
            logger.error("Failed to initialize OpusDecoder (native library may be missing): ${e.message}", e)
        }
    }

    /**
     * Opusエンコード済みバイト配列をPCM ShortArray（16bit）へデコードする。
     */
    fun decode(opusData: ByteArray?, frameSize: Int = 960, decodeFec: Boolean = false): ShortArray {
        val dec = decoder ?: return ShortArray(frameSize * channels)
        return try {
            dec.decode(opusData, decodeFec) ?: ShortArray(frameSize * channels)
        } catch (e: Throwable) {
            ShortArray(frameSize * channels)
        }
    }
}
