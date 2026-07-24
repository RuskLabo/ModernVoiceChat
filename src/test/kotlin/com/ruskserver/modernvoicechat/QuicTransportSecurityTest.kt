package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.QuicVoiceClient
import com.ruskserver.modernvoicechat.transport.QuicVoiceServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID

class QuicTransportSecurityTest {

    @Test
    fun `client rejects a server with the wrong pinned certificate`() {
        val server = QuicVoiceServer(24458, SFURouter(), Files.createTempDirectory("mvc-quic-cert"))
        server.start()
        val wrongFingerprint = server.certificateFingerprint.copyOf().also {
            it[0] = (it[0].toInt() xor 0xFF).toByte()
        }
        val client = QuicVoiceClient(
            UUID.randomUUID(),
            InetSocketAddress("127.0.0.1", 24458),
            sessionToken = UUID.randomUUID(),
            expectedCertificateFingerprint = wrongFingerprint
        )

        try {
            assertFalse(client.start())
        } finally {
            client.stop()
            server.stop()
        }
    }

    @Test
    fun `server rejects an invalid minecraft session token`() {
        val validToken = UUID.randomUUID()
        val playerUuid = UUID.randomUUID()
        val server = QuicVoiceServer(24459, SFURouter(), Files.createTempDirectory("mvc-quic-auth"))
        server.packetAuthenticator = { uuid, token -> uuid == playerUuid && token == validToken }
        server.start()
        val client = QuicVoiceClient(
            playerUuid,
            InetSocketAddress("127.0.0.1", 24459),
            sessionToken = UUID.randomUUID(),
            expectedCertificateFingerprint = server.certificateFingerprint
        )

        try {
            assertFalse(client.start())
        } finally {
            client.stop()
            server.stop()
        }
    }
}
