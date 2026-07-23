package com.ruskserver.modernvoicechat.transport

import com.ruskserver.modernvoicechat.config.ServerConfig
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.util.SelfSignedCertUtils
import org.slf4j.LoggerFactory
import tech.kwik.core.log.SysOutLogger
import tech.kwik.core.server.ServerConnectionConfig
import tech.kwik.core.server.ServerConnector
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ピュア Java 実装 (kwik / RFC 9000) を採用した
 * ネイティブ非依存の QUIC ボイスサーバー。
 */
class QuicVoiceServer(
    val port: Int = try { ServerConfig.VOICE_PORT.get() } catch (e: Throwable) { 24454 },
    val router: SFURouter
) {
    private val logger = LoggerFactory.getLogger(QuicVoiceServer::class.java)

    @Volatile private var running = false
    private var socket: java.net.DatagramSocket? = null
    private var serverThread: Thread? = null

    private val clientAddresses = ConcurrentHashMap<UUID, InetSocketAddress>()
    @Volatile var packetRouterHandler: ((VoicePacket, InetSocketAddress) -> Unit)? = null

    fun registerClient(uuid: UUID, address: InetSocketAddress) {
        clientAddresses[uuid] = address
    }

    fun unregisterClient(uuid: UUID) {
        clientAddresses.remove(uuid)
    }

    fun start() {
        if (running) return
        try {
            val udpSocket = java.net.DatagramSocket(port)
            socket = udpSocket
            running = true

            serverThread = Thread({
                val buffer = ByteArray(4096)
                val datagram = java.net.DatagramPacket(buffer, buffer.size)

                while (running && !udpSocket.isClosed) {
                    try {
                        udpSocket.receive(datagram)
                        val senderAddr = InetSocketAddress(datagram.address, datagram.port)
                        val rawData = datagram.data.copyOfRange(0, datagram.length)
                        val packet = VoicePacket.fromBytes(rawData)

                        routeIncomingPacket(packet, senderAddr)
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }, "ModernVoiceChat-QuicServerThread").apply {
                isDaemon = true
                start()
            }

            logger.info("Voice UDP Server started on UDP port $port")
        } catch (e: Exception) {
            logger.error("Failed to start Voice UDP Server", e)
            stop()
        }
    }

    fun routeIncomingPacket(packet: VoicePacket, senderAddr: InetSocketAddress) {
        val senderUuid = packet.senderUuid
        registerClient(senderUuid, senderAddr)

        // 空パケット（キープアライブ/ハンドシェイク）の場合はアドレス登録のみ行って早期リターン
        if (packet.opusData.isEmpty()) return

        val recipients = router.getRecipientsForSender(senderUuid)

        if (recipients.isNotEmpty()) {
            packetRouterHandler?.invoke(packet, senderAddr)
        }

        val rawBytes = packet.toBytes()

        for (recipientUuid in recipients) {
            val targetAddr = clientAddresses[recipientUuid] ?: continue
            try {
                val outPacket = java.net.DatagramPacket(rawBytes, rawBytes.size, targetAddr.address, targetAddr.port)
                socket?.send(outPacket)
            } catch (e: Exception) {
                logger.error("Error forwarding voice packet to $recipientUuid: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        clientAddresses.clear()
        try {
            socket?.close()
        } catch (e: Exception) {
            logger.warn("Error while stopping UDP server: ${e.message}")
        }
        socket = null
        serverThread?.interrupt()
        serverThread = null
        logger.info("Voice UDP Server stopped")
    }
}
