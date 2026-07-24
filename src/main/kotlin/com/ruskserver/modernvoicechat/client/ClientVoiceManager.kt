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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

object ClientVoiceManager {
    private val logger = LoggerFactory.getLogger(ClientVoiceManager::class.java)
    private val connectionExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ModernVoiceChat-Connection").apply { isDaemon = true }
    }
    private val connectionGeneration = AtomicLong()
    @Volatile private var transmitThread: Thread? = null
    @Volatile private var transmitRunning = false
    @Volatile private var transmitState = TransmitState()

    private data class TransmitState(
        val pttPressed: Boolean = false,
        val holdingRadio: Boolean = false,
        val muted: Boolean = true,
        val inputMode: VoiceConfig.InputMode = VoiceConfig.InputMode.VOICE_ACTIVATION,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val z: Double = 0.0
    )

    var voiceClient: QuicVoiceClient? = null
    var recorder: AudioRecorder? = null
    var player: AudioPlayer? = null
    var encoder: OpusEncoderWrapper? = null
    var adaptor: DynamicBitrateAdaptor? = null
    private val decoders = java.util.concurrent.ConcurrentHashMap<UUID, OpusDecoderWrapper>()
    private val lastReceivedSequences = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    @Volatile var isConnected = false
        private set

    // マイクテスト用の専用ループバックスレッド（AudioRecorder流用 + 専用SourceDataLine直書き方式）
    @Volatile private var loopbackThread: Thread? = null
    @Volatile private var loopbackRunning = false
    private var loopbackSourceLine: SourceDataLine? = null

    fun connectAsync(
        voicePort: Int,
        voiceHost: String = "",
        secretToken: UUID,
        certificateFingerprint: ByteArray
    ) {
        val mc = Minecraft.getInstance()
        val localPlayer = mc.player ?: return
        val serverData = mc.currentServer
        disconnect()
        val generation = connectionGeneration.incrementAndGet()
        val playerUuid = localPlayer.uuid
        val fallbackServerAddress = serverData?.ip

        connectionExecutor.execute {
            connectInternal(
                generation, playerUuid, fallbackServerAddress, voicePort,
                voiceHost, secretToken, certificateFingerprint
            )
        }
    }

    private fun connectInternal(
        generation: Long,
        playerUuid: UUID,
        fallbackServerAddress: String?,
        voicePort: Int,
        voiceHost: String,
        secretToken: UUID,
        certificateFingerprint: ByteArray
    ) {
        val targetHost = when {
            voiceHost.isNotBlank() -> voiceHost.trim()
            !fallbackServerAddress.isNullOrBlank() -> parseServerHost(fallbackServerAddress)
            else -> "127.0.0.1"
        }
        // DNS を即時解決して InetAddress に変換（遅延解決による送信失敗を防止）
        val resolvedAddress = try {
            java.net.InetAddress.getByName(targetHost)
        } catch (e: Exception) {
            logger.error("Failed to resolve voice server host '$targetHost': ${e.message}")
            return
        }
        val serverAddress = InetSocketAddress(resolvedAddress, voicePort)
        logger.info("Voice server address resolved: $targetHost -> ${resolvedAddress.hostAddress}:$voicePort")

        val newEncoder = OpusEncoderWrapper(48000, 1, 32000)
        if (!newEncoder.isInitialized) {
            logger.error("Could not initialize the native Opus encoder")
            return
        }
        val newAdaptor = DynamicBitrateAdaptor(newEncoder)
        val newClient = QuicVoiceClient(
            playerUuid,
            serverAddress,
            newAdaptor,
            secretToken,
            certificateFingerprint
        )
        val newRecorder = AudioRecorder(48000, 960)
        val newPlayer = AudioPlayer(48000)
        newClient.setPacketListener { packet ->
            handleIncomingPacket(packet)
        }
        if (!newClient.start() || !newRecorder.start()) {
            logger.error("Could not connect to authenticated QUIC voice server")
            newClient.stop()
            newRecorder.stop()
            newPlayer.stopAll()
            return
        }
        synchronized(this) {
            if (generation != connectionGeneration.get()) {
                newClient.stop()
                newRecorder.stop()
                newPlayer.stopAll()
                return
            }
            encoder = newEncoder
            adaptor = newAdaptor
            voiceClient = newClient
            recorder = newRecorder
            player = newPlayer
            isConnected = true
        }
        startTransmitLoop(generation, newRecorder, newClient, newEncoder)
        logger.info("ClientVoiceManager connected to voice server (resolved: ${resolvedAddress.hostAddress}:${serverAddress.port})")
        Minecraft.getInstance().tell {
            if (generation == connectionGeneration.get()) {
                PacketDistributor.sendToServer(ModNetwork.C2SVoiceSecretPayload(secretToken))
            }
        }
    }

    private fun handleIncomingPacket(packet: VoicePacket) {
        if (packet.opusData.isEmpty()) return
        val mc = Minecraft.getInstance()
        val localPlayer = mc.player
        // 自分自身のパケットが届いた場合は再生せず無視（セルフエコー・ループ防止）
        if (localPlayer != null && packet.senderUuid == localPlayer.uuid) return

        logger.info("[AUDIO-RX] Received voice from ${packet.senderUuid}, opusLen=${packet.opusData.size}")
        val decoder = decoders.computeIfAbsent(packet.senderUuid) { OpusDecoderWrapper(48000, 1) }
        val previousSequence = lastReceivedSequences[packet.senderUuid]
        if (previousSequence != null && packet.sequenceNumber <= previousSequence) return
        if (previousSequence != null) {
            val missing = (packet.sequenceNumber - previousSequence - 1).coerceIn(0, 3)
            repeat(missing.toInt()) {
                val concealed = decoder.decode(null, 960)
                player?.playAudio(
                    packet.senderUuid, concealed, packet.posX, packet.posY, packet.posZ,
                    packet.isRadio, packet.quality
                )
            }
        }
        lastReceivedSequences[packet.senderUuid] = packet.sequenceNumber
        val pcm = decoder.decode(packet.opusData, 960)
        player?.playAudio(packet.senderUuid, pcm, packet.posX, packet.posY, packet.posZ, packet.isRadio, packet.quality)
        NameTagIcons.setPlayerSpeaking(packet.senderUuid, true)
    }

    private fun startTransmitLoop(
        generation: Long,
        source: AudioRecorder,
        client: QuicVoiceClient,
        opusEncoder: OpusEncoderWrapper
    ) {
        transmitRunning = true
        transmitThread = Thread({
            while (transmitRunning && generation == connectionGeneration.get()) {
                try {
                    val frame = source.readFrame(timeoutMs = 25L) ?: continue
                    val state = transmitState
                    if (state.muted) {
                        VoiceHudOverlay.isSpeakingCurrent = false
                        continue
                    }
                    val (pcm, voiceDetected) = frame
                    val shouldTransmit = if (state.holdingRadio) {
                        state.inputMode == VoiceConfig.InputMode.PUSH_TO_TALK || voiceDetected
                    } else {
                        if (state.inputMode == VoiceConfig.InputMode.PUSH_TO_TALK) {
                            state.pttPressed
                        } else {
                            voiceDetected
                        }
                    }
                    VoiceHudOverlay.isSpeakingCurrent = shouldTransmit
                    if (!shouldTransmit) continue
                    val encoded = opusEncoder.encode(pcm, 960)
                    if (encoded.isEmpty()) continue
                    client.sendHandler?.invoke(
                        VoicePacket(
                            senderUuid = client.playerUuid,
                            sequenceNumber = 0L,
                            opusData = encoded,
                            posX = state.x,
                            posY = state.y,
                            posZ = state.z,
                            isRadio = state.holdingRadio
                        )
                    )
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (transmitRunning) logger.warn("Voice transmit loop error: ${e.message}")
                }
            }
        }, "ModernVoiceChat-Transmit").apply {
            isDaemon = true
            start()
        }
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
            transmitState = transmitState.copy(muted = true)
            if (!loopbackRunning) startLoopback()
            return
        } else {
            if (loopbackRunning) stopLoopback()
        }

        // 2. Capture is sent by the dedicated 20 ms transmit loop. The game tick
        // only publishes a thread-safe snapshot of input and player state.
        if (!isConnected || voiceClient == null) return
        val isPttPressed = KeyMappings.PUSH_TO_TALK.isDown
        val isHoldingRadio = localPlayer.isUsingItem && localPlayer.useItem.item is com.ruskserver.modernvoicechat.item.RadioItem
        transmitState = TransmitState(
            isPttPressed, isHoldingRadio, VoiceConfig.isMicMuted,
            VoiceConfig.inputMode, localPlayer.x, localPlayer.y, localPlayer.z
        )
        if (VoiceConfig.isMicMuted) {
            recorder?.discardPendingFrames()
            VoiceHudOverlay.isSpeakingCurrent = false
        }
    }

    @SubscribeEvent
    fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        disconnect()
    }

    @Synchronized
    fun disconnect() {
        connectionGeneration.incrementAndGet()
        transmitRunning = false
        transmitThread?.interrupt()
        transmitThread = null
        stopLoopback()
        if (!isConnected) {
            // テストのみ使用時のリソース解放
            recorder?.stop()
            player?.stopAll()
            encoder?.close()
            decoders.values.forEach { it.close() }
            decoders.clear()
            lastReceivedSequences.clear()
            recorder = null
            player = null
            encoder = null
            return
        }
        voiceClient?.stop()
        recorder?.stop()
        player?.stopAll()

        voiceClient = null
        recorder = null
        player = null
        encoder?.close()
        encoder = null
        decoders.values.forEach { it.close() }
        decoders.clear()
        lastReceivedSequences.clear()
        adaptor = null
        isConnected = false
        VoiceHudOverlay.isSpeakingCurrent = false
        logger.info("ClientVoiceManager disconnected")
    }

    private fun parseServerHost(address: String): String {
        val trimmed = address.trim()
        if (trimmed.startsWith("[")) {
            return trimmed.substringAfter("[").substringBefore("]")
        }
        return if (trimmed.count { it == ':' } == 1) trimmed.substringBefore(":") else trimmed
    }

    fun reloadAudioDevices() {
        val restartLoopback = loopbackRunning
        if (restartLoopback) stopLoopback()
        recorder?.restart()
        player?.reloadOutputDevice()
        if (restartLoopback) startLoopback()
    }
}
