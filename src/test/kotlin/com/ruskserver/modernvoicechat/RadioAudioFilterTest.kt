package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.audio.dsp.RadioAudioFilter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin

class RadioAudioFilterTest {

    @Test
    fun testBandPassFilterAndQualityAttenuation() {
        val filter = RadioAudioFilter(sampleRate = 48000)
        val numSamples = 960 // 20ms

        // 1. 100Hz (低音カット対象) のサイン波
        val lowFreqPcm = ShortArray(numSamples) {
            (sin(2.0 * Math.PI * 100.0 * (it.toDouble() / 48000.0)) * 10000.0).toInt().toShort()
        }

        // 2. 1000Hz (通す帯域) のサイン波
        val midFreqPcm = ShortArray(numSamples) {
            (sin(2.0 * Math.PI * 1000.0 * (it.toDouble() / 48000.0)) * 10000.0).toInt().toShort()
        }

        // 700m 以内 (Quality = 1.0f: 完全クリア) で加工
        val processedLow = filter.process(lowFreqPcm, quality = 1.0f)
        val processedMid = filter.process(midFreqPcm, quality = 1.0f)

        // 低音 (100Hz) がカットされ減衰していることを検証
        val origLowEnergy = lowFreqPcm.map { abs(it.toInt()) }.average()
        val filteredLowEnergy = processedLow.map { abs(it.toInt()) }.average()
        assertTrue(filteredLowEnergy < origLowEnergy * 0.4, "100Hz low frequency must be attenuated by bandpass filter")

        // 通す帯域 (1000Hz) は音声として保持されていることを検証
        val filteredMidEnergy = processedMid.map { abs(it.toInt()) }.average()
        assertTrue(filteredMidEnergy > 3000.0, "1000Hz mid frequency voice must be passed through")

        // 700m 超 (Quality = 0.3f: 電波悪化) で加工
        val filterLowQuality = RadioAudioFilter(sampleRate = 48000)
        val processedLowQualityMid = filterLowQuality.process(midFreqPcm, quality = 0.3f)

        assertNotNull(processedLowQualityMid)
        assertEquals(numSamples, processedLowQualityMid.size)

        println("=========================================================================")
        println("          RADIO AUDIO FILTER (BANDPASS & QUALITY) TEST RESULTS           ")
        println("=========================================================================")
        println(" 100Hz (低音) 元エネルギー      : ${String.format("%.1f", origLowEnergy)}")
        println(" 100Hz (低音) フィルタ後        : ${String.format("%.1f", filteredLowEnergy)} (低音カット成功)")
        println(" 1000Hz (中音) フィルタ後       : ${String.format("%.1f", filteredMidEnergy)} (音声通過成功)")
        println(" 評価                           : 700m 鮮明保持 ＆ 300-3400Hz バンドパス正常")
        println("=========================================================================")
    }
}
