package com.ruskserver.modernvoicechat.client

import com.ruskserver.modernvoicechat.audio.AudioDeviceUtils
import com.ruskserver.modernvoicechat.audio.AudioPlayer
import com.ruskserver.modernvoicechat.audio.AudioRecorder
import com.ruskserver.modernvoicechat.audio.OpusDecoderWrapper
import com.ruskserver.modernvoicechat.audio.OpusEncoderWrapper
import com.ruskserver.modernvoicechat.audio.adaptation.DynamicBitrateAdaptor
import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import com.ruskserver.modernvoicechat.client.gui.NameTagIcons
import com.ruskserver.modernvoicechat.client.gui.VoiceHudOverlay
import com.ruskserver.modernvoicechat.network.ModNetwork
import com.ruskserver.modernvoicechat.transport.QuicVoiceClient
import com.ruskserver.modernvoicechat.transport.VoicePacket
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

object ClientVoiceManager {
    private val logger = LoggerFactory.getLogger(ClientVoiceManager::class.java)

    var voiceClient: QuicVoiceClient? = null
    var recorder: AudioRecorder? = null
    var player: AudioPlayer? = null
    var encoder: OpusEncoderWrapper? = null
    var decoder: OpusDecoderWrapper? = null
    var adaptor: DynamicBitrateAdaptor? = null

    var isConnected = false
        private set

    // マイクテスト用の専用ループバックスレッド（TargetDataLine→SourceDataLine 直結コピー方式）
    @Volatile private var loopbackThread: Thread? = null
    @Volatile private var loopbackRunning = false
    private var loopbackSourceLine: SourceDataLine? = null
    private var loopbackTargetLine: TargetDataLine? = null

    fun connect(voicePort: Int, voiceHost: String = "", secretToken: UUID) {
        val mc = Minecraft.getInstance()
        val localPlayer = mc.player ?: return
        val serverData = mc.currentServer ?: return

        disconnect()

        val targetHost = if (voiceHost.isNotBlank()) {
            voiceHost.trim()
        } else {
            serverData.ip.split(":")[0]
        }
        val serverAddress = InetSocketAddress(targetHost, voicePort)

        encoder = OpusEncoderWrapper(48000, 1, 32000)
        decoder = OpusDecoderWrapper(48000, 1)
        adaptor = DynamicBitrateAdaptor(encoder!!)

        voiceClient = QuicVoiceClient(localPlayer.uuid, serverAddress, adaptor)
        recorder = AudioRecorder(48000, 960)
        player = AudioPlayer(48000)

        voiceClient?.setPacketListener { packet ->
            handleIncomingPacket(packet)
        }

        recorder?.start()
        voiceClient?.start()
        isConnected = true
        logger.info("ClientVoiceManager connected to voice server (port: ${serverAddress.port})")

        PacketDistributor.sendToServer(ModNetwork.C2SVoiceSecretPayload(secretToken))
        voiceClient?.sendHandshake(localPlayer.x, localPlayer.y, localPlayer.z)
    }

    private fun handleIncomingPacket(packet: VoicePacket) {
        val pcm = decoder?.decode(packet.opusData, 960) ?: return
        player?.playAudio(packet.senderUuid, pcm, packet.posX, packet.posY, packet.posZ, packet.isRadio, packet.quality)
        NameTagIcons.setPlayerSpeaking(packet.senderUuid, true)
    }

    // ===========================================================================
    // マイクテスト用専用ループバックスレッドの起動・停止
    // ClientTickから完全に独立し、20ms cadence でキャプチャ→再生をループする
    // ===========================================================================

