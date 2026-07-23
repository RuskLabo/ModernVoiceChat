package com.ruskserver.modernvoicechat.audio

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * 3D 空間パニング、距離減衰、空気吸収フィルタ、ソフトウェアミキシングを備えた
 * Pure Java 3D 空間ステレオオーディオエンジン。
 */
class AudioPlayer(
    val sampleRate: Int = 48000
) {
    private val logger = LoggerFactory.getLogger(AudioPlayer::class.java)

    @Volatile private var masterLine: SourceDataLine? = null

    data class AudioFrame(
        val pcm: ShortArray,
        val posX: Double,
        val posY: Double,
        val posZ: Double,
        val isRadio: Boolean,
        val quality: Float
    )

    private val activeBuffers = ConcurrentHashMap<UUID, ConcurrentHashMap<Long, AudioFrame>>()
    private val playerRadioFilters = ConcurrentHashMap<UUID, com.ruskserver.modernvoicechat.audio.dsp.RadioAudioFilter>()

    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ModernVoiceChat-AudioPlaybackThread").also { it.isDaemon = true }
    }

    init {
        initMasterLine()

        playbackExecutor.execute {
            val frameSizeSamples = 960 // 20ms @ 48kHz
            // 2チャンネル (Stereo: L/R Interleaved)
            val mixedPcmL = FloatArray(frameSizeSamples)
            val mixedPcmR = FloatArray(frameSizeSamples)
            val outputBytes = ByteArray(frameSizeSamples * 2 * 2) // 960 * 2ch * 2bytes = 3840 bytes

            while (!Thread.currentThread().isInterrupted) {
                try {
                    val line = getOrCreateMasterLine()
                    if (line == null) {
                        Thread.sleep(20)
                        continue
                    }

                    mixedPcmL.fill(0.0f)
                    mixedPcmR.fill(0.0f)
                    var hasAudio = false

                    // リスナー (ローカルプレイヤー) の位置と頭の向き (Yaw) を取得
                    val mc = Minecraft.getInstance()
                    val listener = mc.player
                    val listenerX = listener?.x ?: 0.0
                    val listenerY = listener?.y ?: 0.0
                    val listenerZ = listener?.z ?: 0.0
                    val listenerYaw = listener?.yRot ?: 0.0f

                    // プレイヤーの視線方向単位ベクトルの算出 (Minecraft の Yaw: 0=南, 90=西, 180=北, 270=東)
                    val radYaw = Math.toRadians(listenerYaw.toDouble())
                    val forwardX = -sin(radYaw)
                    val forwardZ = cos(radYaw)
                    // 右方向ベクトル (Forward ベクトルの 90度回転)
                    val rightX = cos(radYaw)
                    val rightZ = sin(radYaw)

                    val iterator = activeBuffers.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val playerUuid = entry.key
                        val framesMap = entry.value

                        if (framesMap.isEmpty()) continue

                        val oldestSeq = framesMap.keys.minOrNull() ?: continue
                        val frame = framesMap.remove(oldestSeq) ?: continue

                        val playerVol = VoiceConfig.getPlayerVolume(playerUuid) / 100.0
                        val globalVol = VoiceConfig.speakerVolumePercentage / 100.0
                        val baseVol = (globalVol * playerVol).toFloat()

                        var leftGain = baseVol
                        var rightGain = baseVol

                        if (!frame.isRadio) {
                            // 通常空間ボイス: 3D 空間減衰 & ステレオパニング計算
                            val dx = frame.posX - listenerX
                            val dy = frame.posY - listenerY
                            val dz = frame.posZ - listenerZ
                            val dist = sqrt(dx * dx + dy * dy + dz * dz)

                            // 距離減衰: 2m ～ ServerConfig.VOICE_RANGE の間でスライディング減衰
                            val maxDist = try {
                                com.ruskserver.modernvoicechat.config.ServerConfig.VOICE_RANGE.get()
                            } catch (_: Throwable) {
                                24.0
                            }
                            val minDist = 2.0
                            val distFactor = when {
                                dist <= minDist -> 1.0f
                                dist >= maxDist -> 0.0f
                                else -> ((maxDist - dist) / (maxDist - minDist)).toFloat().coerceIn(0.0f, 1.0f)
                            }

                            if (distFactor <= 0.0f) continue

                            // 相対位置の右方向への射影 (パニング)
                            val dotRight = (dx * rightX + dz * rightZ)
                            val pan = (dotRight / (dist + 0.001)).coerceIn(-1.0, 1.0).toFloat()

                            // 定パワーパニング (Constant Power Stereo Panning)
                            // pan = -1.0 (左100%), 0.0 (中央50/50), 1.0 (右100%)
                            val angle = (pan + 1.0f) * (Math.PI.toFloat() / 4.0f) // 0 ～ PI/2
                            leftGain = baseVol * distFactor * cos(angle)
                            rightGain = baseVol * distFactor * sin(angle)
                        }

                        val pcm = frame.pcm
                        for (i in pcm.indices) {
                            val sample = pcm[i]
                            mixedPcmL[i] += sample * leftGain
                            mixedPcmR[i] += sample * rightGain
                        }
                        hasAudio = true
                    }

                    if (hasAudio) {
                        val bb = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
                        bb.clear()
                        for (i in 0 until frameSizeSamples) {
                            val sampleL = mixedPcmL[i].toInt().coerceIn(-32768, 32767).toShort()
                            val sampleR = mixedPcmR[i].toInt().coerceIn(-32768, 32767).toShort()
                            bb.putShort(sampleL)
                            bb.putShort(sampleR)
                        }
                        line.write(outputBytes, 0, outputBytes.size)
                    } else {
                        Thread.sleep(10)
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Error in 3D audio playback thread: ${e.message}")
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
            playerRadioFilters[playerUuid]?.resetState()
            pcm
        }

        val playerFrames = activeBuffers.computeIfAbsent(playerUuid) { ConcurrentHashMap() }
        if (playerFrames.size > 5) {
            playerFrames.clear()
        }
        playerFrames[System.nanoTime()] = AudioFrame(processedPcm, posX, posY, posZ, isRadio, quality)
    }

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
            // 48kHz, 16bit, 2 channels (Stereo), Signed, Little-Endian
            val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)

            val deviceName = VoiceConfig.selectedSpeakerDevice
            val mixerInfo = AudioDeviceUtils.getMixerInfoByName(deviceName)

            val line = if (mixerInfo != null) {
                val mixer = AudioSystem.getMixer(mixerInfo)
                mixer.getLine(info) as SourceDataLine
            } else {
                AudioSystem.getLine(info) as SourceDataLine
            }

            // バッファを 4フレーム分 (80ms: 1920 * 2 * 4 = 15360 バイト) 確保
            val bufferSize = 960 * 2 * 2 * 4
            line.open(format, bufferSize)
            line.start()
            masterLine = line
            logger.info("AudioPlayer: Master 3D Stereo playback line created successfully (device: $deviceName)")
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

