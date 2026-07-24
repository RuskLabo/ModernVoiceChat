package com.ruskserver.modernvoicechat.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

object VoiceControlProtocol {
    const val ALPN = "modernvoicechat/1"
    const val MAGIC = 0x4D564331 // MVC1
    const val VERSION = 1
    const val AUTH_ACCEPTED = 0
    const val AUTH_REJECTED = 1

    data class ClientHello(val playerUuid: UUID, val sessionToken: UUID)

    fun writeClientHello(output: DataOutputStream, hello: ClientHello) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeLong(hello.playerUuid.mostSignificantBits)
        output.writeLong(hello.playerUuid.leastSignificantBits)
        output.writeLong(hello.sessionToken.mostSignificantBits)
        output.writeLong(hello.sessionToken.leastSignificantBits)
        output.flush()
    }

    fun readClientHello(input: DataInputStream): ClientHello {
        require(input.readInt() == MAGIC) { "Invalid voice control protocol magic" }
        require(input.readInt() == VERSION) { "Unsupported voice control protocol version" }
        val playerUuid = UUID(input.readLong(), input.readLong())
        val sessionToken = UUID(input.readLong(), input.readLong())
        return ClientHello(playerUuid, sessionToken)
    }
}
