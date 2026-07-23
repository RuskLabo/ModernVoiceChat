package com.ruskserver.modernvoicechat.transport

import com.ruskserver.modernvoicechat.audio.adaptation.DynamicBitrateAdaptor
import org.slf4j.LoggerFactory
import tech.kwik.core.QuicClientConnection
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * ピュア Java 実装 (kwik / RFC 9000) を採用した
 * ネイティブ非依存の QUIC ボイスクライアント。
 */
class QuicVoiceClient(
    val playerUuid: UUID,
    val serverAddress: InetSocketAddress,
    val adaptor: DynamicBitrateAdaptor? = null
) {
    private val logger = LoggerFactory.getLogger(QuicVoiceClient::class.java)

    @Volatile private var packetListener: ((VoicePacket) -> Unit)? = null
    @Volatile var sendHandler: ((VoicePacket) -> Unit)? = null
    @Volatile private var running = false
    private var socket: java.net.DatagramSocket? = null
    private var receiveThread: Thread? = null

    private val sequenceCounter = AtomicLong(0)

    fun setPacketListener(listener: (VoicePacket) -> Unit) {
        this.packetListener = listener
    }

    fun handlePacketDirectly(packet: VoicePacket) {
        packetListener?.invoke(packet)
    }

    fun start() {
        if (running) return
        try {
            val udpSocket = java.net.DatagramSocket()
            socket = udpSocket
            running = true

            // 受信用スレッド
            receiveThread = Thread({
                val buffer = ByteArray(4096)
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                while (running && !udpSocket.isClosed) {
                    try {
                        udpSocket.receive(packet)
                        val data = packet.data.copyOfRange(0, packet.length)
                        val voicePacket = VoicePacket.fromBytes(data)
                        packetListener?.invoke(voicePacket)
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }, "ModernVoiceChat-QuicClientReceiveThread").apply {
                isDaemon = true
                start()
            }

            // 送信用ハンドラーの設定
            sendHandler = { voicePacket ->
                sendAudioPacket(voicePacket)
            }

            logger.info("Voice UDP Client connected to server ${serverAddress.hostString}:${serverAddress.port} for player $playerUuid")
        } catch (e: Exception) {
            logger.error("Failed to start Voice UDP Client", e)
            stop()
        }
    }

    fun sendHandshake(x: Double, y: Double, z: Double) {
        val dummyOpus = byteArrayOf(0, 1, 2, 3)
        sendAudioFrame(dummyOpus, x, y, z)
    }

    fun sendAudioFrame(opusData: ByteArray, x: Double, y: Double, z: Double) {
        val packet = VoicePacket(
            senderUuid = playerUuid,
            sequenceNumber = sequenceCounter.incrementAndGet(),
            posX = x,
            posY = y,
            posZ = z,
            opusData = opusData
        )
        sendAudioPacket(packet)
    }

    private fun sendAudioPacket(packet: VoicePacket) {
        if (!running) return
        val udpSocket = socket ?: return
        try {
            val rawBytes = packet.toBytes()
            val datagram = java.net.DatagramPacket(rawBytes, rawBytes.size, serverAddress.address ?: java.net.InetAddress.getByName(serverAddress.hostString), serverAddress.port)
            udpSocket.send(datagram)
            adaptor?.onPacketSent(rawBytes.size)
        } catch (e: Exception) {
            logger.error("Error sending voice packet: ${e.message}")
        }
    }

    fun stop() {
        running = false
        sendHandler = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        receiveThread?.interrupt()
        receiveThread = null
        logger.info("Voice UDP Client stopped")
    }
}
