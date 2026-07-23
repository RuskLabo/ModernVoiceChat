package com.ruskserver.modernvoicechat.audio

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * 型安全な ByteBuffer Little-Endian エンディアンパースと
 * アライメント補正を備えた高堅牢マイク音声キャプチャクラス。
 */
class AudioRecorder(
    val sampleRate: Int = 48000,
    val frameSizeSamples: Int = 960 // 20ms @ 48kHz = 960 サンプル
) {
    private val logger = LoggerFactory.getLogger(AudioRecorder::class.java)
    private var line: TargetDataLine? = null
    private var captureThread: Thread? = null

    @Volatile private var recording = false

    @Volatile
    var currentRmsPercentage: Int = 0
        private set

    // 1フレームあたりの期待バイト数 (16bit = 2 bytes/sample)
    val frameSizeBytes: Int = frameSizeSamples * 2

    private val pcmQueue = ArrayBlockingQueue<Pair<ShortArray, Boolean>>(5)

    @Synchronized
    fun start(): Boolean {
        if (recording) return true
        try {
            // Little-Endian (bigEndian = false) でオーディオフォーマットを構築
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)

            val deviceName = VoiceConfig.selectedMicrophoneDevice
            val mixerInfo = AudioDeviceUtils.getMixerInfoByName(deviceName)

            line = if (mixerInfo != null) {
                val mixer = AudioSystem.getMixer(mixerInfo)
                mixer.getLine(info) as TargetDataLine
            } else {
                AudioSystem.getLine(info) as TargetDataLine
            }

            // バッファサイズを 2フレーム分 (40ms) に抑えて捕捉遅延を最小化
            // 大きくすると OSドライバーが溜まるまで待つため遅延が増える
            line?.open(format, frameSizeBytes * 2)
            line?.start()
            recording = true
            pcmQueue.clear()

            captureThread = Thread(this::captureLoop, "ModernVoiceChat-MicCaptureThread").apply {
                isDaemon = true
                start()
            }

            logger.info("AudioRecorder started successfully (Mic: $deviceName, FrameSizeBytes: $frameSizeBytes)")
            return true
        } catch (e: Exception) {
            logger.error("Failed to start AudioRecorder", e)
            stopInternal()
            return false
        }
    }

    private fun captureLoop() {
        val targetLine = line ?: return
        val byteBuffer = ByteArray(frameSizeBytes)

        while (recording && !Thread.currentThread().isInterrupted) {
            try {
                var readBytes = 0
                // 正確に 1 フレーム分 (1920 バイト) を読み出す
                while (readBytes < frameSizeBytes && recording) {
                    val count = targetLine.read(byteBuffer, readBytes, frameSizeBytes - readBytes)
                    if (count < 0) break
                    readBytes += count
                }

                if (readBytes < frameSizeBytes) continue

                // 符号拡張バグを回避するため、ByteBuffer + LITTLE_ENDIAN で型安全に Short 配列へパース
                val pcm = ShortArray(frameSizeSamples)
                ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)

                // VoiceConfig.micVolumePercentage を PCM サンプルに適用
                val micGain = VoiceConfig.micVolumePercentage / 100.0
                if (micGain != 1.0) {
                    for (i in pcm.indices) {
                        val scaled = (pcm[i] * micGain).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                        pcm[i] = scaled.toInt().toShort()
                    }
                }

                var sumSquares = 0.0
                for (sample in pcm) {
                    val normalized = sample / 32768.0
                    sumSquares += normalized * normalized
                }

                val rms = Math.sqrt(sumSquares / frameSizeSamples)
                currentRmsPercentage = ((rms / 0.05) * 100.0).coerceIn(0.0, 100.0).toInt()

                val threshold = (VoiceConfig.vadThresholdPercentage / 1000.0)
                val isSpeaking = rms >= threshold

                val element = Pair(pcm, isSpeaking)

                while (!pcmQueue.offer(element)) {
                    pcmQueue.poll()
                }

            } catch (e: Exception) {
                if (!recording) break
                logger.error("Error in AudioRecorder capture loop: ${e.message}")
            }
        }
    }

    fun readFrame(timeoutMs: Long = 0): Pair<ShortArray, Boolean>? {
        return if (timeoutMs > 0) {
            pcmQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            pcmQueue.poll()
        }
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        recording = false
        captureThread?.interrupt()
        captureThread = null

        try {
            line?.stop()
            line?.close()
        } catch (e: Exception) {
            logger.error("Error closing target data line", e)
        }
        line = null
        pcmQueue.clear()
        currentRmsPercentage = 0
        logger.info("AudioRecorder stopped cleanly")
    }
}
