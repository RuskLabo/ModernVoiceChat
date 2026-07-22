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
    private var connection: QuicClientConnection? = null

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
            val conn = QuicClientConnection.newBuilder()
                .uri(java.net.URI.create("https://${serverAddress.hostString}:${serverAddress.port}"))
                .applicationProtocol("modernvoicechat")
                .noServerCertificateCheck()
                .build()
            conn.connect()
            connection = conn
            running = true
            logger.info("kwik Pure Java QUIC Client connected to voice server (port: ${serverAddress.port}) for player $playerUuid")
        } catch (e: Exception) {
            logger.error("Failed to start kwik QUIC Client", e)
            stop()
        }
    }

    fun sendHandshake(x: Double, y: Double, z: Double) {
        val dummyOpus = byteArrayOf(0, 1, 2, 3)
        sendAudioFrame(dummyOpus, x, y, z)
    }

    fun sendAudioFrame(opusData: ByteArray, x: Double, y: Double, z: Double) {
        try {
            val packet = VoicePacket(
                senderUuid = playerUuid,
                sequenceNumber = sequenceCounter.incrementAndGet(),
                posX = x,
                posY = y,
                posZ = z,
                opusData = opusData
            )
            val rawBytes = packet.toBytes()
            sendHandler?.invoke(packet)
            adaptor?.onPacketSent(rawBytes.size)
        } catch (e: Exception) {
            logger.error("Error sending QUIC audio frame: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        logger.info("kwik QUIC Client stopped")
    }
}
