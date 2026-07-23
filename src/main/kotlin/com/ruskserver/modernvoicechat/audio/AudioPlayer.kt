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

        val globalVolume = VoiceConfig.speakerVolumePercentage / 100.0
        val playerVolume = VoiceConfig.getPlayerVolume(playerUuid) / 100.0
        val combinedMultiplier = globalVolume * playerVolume

        val byteBuffer = ByteBuffer.allocate(processedPcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in processedPcm) {
            val scaledSample = (sample * combinedMultiplier).coerceIn(-32768.0, 32767.0).toInt().toShort()
            byteBuffer.putShort(scaledSample)
        }

        // バッファが溢れている場合は古い再生ジョブを捨てて最新を優先
        if (playbackQueue.remainingCapacity() == 0) {
            playbackQueue.poll()
        }
        playbackQueue.offer(PlaybackJob(playerUuid, byteBuffer.array(), combinedMultiplier))
    }

    private fun writeToLine(job: PlaybackJob) {
        var line = playerLines[job.uuid]
        if (line == null) {
            val newLine = createLine() ?: return
            val existing = playerLines.putIfAbsent(job.uuid, newLine)
            line = existing ?: newLine
        }

        try {
            line.write(job.rawBytes, 0, job.rawBytes.size)
        } catch (e: Exception) {
            logger.error("Error writing audio line for player ${job.uuid}: ${e.message}")
            playerLines.remove(job.uuid)
            try { line.close() } catch (_: Exception) {}
        }
    }

    /**
     * キューを経由せずに SourceDataLine へ直接書き込む最低遅延パス。
     * ループバックマイクテスト専用。通常のネットワーク再生には playAudio() を使う。
     *
     * OSのバッファ蓄積による遅延とオーバーフロークリックノイズを防ぐため、
     * 書き込み前に available() をチェックし、バッファが詰まっている場合は
     * flush() でリセットしてから書き込む。
     */
    fun playAudioDirect(playerUuid: UUID, pcm: ShortArray) {
        if (VoiceConfig.isSpeakerMuted) return

        val volumeMultiplier = VoiceConfig.speakerVolumePercentage / 100.0
        val byteBuffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) {
            val scaledSample = (sample * volumeMultiplier).coerceIn(-32768.0, 32767.0).toInt().toShort()
            byteBuffer.putShort(scaledSample)
        }
        val rawBytes = byteBuffer.array()
        val frameSize = rawBytes.size

        var line = playerLines[playerUuid]
        if (line == null) {
            val newLine = createLine() ?: return
            val existing = playerLines.putIfAbsent(playerUuid, newLine)
            line = existing ?: newLine
        }

        try {
            // available() はバッファ内の残余容量(書き込み可能バイト数)を返す。
            // フレーム1枚分の空きがなければ、OSバッファに古い音声が溜まっている → flush() でリセット。
            // これにより 0.5s 遅延と溢れ時のクリックノイズを防止する。
            if (line.available() < frameSize) {
                line.flush()
            }
            line.write(rawBytes, 0, frameSize)
        } catch (e: Exception) {
            logger.error("Error in direct audio write for player $playerUuid: ${e.message}")
            playerLines.remove(playerUuid)
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

            // バッファを 20ms (1フレーム) に削減して入力遅延を最小化
            // アンダーランが発生する場合は 2 (40ms) に戻す
            val bufferSize = 960 * 2 * 1  // 1920 bytes = 20ms @ 48kHz mono 16bit
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
