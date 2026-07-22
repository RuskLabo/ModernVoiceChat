package com.ruskserver.modernvoicechat.audio

import de.maxhenkel.opus4j.OpusDecoder

/**
 * Opusデコーダのラッパークラス (Opus4J使用)。
 */
class OpusDecoderWrapper(
    val sampleRate: Int = 48000,
    val channels: Int = 1
) {
    private var decoder: OpusDecoder? = null

    init {
        try {
            decoder = OpusDecoder(sampleRate, channels)
        } catch (e: Throwable) {}
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
