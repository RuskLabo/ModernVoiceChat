package com.ruskserver.modernvoicechat.transport

import com.ruskserver.modernvoicechat.config.ServerConfig
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.util.SelfSignedCertUtils
import org.slf4j.LoggerFactory
import tech.kwik.core.ConnectionListener
import tech.kwik.core.ConnectionTerminatedEvent
import tech.kwik.core.QuicConnection
import tech.kwik.core.QuicStream
import tech.kwik.core.log.SysOutLogger
import tech.kwik.core.server.ApplicationProtocolConnection
import tech.kwik.core.server.ApplicationProtocolConnectionFactory
import tech.kwik.core.server.ServerConnectionConfig
import tech.kwik.core.server.ServerConnector
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QuicVoiceServer(
    val port: Int = try { ServerConfig.VOICE_PORT.get() } catch (_: Throwable) { 24454 },
    val router: SFURouter,
    certificateDirectory: Path = defaultCertificateDirectory()
) {
    private val logger = LoggerFactory.getLogger(QuicVoiceServer::class.java)
    private val sessions = ConcurrentHashMap<UUID, VoiceSession>()
    private val controlExecutor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "ModernVoiceChat-QuicControl").apply { isDaemon = true }
    }
    private val certificatePair = SelfSignedCertUtils.loadOrCreate(certificateDirectory)
    val certificateFingerprint: ByteArray =
        SelfSignedCertUtils.certificateFingerprint(certificatePair)

    @Volatile private var connector: ServerConnector? = null
    @Volatile var packetRouterHandler: ((VoicePacket, InetSocketAddress) -> Unit)? = null
    @Volatile var packetAuthenticator: ((UUID, UUID) -> Boolean)? = null

    private data class VoiceSession(
        val connection: QuicConnection,
        @Volatile var playerUuid: UUID? = null,
        @Volatile var authenticated: Boolean = false
    )

    fun start() {
        if (connector != null) return

        val password = UUID.randomUUID().toString().toCharArray()
        val keyStore = SelfSignedCertUtils.loadKeyStore(certificatePair, password)
        val configuration = ServerConnectionConfig.builder()
            .maxIdleTimeoutInSeconds(30)
            .maxOpenPeerInitiatedBidirectionalStreams(1)
            .maxOpenPeerInitiatedUnidirectionalStreams(0)
            .maxConnectionBufferSize(64 * 1024L)
            .maxBidirectionalStreamBufferSize(8 * 1024L)
            .retryRequired(true)
            .build()

        val newConnector = ServerConnector.builder()
            .withPort(port)
            .withKeyStore(keyStore, "modernvoicechat", password)
            .withConfiguration(configuration)
            .withLogger(SysOutLogger())
            .build()

        newConnector.registerApplicationProtocol(
            VoiceControlProtocol.ALPN,
            object : ApplicationProtocolConnectionFactory {
                override fun enableDatagramExtension(): Boolean = true
                override fun maxConcurrentPeerInitiatedBidirectionalStreams(): Int = 1
                override fun maxTotalPeerInitiatedBidirectionalStreams(): Long = 1
                override fun maxConcurrentPeerInitiatedUnidirectionalStreams(): Int = 0
                override fun maxTotalPeerInitiatedUnidirectionalStreams(): Long = 0

                override fun createConnection(
                    protocol: String,
                    quicConnection: QuicConnection
                ): ApplicationProtocolConnection {
                    return createApplicationConnection(quicConnection)
                }
            }
        )
        newConnector.start()
        connector = newConnector
        logger.info("Voice QUIC server started on UDP port $port using ALPN ${VoiceControlProtocol.ALPN}")
    }

    private fun createApplicationConnection(connection: QuicConnection): ApplicationProtocolConnection {
        val session = VoiceSession(connection)
        connection.setDatagramHandler { bytes ->
            val playerUuid = session.playerUuid
            if (!session.authenticated || playerUuid == null) return@setDatagramHandler
            val packet = try {
                VoicePacket.fromBytes(bytes).copy(senderUuid = playerUuid)
            } catch (e: IllegalArgumentException) {
                logger.warn("Rejected malformed QUIC datagram from $playerUuid: ${e.message}")
                return@setDatagramHandler
            }
            routeAuthenticatedPacket(packet, connectionAddress(connection))
        }
        connection.setConnectionListener(object : ConnectionListener {
            override fun disconnected(event: ConnectionTerminatedEvent) {
                removeSession(session)
            }
        })

        return object : ApplicationProtocolConnection {
            override fun acceptPeerInitiatedStream(stream: QuicStream) {
                controlExecutor.execute { authenticateStream(session, stream) }
            }
        }
    }

    private fun authenticateStream(session: VoiceSession, stream: QuicStream) {
        try {
            val hello = VoiceControlProtocol.readClientHello(DataInputStream(stream.inputStream))
            val accepted = packetAuthenticator?.invoke(hello.playerUuid, hello.sessionToken) ?: true
            val output = DataOutputStream(stream.outputStream)
            output.writeInt(
                if (accepted) VoiceControlProtocol.AUTH_ACCEPTED
                else VoiceControlProtocol.AUTH_REJECTED
            )
            output.flush()
            stream.outputStream.close()

            if (!accepted || session.authenticated) {
                if (!accepted) session.connection.close(0x101L, "Voice authentication rejected")
                return
            }

            session.playerUuid = hello.playerUuid
            session.authenticated = true
            sessions.put(hello.playerUuid, session)?.let { old ->
                if (old !== session) old.connection.close(0x102L, "Replaced by a newer voice connection")
            }
            logger.info("Authenticated QUIC voice session for ${hello.playerUuid}")
        } catch (e: Exception) {
            logger.warn("Failed to authenticate QUIC voice connection: ${e.message}")
            session.connection.close(0x103L, "Invalid voice authentication")
        }
    }

    private fun routeAuthenticatedPacket(packet: VoicePacket, senderAddress: InetSocketAddress) {
        if (packet.opusData.isEmpty()) return

        val senderUuid = packet.senderUuid
        val serverPosition = router.getPosition(senderUuid)
        val forwardedPacket = if (serverPosition != null) {
            packet.copy(
                posX = serverPosition.x,
                posY = serverPosition.y,
                posZ = serverPosition.z
            )
        } else {
            packet
        }

        packetRouterHandler?.invoke(forwardedPacket, senderAddress)
        val rawBytes = forwardedPacket.toBytes()
        for (recipientUuid in router.getRecipientsForSender(senderUuid)) {
            val recipient = sessions[recipientUuid] ?: continue
            try {
                if (recipient.connection.canSendDatagram()) {
                    recipient.connection.sendDatagram(rawBytes)
                }
            } catch (e: Exception) {
                logger.warn("Failed to forward QUIC voice datagram to $recipientUuid: ${e.message}")
            }
        }
    }

    /**
     * Test/API hook. Network traffic reaches routeAuthenticatedPacket only after QUIC authentication.
     */
    fun routeIncomingPacket(packet: VoicePacket, senderAddr: InetSocketAddress) {
        routeAuthenticatedPacket(packet, senderAddr)
    }

    fun registerClient(uuid: UUID, address: InetSocketAddress) = Unit

    fun unregisterClient(uuid: UUID) {
        sessions.remove(uuid)?.connection?.close(0x100L, "Player logged out")
    }

    fun stop() {
        connector?.close()
        connector = null
        sessions.clear()
        controlExecutor.shutdownNow()
        logger.info("Voice QUIC server stopped")
    }

    private fun removeSession(session: VoiceSession) {
        val uuid = session.playerUuid ?: return
        sessions.remove(uuid, session)
    }

    private fun connectionAddress(connection: QuicConnection): InetSocketAddress =
        (connection as? tech.kwik.core.server.ServerConnection)?.initialRemoteAddress
            ?: InetSocketAddress(0)

    companion object {
        private fun defaultCertificateDirectory(): Path = try {
            net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("modernvoicechat")
        } catch (_: Throwable) {
            Path.of(System.getProperty("java.io.tmpdir"), "modernvoicechat")
        }
    }
}
