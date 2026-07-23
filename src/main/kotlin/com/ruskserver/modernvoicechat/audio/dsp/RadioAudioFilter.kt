package com.ruskserver.modernvoicechat.audio.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * TFAR 風の軍用無線オーディオ加工を行うリアルタイム DSP プロセッサ。
 * 
 * 処理パイプライン:
 *  1. バンドパスフィルタ (300Hz - 3400Hz / 電波悪化時 800Hz - 1800Hz)
 *  2. 軽いコンプレッサー & リミッター
 *  3. 電波品質 (0.0 ～ 1.0) に応じた減衰エフェクト:
 *     - ホワイトノイズ / ピンクノイズ添加
 *     - 適応的帯域幅狭窄化
 *     - 文節ドロップアウト (パケット欠落)
 *     - デジタル量子化歪み (MELPe風ロボット感)
 */
class RadioAudioFilter(
    val sampleRate: Int = 48000
) {
    // IIR Biquad フィルタの状態
    private var hpX1 = 0.0; private var hpX2 = 0.0; private var hpY1 = 0.0; private var hpY2 = 0.0
    private var lpX1 = 0.0; private var lpX2 = 0.0; private var lpY1 = 0.0; private var lpY2 = 0.0

    // ドロップアウト (パケット欠落) 状態
    private var isCurrentlyDropping = false
    private var dropFramesRemaining = 0

    /**
     * 48kHz PCM 音声サンプル (ShortArray) に無線エフェクトを適用します。
     * @param pcm 入力・出力兼用 PCM 配列
     * @param quality 電波品質 Q ∈ [0.0, 1.0] (1.0 = 700m以内で100%クリア, 0.0 = 完全減衰)
     * @return 加工後の PCM 配列
     */
    fun process(pcm: ShortArray, quality: Float): ShortArray {
        val clampedQuality = quality.coerceIn(0.0f, 1.0f)
        val result = ShortArray(pcm.size)

        // 1. 品質に応じた帯域幅の決定
        // クリア時 (Q=1.0): 300Hz ～ 3400Hz
        // 減衰時 (Q<1.0): 最大 800Hz ～ 1800Hz へ収縮
        val hpCutoff = 300.0 + (1.0 - clampedQuality) * 500.0 // 300Hz -> 800Hz
        val lpCutoff = 3400.0 - (1.0 - clampedQuality) * 1600.0 // 3400Hz -> 1800Hz

        // 2. 文節ドロップアウト判定 (700m 超の重度品質悪化 Q < 0.6f のみ発生)
        if (clampedQuality < 0.6f) {
            if (!isCurrentlyDropping && Random.nextFloat() < (0.6f - clampedQuality) * 0.12f) {
                isCurrentlyDropping = true
                dropFramesRemaining = Random.nextInt(2, 5) // 2～4フレーム(40ms～80ms)無音化
            }
        }

        // ドロップアウト処理
        if (isCurrentlyDropping) {
            dropFramesRemaining--
            if (dropFramesRemaining <= 0) {
                isCurrentlyDropping = false
            }
            return result
        }

        // 入力フレームが無音に近い場合はフィルタ状態をクリアして発振ループを防止
        var maxInAbs = 0.0
        for (s in pcm) {
            val a = abs(s.toDouble())
            if (a > maxInAbs) maxInAbs = a
        }
        if (maxInAbs < 50.0) {
            resetState()
            return result
        }

        // Biquad フィルタ係数計算
        val hpCoeffs = calculateHighPassCoeffs(hpCutoff, sampleRate.toDouble())
        val lpCoeffs = calculateLowPassCoeffs(lpCutoff, sampleRate.toDouble())

        val noiseFactor = if (clampedQuality >= 0.7f) {
            (1.0f - clampedQuality) * 0.05f
        } else {
            0.015f + (0.7f - clampedQuality) * 1.0f
        }
        val noiseAmplitude = (noiseFactor * 1500.0f).toInt()
        val bitCrushShift = if (clampedQuality < 0.4f) ((0.4f - clampedQuality) * 3.0f).toInt() else 0

        for (i in pcm.indices) {
            var sample = pcm[i].toDouble()

            // A. ハイパスフィルタ
            val hpOut = hpCoeffs.b0 * sample + hpCoeffs.b1 * hpX1 + hpCoeffs.b2 * hpX2 -
                    hpCoeffs.a1 * hpY1 - hpCoeffs.a2 * hpY2
            hpX2 = hpX1; hpX1 = sample
            hpY2 = hpY1; hpY1 = hpOut.coerceIn(-65536.0, 65536.0)
            sample = hpOut

            // B. ローパスフィルタ
            val lpOut = lpCoeffs.b0 * sample + lpCoeffs.b1 * lpX1 + lpCoeffs.b2 * lpX2 -
                    lpCoeffs.a1 * lpY1 - lpCoeffs.a2 * lpY2
            lpX2 = lpX1; lpX1 = sample
            lpY2 = lpY1; lpY1 = lpOut.coerceIn(-65536.0, 65536.0)
            sample = lpOut

            // C. 軽いコンプレッサー & ダイナミクス圧縮
            sample = compressSample(sample)

            // D. 電波品質低下時のホワイトノイズ添加
            if (noiseAmplitude > 0) {
                val noise = Random.nextInt(-noiseAmplitude, noiseAmplitude + 1)
                sample += noise
            }

            // E. デジタル歪み
            var intSample = sample.toInt().coerceIn(-32768, 32767)
            if (bitCrushShift > 0) {
                intSample = (intSample shr bitCrushShift) shl bitCrushShift
            }

            // F. リミッター (クリッピング防止)
            result[i] = intSample.coerceIn(-32000, 32000).toShort()
        }

        return result
    }

    fun resetState() {
        hpX1 = 0.0; hpX2 = 0.0; hpY1 = 0.0; hpY2 = 0.0
        lpX1 = 0.0; lpX2 = 0.0; lpY1 = 0.0; lpY2 = 0.0
    }

    private fun compressSample(sample: Double): Double {
        val absVal = abs(sample)
        val threshold = 12000.0
        val ratio = 0.6

        val compressedAbs = if (absVal > threshold) {
            threshold + (absVal - threshold) * ratio
        } else {
            absVal // 小音量の不要な連続ブーストを削除して発振・ノイズ持ち上げを防止
        }

        return if (sample >= 0) compressedAbs else -compressedAbs
    }

    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    private fun calculateHighPassCoeffs(cutoffHz: Double, sampleRateHz: Double): BiquadCoeffs {
        val w0 = 2.0 * Math.PI * cutoffHz / sampleRateHz
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * 0.707) // Q = 0.707 (Butterworth)

        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun calculateLowPassCoeffs(cutoffHz: Double, sampleRateHz: Double): BiquadCoeffs {
        val w0 = 2.0 * Math.PI * cutoffHz / sampleRateHz
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * 0.707)

        val b0 = (1.0 - cosW0) / 2.0
        val b1 = 1.0 - cosW0
        val b2 = (1.0 - cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
