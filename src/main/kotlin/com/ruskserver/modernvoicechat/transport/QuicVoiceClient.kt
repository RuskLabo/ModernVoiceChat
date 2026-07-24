package com.ruskserver.modernvoicechat.transport

import com.ruskserver.modernvoicechat.audio.adaptation.DynamicBitrateAdaptor
import org.slf4j.LoggerFactory
import tech.kwik.core.ConnectionListener
import tech.kwik.core.ConnectionTerminatedEvent
import tech.kwik.core.QuicClientConnection
import tech.kwik.core.log.SysOutLogger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QuicVoiceClient(
    val playerUuid: UUID,
    val serverAddress: InetSocketAddress,
    val adaptor: DynamicBitrateAdaptor? = null,
    private val sessionToken: UUID = UUID(0L, 0L),
    private val expectedCertificateFingerprint: ByteArray? = null
) {
    private val logger = LoggerFactory.getLogger(QuicVoiceClient::class.java)
    @Volatile private var packetListener: ((VoicePacket) -> Unit)? = null
    @Volatile var sendHandler: ((VoicePacket) -> Unit)? = null
    @Volatile private var running = false
    @Volatile private var connection: QuicClientConnection? = null
    private val sequenceCounter = AtomicLong(0)
    private var lastMetricsNanos = System.nanoTime()
    private var lastPacketsSent = 0L
    private var lastPacketsLost = 0L

    fun setPacketListener(listener: (VoicePacket) -> Unit) {
        packetListener = listener
    }

    fun handlePacketDirectly(packet: VoicePacket) {
        packetListener?.invoke(packet)
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            var builder = QuicClientConnection.newBuilder()
                .host(serverAddress.address?.hostAddress ?: serverAddress.hostString)
                .port(serverAddress.port)
                .applicationProtocol(VoiceControlProtocol.ALPN)
                .connectTimeout(Duration.ofSeconds(10))
                .maxIdleTimeout(Duration.ofSeconds(30))
                .maxOpenPeerInitiatedBidirectionalStreams(0)
                .maxOpenPeerInitiatedUnidirectionalStreams(0)
                .enableDatagramExtension()
                .logger(SysOutLogger())

            // Kwik performs hostname validation even with a custom trust manager. The
            // server uses a persistent self-signed certificate, so connect first without
            // PKIX validation and pin the returned certificate before sending credentials.
            builder = builder.noServerCertificateCheck()

            val newConnection = builder.build()
            newConnection.setConnectionListener(object : ConnectionListener {
                override fun disconnected(event: ConnectionTerminatedEvent) {
                    running = false
                    sendHandler = null
                    logger.info("Voice QUIC connection closed: ${event.errorDescription() ?: event.closeReason()}")
                }
            })
            newConnection.connect()
            verifyServerCertificate(newConnection)
            check(newConnection.isDatagramExtensionEnabled) {
                "Server did not negotiate QUIC Datagram Extension"
            }
            newConnection.setDatagramHandler { bytes ->
                val packet = try {
                    VoicePacket.fromBytes(bytes)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Rejected malformed QUIC voice datagram: ${e.message}")
                    return@setDatagramHandler
                }
                packetListener?.invoke(packet)
            }

            val authenticationExecutor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "ModernVoiceChat-QuicAuthentication").apply { isDaemon = true }
            }
            try {
                val authentication = authenticationExecutor.submit { authenticate(newConnection) }
                authentication.get(5, TimeUnit.SECONDS)
            } finally {
                authenticationExecutor.shutdownNow()
            }
            newConnection.keepAlive(10)
            connection = newConnection
            running = true
            sendHandler = { voicePacket -> sendAudioPacket(voicePacket) }
            logger.info("Voice QUIC client connected to ${serverAddress.hostString}:${serverAddress.port}")
            true
        } catch (e: Exception) {
            logger.error("Failed to establish authenticated QUIC voice connection", e)
            stop()
            false
        }
    }

    private fun authenticate(quicConnection: QuicClientConnection) {
        val stream = quicConnection.createStream(true)
        VoiceControlProtocol.writeClientHello(
            DataOutputStream(stream.outputStream),
            VoiceControlProtocol.ClientHello(playerUuid, sessionToken)
        )
        stream.outputStream.close()
        val status = DataInputStream(stream.inputStream).readInt()
        check(status == VoiceControlProtocol.AUTH_ACCEPTED) {
            "Voice server rejected authentication"
        }
    }

    private fun verifyServerCertificate(quicConnection: QuicClientConnection) {
        val expected = expectedCertificateFingerprint
        if (expected == null) {
            logger.warn("No QUIC certificate fingerprint supplied; certificate verification is disabled")
            return
        }
        val certificate = quicConnection.serverCertificateChain.firstOrNull()
            ?: error("QUIC server did not provide a certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        check(MessageDigest.isEqual(expected, actual)) {
            "QUIC server certificate fingerprint mismatch"
        }
    }

    fun sendHandshake(x: Double, y: Double, z: Double) = Unit

    fun sendAudioFrame(opusData: ByteArray, x: Double, y: Double, z: Double) {
        sendAudioPacket(
            VoicePacket(
                senderUuid = playerUuid,
                sequenceNumber = sequenceCounter.incrementAndGet(),
                posX = x,
                posY = y,
                posZ = z,
                opusData = opusData
            )
        )
    }

    private fun sendAudioPacket(packet: VoicePacket) {
        if (!running) return
        val quicConnection = connection ?: return
        try {
            val packetWithoutCredentials = packet.copy(senderUuid = playerUuid)
                .copy(sequenceNumber = sequenceCounter.incrementAndGet())
            val bytes = packetWithoutCredentials.toBytes()
            require(bytes.size <= quicConnection.maxDatagramDataSize()) {
                "Voice packet exceeds negotiated QUIC datagram size"
            }
            quicConnection.sendDatagram(bytes)
            adaptor?.onPacketSent(bytes.size)
            updateNetworkMetrics(quicConnection)
        } catch (e: Exception) {
            logger.warn("Error sending QUIC voice datagram: ${e.message}")
        }
    }

    @Synchronized
    private fun updateNetworkMetrics(quicConnection: QuicClientConnection) {
        val now = System.nanoTime()
        if (now - lastMetricsNanos < TimeUnit.SECONDS.toNanos(1)) return
        val stats = quicConnection.stats
        val sent = (stats.packetsSent() - lastPacketsSent).coerceAtLeast(0)
        val lost = (stats.lostPackets() - lastPacketsLost).coerceAtLeast(0)
        val lossPercent = if (sent + lost > 0) {
            (lost * 100.0 / (sent + lost)).toFloat()
        } else {
            0.0f
        }
        adaptor?.updateMetrics(stats.smoothedRtt().toLong(), lossPercent)
        lastPacketsSent = stats.packetsSent()
        lastPacketsLost = stats.lostPackets()
        lastMetricsNanos = now
    }

    fun stop() {
        running = false
        sendHandler = null
        try {
            connection?.closeAndWait(Duration.ofSeconds(2))
        } catch (_: Exception) {
            connection?.close()
        }
        connection = null
        logger.info("Voice QUIC client stopped")
    }

}
