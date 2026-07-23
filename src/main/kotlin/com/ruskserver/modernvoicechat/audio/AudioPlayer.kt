package com.ruskserver.modernvoicechat.audio

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * ソフトウェアミキシングを備えた単一出力オーディオプレイヤー。
 * 同一スピーカーデバイスへの複数 SourceDataLine オープン競合を防ぎ、
 * 全プレイヤーの音声を1つの出力ラインで安全にミックス再生する。
 */
class AudioPlayer(
    val sampleRate: Int = 48000
) {
    private val logger = LoggerFactory.getLogger(AudioPlayer::class.java)

    @Volatile private var masterLine: SourceDataLine? = null
    private val activeBuffers = ConcurrentHashMap<UUID, ConcurrentHashMap<Long, ShortArray>>()

    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ModernVoiceChat-AudioPlaybackThread").also { it.isDaemon = true }
    }

    private val playerRadioFilters = ConcurrentHashMap<UUID, com.ruskserver.modernvoicechat.audio.dsp.RadioAudioFilter>()

    init {
        initMasterLine()

        playbackExecutor.execute {
            val frameSizeSamples = 960 // 20ms @ 48kHz
            val frameSizeBytes = frameSizeSamples * 2
            val mixedPcm = IntArray(frameSizeSamples)
            val outputBytes = ByteArray(frameSizeBytes)

            while (!Thread.currentThread().isInterrupted) {
                try {
                    val line = getOrCreateMasterLine()
                    if (line == null) {
                        Thread.sleep(20)
                        continue
                    }

                    mixedPcm.fill(0)
                    var hasAudio = false

                    // アクティブな全プレイヤーの PCM を単一バッファへ合成（ミキシング）
                    val iterator = activeBuffers.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val playerUuid = entry.key
                        val framesMap = entry.value

                        if (framesMap.isEmpty()) continue

                        // 最も古いフレームを1つ取得
                        val oldestSeq = framesMap.keys.minOrNull() ?: continue
                        val pcm = framesMap.remove(oldestSeq) ?: continue

                        val playerVol = VoiceConfig.getPlayerVolume(playerUuid) / 100.0
                        val globalVol = VoiceConfig.speakerVolumePercentage / 100.0
                        val combinedVol = globalVol * playerVol

                        for (i in pcm.indices) {
                            mixedPcm[i] += (pcm[i] * combinedVol).toInt()
                        }
                        hasAudio = true
                    }

                    if (hasAudio) {
                        val bb = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
                        bb.clear()
                        for (sampleInt in mixedPcm) {
                            val clamped = sampleInt.coerceIn(-32768, 32767).toShort()
                            bb.putShort(clamped)
                        }
                        line.write(outputBytes, 0, frameSizeBytes)
                    } else {
                        // 有音データがないときは短時間休止
                        Thread.sleep(10)
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Error in audio playback thread: ${e.message}")
                }
            }
        }
    }

    fun playAudio(playerUuid: UUID, pcm: ShortArray, posX: Double, posY: Double, posZ: Double, isRadio: Boolean = false, quality: Float = 1.0f) {
        if (VoiceConfig.isSpeakerMuted) return

        val processedPcm = if (isRadio) {
            val filter = playerRadioFilters.computeIfAbsent(playerUuid) {
                com.ruskserver.modernvoicechat.audio.dsp.RadioAudioFilter(sampleRate)
            }
            filter.process(pcm, quality)
        } else {
            // 無線以外への切り替え時はフィルタ状態をクリア
            playerRadioFilters[playerUuid]?.resetState()
            pcm
        }

        val playerFrames = activeBuffers.computeIfAbsent(playerUuid) { ConcurrentHashMap() }
        // 滞留を防止するためバッファが大きすぎる場合はリセット
        if (playerFrames.size > 5) {
            playerFrames.clear()
        }
        playerFrames[System.nanoTime()] = processedPcm
    }

    /**
     * ループバック専用のダイレクト再生
     */
    fun playAudioDirect(playerUuid: UUID, pcm: ShortArray) {
        playAudio(playerUuid, pcm, 0.0, 0.0, 0.0, false, 1.0f)
    }

    @Synchronized
    private fun getOrCreateMasterLine(): SourceDataLine? {
        if (masterLine != null && masterLine!!.isOpen) return masterLine
        return initMasterLine()
    }

    @Synchronized
    private fun initMasterLine(): SourceDataLine? {
        return try {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)

            val deviceName = VoiceConfig.selectedSpeakerDevice
            val mixerInfo = AudioDeviceUtils.getMixerInfoByName(deviceName)

            val line = if (mixerInfo != null) {
                val mixer = AudioSystem.getMixer(mixerInfo)
                mixer.getLine(info) as SourceDataLine
            } else {
                AudioSystem.getLine(info) as SourceDataLine
            }

            // バッファを 4フレーム分 (80ms) 確保
            val bufferSize = 960 * 2 * 4
            line.open(format, bufferSize)
            line.start()
            masterLine = line
            logger.info("AudioPlayer: Master playback line created successfully (device: $deviceName)")
            line
        } catch (e: Exception) {
            logger.error("Failed to create Master SourceDataLine for AudioPlayer", e)
            null
        }
    }

    fun stopPlayer(playerUuid: UUID) {
        activeBuffers.remove(playerUuid)
        playerRadioFilters.remove(playerUuid)
    }

    fun stopAll() {
        playbackExecutor.shutdownNow()
        activeBuffers.clear()
        playerRadioFilters.clear()
        try {
            masterLine?.stop()
            masterLine?.close()
        } catch (_: Exception) {}
        masterLine = null
    }
}
