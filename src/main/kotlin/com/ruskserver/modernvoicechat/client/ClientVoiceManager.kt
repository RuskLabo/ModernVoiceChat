package com.ruskserver.modernvoicechat.client

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
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    // マイクテスト用の専用ループバックスレッド（ClientTickに依存しない20ms cadence）
    @Volatile private var loopbackThread: Thread? = null
    @Volatile private var loopbackRunning = false

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

    fun startLoopback() {
        if (loopbackRunning) return
        stopLoopback()

        if (recorder == null) {
            recorder = AudioRecorder(48000, 960)
            recorder?.start()
        }
        if (player == null) {
            player = AudioPlayer(48000)
        }

        loopbackRunning = true
        loopbackThread = Thread({
            val localRecorder = recorder ?: return@Thread
            val localPlayer = player ?: return@Thread

            logger.info("Mic loopback thread started")
            while (loopbackRunning && !Thread.currentThread().isInterrupted) {
                try {
                    // 20ms タイムアウト付きで取り出す（キャプチャと同じ cadence で待機）
                    val framePair = localRecorder.readFrame(timeoutMs = 30L)

                    if (framePair != null) {
                        val (pcm, isSpeaking) = framePair

                        val isPttPressed = KeyMappings.PUSH_TO_TALK.isDown
                        val shouldPlay = when (VoiceConfig.inputMode) {
                            VoiceConfig.InputMode.PUSH_TO_TALK -> isPttPressed
                            VoiceConfig.InputMode.VOICE_ACTIVATION -> isSpeaking
                        }

                        VoiceHudOverlay.isSpeakingCurrent = shouldPlay

                        if (shouldPlay) {
                            // ローカルプレイヤーのUUIDで自分自身のスピーカーへ再生
                            val mc = Minecraft.getInstance()
                            val lp = mc.player
                            if (lp != null) {
                                localPlayer.playAudio(lp.uuid, pcm, lp.x, lp.y, lp.z)
                            }
                        }
                    } else {
                        VoiceHudOverlay.isSpeakingCurrent = false
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