    /**
     * マイクテスト用ループバック。
     * TargetDataLine (マイク) → SourceDataLine (スピーカー) を直接バイトコピーする方式。
     * AudioRecorder/AudioPlayer のキュー・バッファを一切通らないため最低遅延でクリック音なし。
     * 話している間だけ有音PCMを書き込み、無音時はサイレントフレームを書き込んで
     * SourceDataLine のアンダーランを防ぐ。
     */
    fun startLoopback() {
        if (loopbackRunning) return
        stopLoopback()

        val format = AudioFormat(48000f, 16, 1, true, false)
        val frameSizeBytes = 960 * 2  // 20ms @ 48kHz mono 16bit
        val silentFrame = ByteArray(frameSizeBytes)

        // TargetDataLine (マイク入力)
        val micDeviceName = VoiceConfig.selectedMicrophoneDevice
        val micMixerInfo = AudioDeviceUtils.getMixerInfoByName(micDeviceName)
        val targetInfo = DataLine.Info(TargetDataLine::class.java, format)
        val tLine = try {
            val l = if (micMixerInfo != null) {
                AudioSystem.getMixer(micMixerInfo).getLine(targetInfo) as TargetDataLine
            } else {
                AudioSystem.getLine(targetInfo) as TargetDataLine
            }
            // 1フレーム分のバッファで最低遅延キャプチャ
            l.open(format, frameSizeBytes)
            l.start()
            l
        } catch (e: Exception) {
            logger.error("Loopback: failed to open mic line", e)
            return
        }

        // SourceDataLine (スピーカー出力)
        val spkDeviceName = VoiceConfig.selectedSpeakerDevice
        val spkMixerInfo = AudioDeviceUtils.getMixerInfoByName(spkDeviceName)
        val sourceInfo = DataLine.Info(SourceDataLine::class.java, format)
        val sLine = try {
            val l = if (spkMixerInfo != null) {
                AudioSystem.getMixer(spkMixerInfo).getLine(sourceInfo) as SourceDataLine
            } else {
                AudioSystem.getLine(sourceInfo) as SourceDataLine
            }
            // 2フレーム分のバッファ (40ms) でアンダーランを防止しつつ遅延を抑える
            l.open(format, frameSizeBytes * 2)
            l.start()
            l
        } catch (e: Exception) {
            logger.error("Loopback: failed to open speaker line", e)
            tLine.stop(); tLine.close()
            return
        }

        loopbackTargetLine = tLine
        loopbackSourceLine = sLine
        loopbackRunning = true

        loopbackThread = Thread({
            logger.info("Mic loopback thread started (direct copy mode)")
            val buf = ByteArray(frameSizeBytes)

            while (loopbackRunning && !Thread.currentThread().isInterrupted) {
                try {
                    // 正確に 1 フレーム分を TargetDataLine から読み出す
                    var readTotal = 0
                    while (readTotal < frameSizeBytes && loopbackRunning) {
                        val n = tLine.read(buf, readTotal, frameSizeBytes - readTotal)
                        if (n < 0) break
                        readTotal += n
                    }
                    if (readTotal < frameSizeBytes) continue

                    // マイクゲインを適用して PCM を Short 配列に変換
                    val micGain = VoiceConfig.micVolumePercentage / 100.0
                    val spkGain = VoiceConfig.speakerVolumePercentage / 100.0
                    val combinedGain = micGain * spkGain
                    val outBuf = if (combinedGain != 1.0) {
                        val bb = ByteBuffer.wrap(buf.copyOf()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val pcm = ShortArray(960).also { bb.get(it) }
                        val out = ByteBuffer.allocate(frameSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (s in pcm) {
                            val scaled = (s * combinedGain).coerceIn(-32768.0, 32767.0).toInt().toShort()
                            out.putShort(scaled)
                        }
                        out.array()
                    } else buf

                    // RMS を計算して発話判定
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val pcm = ShortArray(960).also { bb.get(it) }
                    var sumSq = 0.0
                    for (s in pcm) sumSq += (s / 32768.0).let { it * it }
                    val rms = Math.sqrt(sumSq / 960)
                    val threshold = VoiceConfig.vadThresholdPercentage / 1000.0

                    val isPttPressed = KeyMappings.PUSH_TO_TALK.isDown
                    val isSpeaking = rms >= threshold
                    val shouldPlay = when (VoiceConfig.inputMode) {
                        VoiceConfig.InputMode.PUSH_TO_TALK -> isPttPressed
                        VoiceConfig.InputMode.VOICE_ACTIVATION -> isSpeaking
                    }

                    VoiceHudOverlay.isSpeakingCurrent = shouldPlay

                    // 話している間は有音フレーム、無音時はサイレントフレームを書き込む
                    // サイレントフレームを書かないとアンダーランでプツプツ鳴る
                    sLine.write(if (shouldPlay) outBuf else silentFrame, 0, frameSizeBytes)

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!loopbackRunning) break
                    logger.error("Error in loopback thread: ${e.message}")
                }
            }

            VoiceHudOverlay.isSpeakingCurrent = false
            logger.info("Mic loopback thread stopped")
        }, "ModernVoiceChat-LoopbackThread").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stopLoopback() {
        loopbackRunning = false
        loopbackThread?.interrupt()
        loopbackThread = null
        try { loopbackSourceLine?.stop(); loopbackSourceLine?.close() } catch (_: Exception) {}
        try { loopbackTargetLine?.stop(); loopbackTargetLine?.close() } catch (_: Exception) {}
        loopbackSourceLine = null
        loopbackTargetLine = null
        VoiceHudOverlay.isSpeakingCurrent = false
    }

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val localPlayer = mc.player ?: return

        // 1. マイクテストモードの管理（ループバックは専用スレッドが担当）
        if (VoiceConfig.isMicTestingEnabled) {
            if (!loopbackRunning) startLoopback()
            return
        } else {
            if (loopbackRunning) stopLoopback()
        }

        // 2. 本番マルチサーバー送信（ClientTickで全フレームをネットワーク送信）
        if (!isConnected || voiceClient == null) return

        if (VoiceConfig.isMicMuted) {
            VoiceHudOverlay.isSpeakingCurrent = false
            return
        }

        val isPttPressed = KeyMappings.PUSH_TO_TALK.isDown
        val isHoldingRadio = localPlayer.isUsingItem && localPlayer.useItem.item is com.ruskserver.modernvoicechat.item.RadioItem
        var anySpeaking = false

        // ネットワーク送信はキューを全消費してOK
        while (true) {
            val framePair = recorder?.readFrame() ?: break
            val (pcm, isSpeaking) = framePair

            val isVoiceDetected = when (VoiceConfig.inputMode) {
                VoiceConfig.InputMode.PUSH_TO_TALK -> isPttPressed
                VoiceConfig.InputMode.VOICE_ACTIVATION -> isSpeaking
            }

            // 無線機長押し中であっても、実際に声を発話した時 (isSpeaking / PTT) のみ送信・Talking状態にする
            val shouldTransmit = if (isHoldingRadio) {
                isSpeaking || isPttPressed
            } else {
                isVoiceDetected
            }

            if (shouldTransmit) {
                anySpeaking = true
                val encodedOpus = encoder?.encode(pcm, 960)
                if (encodedOpus != null && encodedOpus.isNotEmpty()) {
                    // 無線機持続長押し中であれば isRadio パケットを優先送信
                    val packet = VoicePacket(
                        senderUuid = localPlayer.uuid,
                        sequenceNumber = System.currentTimeMillis(),
                        opusData = encodedOpus,
                        posX = localPlayer.x,
                        posY = localPlayer.y,
                        posZ = localPlayer.z,
                        isRadio = isHoldingRadio,
                        quality = 1.0f
                    )
                    voiceClient?.sendHandler?.invoke(packet)
                }
            }
        }

        VoiceHudOverlay.isSpeakingCurrent = anySpeaking
    }

    @SubscribeEvent
    fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        disconnect()
    }

    fun disconnect() {
        stopLoopback()
        if (!isConnected) {
            // テストのみ使用時のリソース解放
            recorder?.stop()
            player?.stopAll()
            recorder = null
            player = null
            return
        }
        voiceClient?.stop()
        recorder?.stop()
        player?.stopAll()

        voiceClient = null
        recorder = null
        player = null
        encoder = null
        decoder = null
        adaptor = null
        isConnected = false
        VoiceHudOverlay.isSpeakingCurrent = false
        logger.info("ClientVoiceManager disconnected")
    }
}
