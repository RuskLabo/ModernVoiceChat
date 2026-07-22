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
    private var connector: ServerConnector? = null

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
            val certPair = SelfSignedCertUtils.generateSelfSignedCert()
            val config = ServerConnectionConfig.builder().build()

            val conn = ServerConnector.builder()
                .withPort(port)
                .withConfiguration(config)
                .withLogger(SysOutLogger())
                .withCertificate(
                    java.io.FileInputStream(certPair.certFile),
                    java.io.FileInputStream(certPair.keyFile)
                )
                .build()

            conn.registerApplicationProtocol("modernvoicechat") { _, _ ->
                object : tech.kwik.core.server.ApplicationProtocolConnection {}
            }

            conn.start()
            connector = conn
            running = true
            logger.info("kwik Pure Java QUIC Server started on port $port")
        } catch (e: Exception) {
            logger.error("Failed to start kwik QUIC Server", e)
            stop()
        }
    }

    fun routeIncomingPacket(packet: VoicePacket, senderAddr: InetSocketAddress) {
        val senderUuid = packet.senderUuid
        registerClient(senderUuid, senderAddr)
        val recipients = router.getRecipientsForSender(senderUuid)
        if (recipients.isNotEmpty()) {
            packetRouterHandler?.invoke(packet, senderAddr)
        }
    }

    fun stop() {
        running = false
        clientAddresses.clear()
        try {
            connector?.close()
        } catch (e: Exception) {
            logger.warn("Error while stopping kwik server: ${e.message}")
        }
        connector = null
        logger.info("kwik QUIC Server stopped")
    }
}
