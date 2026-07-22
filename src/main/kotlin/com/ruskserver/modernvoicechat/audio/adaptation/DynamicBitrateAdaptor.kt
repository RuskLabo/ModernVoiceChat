package com.ruskserver.modernvoicechat.audio.adaptation

import com.ruskserver.modernvoicechat.audio.OpusEncoderWrapper

/**
 * ネットワークメトリクス（RTT, Packet Loss Rate）に基づき、
 * デフォルト32kbpsから下方向（32k -> 24k -> 16k -> 8k）へ動的にビットレートを調整する適応エンジン。
 */
class DynamicBitrateAdaptor(
    private val encoder: OpusEncoderWrapper
) {
    enum class NetworkQualityTier(val bitrateBps: Int, val lossThreshold: Int) {
        EXCELLENT(32000, 2),  // Loss < 2%, RTT < 80ms -> 32kbps
        GOOD(24000, 5),       // Loss 2-5%, RTT 80-150ms -> 24kbps
        FAIR(16000, 12),      // Loss 5-12%, RTT 150-250ms -> 16kbps
        POOR(8000, 100)       // Severe loss/high RTT -> 8kbps
    }

    var currentTier: NetworkQualityTier = NetworkQualityTier.EXCELLENT
        private set

    /**
     * 最新のネットワーク状態でビットレートを評価・更新する。
     * @param rttMs 往復遅延時間 (ミリ秒)
     * @param packetLossPercent パケット損失率 (0.0〜100.0 %)
     */
    fun updateMetrics(rttMs: Long, packetLossPercent: Float) {
        val targetTier = when {
            packetLossPercent > 12.0f || rttMs > 250 -> NetworkQualityTier.POOR
            packetLossPercent > 5.0f || rttMs > 150 -> NetworkQualityTier.FAIR
            packetLossPercent > 2.0f || rttMs > 80 -> NetworkQualityTier.GOOD
            else -> NetworkQualityTier.EXCELLENT
        }

        if (targetTier != currentTier) {
            currentTier = targetTier
            encoder.setBitrate(targetTier.bitrateBps)
        }

        // エンコーダへパケットロス予測値を設定（FECの自動制御）
        encoder.setPacketLossPercentage(packetLossPercent.toInt())
    }

    fun onPacketSent(bytes: Int) {
        // 送信統計用メトリクスフック
    }
}
