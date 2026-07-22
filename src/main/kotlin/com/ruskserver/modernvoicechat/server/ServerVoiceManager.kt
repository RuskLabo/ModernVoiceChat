package com.ruskserver.modernvoicechat.server

import com.ruskserver.modernvoicechat.config.ServerConfig
import com.ruskserver.modernvoicechat.network.ModNetwork
import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import com.ruskserver.modernvoicechat.transport.QuicVoiceServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ServerVoiceManager {
    private val logger = LoggerFactory.getLogger(ServerVoiceManager::class.java)

    val router = SFURouter()
    var voiceServer: QuicVoiceServer? = null

    val playerTokens = ConcurrentHashMap<UUID, UUID>()
    private val activePlayers = ConcurrentHashMap<UUID, ServerPlayer>()
    private val speakingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun getPlayerByUuid(uuid: UUID): ServerPlayer? = activePlayers[uuid]

    fun isPlayerSpeaking(uuid: UUID): Boolean = speakingPlayers.contains(uuid)

    fun setPlayerSpeaking(uuid: UUID, speaking: Boolean) {
        if (speaking) {
            speakingPlayers.add(uuid)
        } else {
            speakingPlayers.remove(uuid)
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
            com.ruskserver.modernvoicechat.api.event.PlayerSpeakingEvent(uuid, speaking)
        )
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        val port = ServerConfig.VOICE_PORT.get()
        router.maxDistance = ServerConfig.VOICE_RANGE.get()

        voiceServer = QuicVoiceServer(port, router)
        voiceServer?.start()
        logger.info("==========================================================")
        logger.info("[ModernVoiceChat] SFU Voice Server started on UDP port $port (Range: ${router.maxDistance}m)")
        logger.info("==========================================================")
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        voiceServer?.stop()
        voiceServer = null
        playerTokens.clear()
        logger.info("[ModernVoiceChat] SFU Voice Server stopped.")
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val playerUuid = player.uuid

        val secretToken = UUID.randomUUID()
        playerTokens[playerUuid] = secretToken
        activePlayers[playerUuid] = player

        router.updatePosition(
            playerUuid,
            PlayerPosition(player.x, player.y, player.z, player.level().dimension().location().toString())
        )

        val port = ServerConfig.VOICE_PORT.get()
        val host = ServerConfig.VOICE_HOST.get()
        PacketDistributor.sendToPlayer(player, ModNetwork.S2CVoiceSecretPayload(secretToken, port, host))
        logger.info("[ModernVoiceChat] Initialized Voice Chat handshake token for player: ${player.scoreboardName} ($playerUuid)")
    }

    fun onClientVoiceConfirmed(playerUuid: UUID) {
        logger.info("[ModernVoiceChat] Voice Chat connection CONFIRMED & ESTABLISHED for player: $playerUuid")
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val playerUuid = event.entity.uuid
        playerTokens.remove(playerUuid)
        activePlayers.remove(playerUuid)
        speakingPlayers.remove(playerUuid)
        router.removePlayer(playerUuid)
        voiceServer?.unregisterClient(playerUuid)
        logger.info("[ModernVoiceChat] Voice Chat session disconnected for player: ${event.entity.scoreboardName}")
    }

    @SubscribeEvent
    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server ?: return
        for (player in server.playerList.players) {
            router.updatePosition(
                player.uuid,
                PlayerPosition(player.x, player.y, player.z, player.level().dimension().location().toString())
            )
        }
    }
}
