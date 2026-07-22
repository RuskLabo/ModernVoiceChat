package com.ruskserver.modernvoicechat.audio

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

class AudioPlayer(
    val sampleRate: Int = 48000
) {
    private val logger = LoggerFactory.getLogger(AudioPlayer::class.java)
    private val playerLines = ConcurrentHashMap<UUID, SourceDataLine>()

    // 再生は専用スレッドキューで行い、呼び出し元スレッドとのレースコンディションを排除する
    private data class PlaybackJob(val uuid: UUID, val rawBytes: ByteArray, val volume: Double)
    private val playbackQueue = ArrayBlockingQueue<PlaybackJob>(10)
    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ModernVoiceChat-AudioPlaybackThread").also { it.isDaemon = true }
    }

    init {
        playbackExecutor.execute {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val job = playbackQueue.poll(5, TimeUnit.MILLISECONDS) ?: continue
                    writeToLine(job)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Error in audio playback thread: ${e.message}")
                }
            }
        }
    }

    private val radioFilter = com.ruskserver.modernvoicechat.audio.dsp.RadioAudioFilter(sampleRate)

    fun playAudio(playerUuid: UUID, pcm: ShortArray, posX: Double, posY: Double, posZ: Double, isRadio: Boolean = false, quality: Float = 1.0f) {
        if (VoiceConfig.isSpeakerMuted) return

        val processedPcm = if (isRadio) {
            radioFilter.process(pcm, quality)
        } else {
            pcm
        }

        val volumeMultiplier = VoiceConfig.speakerVolumePercentage / 100.0
        val byteBuffer = ByteBuffer.allocate(processedPcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in processedPcm) {
            val scaledSample = (sample * volumeMultiplier).coerceIn(-32768.0, 32767.0).toInt().toShort()
            byteBuffer.putShort(scaledSample)
        }

        // バッファが溢れている場合は古い再生ジョブを捨てて最新を優先
        if (playbackQueue.remainingCapacity() == 0) {
            playbackQueue.poll()
        }
        playbackQueue.offer(PlaybackJob(playerUuid, byteBuffer.array(), volumeMultiplier))
    }

    private fun writeToLine(job: PlaybackJob) {
        var line = playerLines[job.uuid]
        if (line == null) {
            val newLine = createLine() ?: return
            val existing = playerLines.putIfAbsent(job.uuid, newLine)
            line = existing ?: newLine
        }

        try {
            // flush() は使わない。write() はブロッキングで正確にデータ量だけ書き込む。
            // バッファサイズを十分大きく (200ms) 取っているためオーバーフローしない。
            line.write(job.rawBytes, 0, job.rawBytes.size)
        } catch (e: Exception) {
            logger.error("Error writing audio line for player ${job.uuid}: ${e.message}")
            // ラインが壊れた場合はクローズして次回再作成させる
            playerLines.remove(job.uuid)
            try { line.close() } catch (_: Exception) {}
        }
    }

    private fun createLine(): SourceDataLine? {
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

            // バッファを 60ms 分に設定（低遅延を保ちつつアンダーラン防止）
            // 60ms = 3フレーム (960 samples * 2 bytes * 3 frames = 5760 bytes)
            val bufferSize = 960 * 2 * 3  // 5760 bytes = 60ms @ 48kHz mono 16bit
            line.open(format, bufferSize)
            line.start()
            logger.info("AudioPlayer: created playback line (buffer: ${bufferSize}B, device: $deviceName)")
            line
        } catch (e: Exception) {
            logger.error("Failed to create SourceDataLine for player", e)
            null
        }
    }

    fun stopPlayer(playerUuid: UUID) {
        val line = playerLines.remove(playerUuid)
        line?.drain()
        line?.stop()
        line?.close()
    }

    fun stopAll() {
        playbackExecutor.shutdownNow()
        playbackQueue.clear()
        for ((_, line) in playerLines) {
            try {
                line.drain()
                line.stop()
                line.close()
            } catch (_: Exception) {}
        }
        playerLines.clear()
    }
}
