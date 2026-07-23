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

    // マイクテスト用の専用ループバックスレッド（AudioRecorder流用 + 専用SourceDataLine直書き方式）
    @Volatile private var loopbackThread: Thread? = null
    @Volatile private var loopbackRunning = false
    private var loopbackSourceLine: SourceDataLine? = null

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
        if (packet.opusData.isEmpty()) return
        val pcm = decoder?.decode(packet.opusData, 960) ?: return
        player?.playAudio(packet.senderUuid, pcm, packet.posX, packet.posY, packet.posZ, packet.isRadio, packet.quality)
        NameTagIcons.setPlayerSpeaking(packet.senderUuid, true)
    }

    // ===========================================================================

    /**
     * マイクテスト用ループバック。
     *
     * 入力側: AudioRecorder を流用（既起動なら再利用、未起動なら新規起動）。
     *         新たに TargetDataLine を開くと既存 AudioRecorder と競合して
     *         LineUnavailableException になるため直接ラインは開かない。
     *
     * 出力側: AudioPlayer の playbackQueue を経由せず専用 SourceDataLine へ直書き。
     *         無音時はサイレントフレームを書き込んでアンダーランを防止する。
     */
    fun startLoopback() {
        if (loopbackRunning) return
        stopLoopback()

        // ---- 入力側: AudioRecorder を用意 ----
        val localRecorder: AudioRecorder
        val recorderOwnedByLoopback: Boolean
        if (recorder != null) {
            // 接続中は既存の AudioRecorder を再利用（デバイス競合を回避）
            localRecorder = recorder!!
            recorderOwnedByLoopback = false
            logger.info("Loopback: reusing existing AudioRecorder")
        } else {
            // 未接続時は専用 AudioRecorder を新規起動
            val r = AudioRecorder(48000, 960)
            if (!r.start()) {
                logger.error("Loopback: failed to start AudioRecorder")
                return
            }
            recorder = r
            localRecorder = r
            recorderOwnedByLoopback = true
            logger.info("Loopback: started new AudioRecorder")
        }

        // ---- 出力側: 専用 SourceDataLine を開く ----
        val format = AudioFormat(48000f, 16, 1, true, false)
        val frameSizeBytes = 960 * 2  // 20ms @ 48kHz mono 16bit
        val silentFrame = ByteArray(frameSizeBytes)

        val spkDeviceName = VoiceConfig.selectedSpeakerDevice
        val spkMixerInfo = AudioDeviceUtils.getMixerInfoByName(spkDeviceName)
        val sourceInfo = DataLine.Info(SourceDataLine::class.java, format)
        val sLine = try {
            val l = if (spkMixerInfo != null) {
                AudioSystem.getMixer(spkMixerInfo).getLine(sourceInfo) as SourceDataLine
            } else {
                AudioSystem.getLine(sourceInfo) as SourceDataLine
            }
            // 4フレーム分 (80ms) でアンダーランを防止
            // OSの最小バッファ要件を満たしつつ遅延を抑える
            l.open(format, frameSizeBytes * 4)
            l.start()
            l
        } catch (e: Exception) {
            logger.error("Loopback: failed to open speaker line", e)
            if (recorderOwnedByLoopback) { recorder?.stop(); recorder = null }
            return
        }

        loopbackSourceLine = sLine
        loopbackRunning = true

        loopbackThread = Thread({
            logger.info("Mic loopback thread started (recorder+direct-line mode)")

            while (loopbackRunning && !Thread.currentThread().isInterrupted) {
                try {
                    // readFrame で 1 フレーム取得（最大 30ms 待機）
                    val framePair = localRecorder.readFrame(timeoutMs = 30L)

                    if (framePair != null) {
                        val (pcm, isSpeaking) = framePair

                        val isPttPressed = KeyMappings.PUSH_TO_TALK.isDown
                        val shouldPlay = when (VoiceConfig.inputMode) {
                            VoiceConfig.InputMode.PUSH_TO_TALK -> isPttPressed
                            VoiceConfig.InputMode.VOICE_ACTIVATION -> isSpeaking
                        }
                        VoiceHudOverlay.isSpeakingCurrent = shouldPlay

                        val spkGain = VoiceConfig.speakerVolumePercentage / 100.0
                        val outBytes = if (shouldPlay && spkGain != 1.0) {
                            val out = ByteBuffer.allocate(frameSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
                            for (s in pcm) {
                                out.putShort((s * spkGain).coerceIn(-32768.0, 32767.0).toInt().toShort())
                            }
                            out.array()
                        } else if (shouldPlay) {
                            // ゲイン 100% は変換不要：Short → bytes を直接変換
                            val out = ByteBuffer.allocate(frameSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
                            for (s in pcm) out.putShort(s)
                            out.array()
                        } else {
                            silentFrame
                        }

                        // 専用 SourceDataLine へ直書き（playbackQueue を経由しない）
                        sLine.write(outBytes, 0, frameSizeBytes)

                    } else {
                        // フレームが来なかった → サイレントフレームで埋めてアンダーランを防ぐ
                        VoiceHudOverlay.isSpeakingCurrent = false
                        sLine.write(silentFrame, 0, frameSizeBytes)
                    }

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
        loopbackSourceLine = null
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
            // ミュート中であっても 10 ticks (0.5秒) ごとに UDP キープアライブを送信して登録を維持
            if (mc.level != null && mc.level!!.gameTime % 10L == 0L) {
                sendKeepAlivePacket(localPlayer)
            }
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

        // 発話がない場合も 10 ticks (0.5秒) ごとに UDP キープアライブを送信してサーバーに受信先 IP:Port を常時登録
        if (!anySpeaking && mc.level != null && mc.level!!.gameTime % 10L == 0L) {
            sendKeepAlivePacket(localPlayer)
        }

        VoiceHudOverlay.isSpeakingCurrent = anySpeaking
    }

    private fun sendKeepAlivePacket(player: net.minecraft.client.player.LocalPlayer) {
        val keepAlivePacket = VoicePacket(
            senderUuid = player.uuid,
            sequenceNumber = System.currentTimeMillis(),
            opusData = ByteArray(0), // 空の Opus データ = キープアライブパケット
            posX = player.x,
            posY = player.y,
            posZ = player.z
        )
        voiceClient?.sendHandler?.invoke(keepAlivePacket)
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
