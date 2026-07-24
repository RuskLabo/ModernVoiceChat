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
    private val serverMutedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val lastRadioTransmission = ConcurrentHashMap<UUID, Long>()
    private val lastVoiceTransmission = ConcurrentHashMap<UUID, Long>()
    private val radioStates = ConcurrentHashMap<UUID, RadioState>()

    private data class RadioState(
        val transmitFrequency: Double?,
        val receiveFrequencies: Set<Double>
    )

    fun getPlayerByUuid(uuid: UUID): ServerPlayer? = activePlayers[uuid]

    fun isPlayerSpeaking(uuid: UUID): Boolean = speakingPlayers.contains(uuid)
    fun isPlayerMuted(uuid: UUID): Boolean = serverMutedPlayers.contains(uuid)
    fun setPlayerMuted(uuid: UUID, muted: Boolean) {
        if (muted) serverMutedPlayers.add(uuid) else serverMutedPlayers.remove(uuid)
    }

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
        voiceServer?.packetAuthenticator = { playerUuid, token ->
            playerTokens[playerUuid] == token && activePlayers.containsKey(playerUuid)
        }
        voiceServer?.senderAllowedProvider = { !serverMutedPlayers.contains(it) }
        voiceServer?.voicePacketListener = { playerUuid ->
            val firstPacket = lastVoiceTransmission.put(playerUuid, System.nanoTime()) == null
            if (firstPacket) {
                activePlayers[playerUuid]?.server?.execute { setPlayerSpeaking(playerUuid, true) }
            }
        }
        voiceServer?.radioTransmitFrequencyProvider = { radioStates[it]?.transmitFrequency }
        voiceServer?.radioReceiveFrequenciesProvider = {
            radioStates[it]?.receiveFrequencies ?: emptySet()
        }
        voiceServer?.radioPacketListener = { playerUuid, frequency ->
            val firstPacket = lastRadioTransmission.put(playerUuid, System.nanoTime()) == null
            if (firstPacket) {
                activePlayers[playerUuid]?.server?.execute {
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                        com.ruskserver.modernvoicechat.api.event.RadioTransmitEvent(
                            playerUuid, frequency, true
                        )
                    )
                }
            }
        }
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
        val fingerprint = voiceServer?.certificateFingerprint
        if (fingerprint == null) {
            logger.error("Cannot initialize voice chat for ${player.scoreboardName}: QUIC server is unavailable")
            return
        }
        PacketDistributor.sendToPlayer(
            player,
            ModNetwork.S2CVoiceSecretPayload(secretToken, port, host, fingerprint)
        )
        logger.info("[ModernVoiceChat] Initialized Voice Chat handshake token for player: ${player.scoreboardName} ($playerUuid)")
    }

    fun onClientVoiceConfirmed(playerUuid: UUID, token: UUID) {
        if (playerTokens[playerUuid] == token) {
            logger.info("[ModernVoiceChat] Voice Chat connection CONFIRMED & ESTABLISHED for player: $playerUuid")
        } else {
            logger.warn("[ModernVoiceChat] Rejected invalid Voice Chat confirmation for player: $playerUuid")
        }
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val playerUuid = event.entity.uuid
        playerTokens.remove(playerUuid)
        activePlayers.remove(playerUuid)
        speakingPlayers.remove(playerUuid)
        serverMutedPlayers.remove(playerUuid)
        lastRadioTransmission.remove(playerUuid)
        lastVoiceTransmission.remove(playerUuid)
        radioStates.remove(playerUuid)
        router.removePlayer(playerUuid)
        voiceServer?.unregisterClient(playerUuid)
        logger.info("[ModernVoiceChat] Voice Chat session disconnected for player: ${event.entity.scoreboardName}")
    }

    @SubscribeEvent
    fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server ?: return
        router.maxDistance = ServerConfig.VOICE_RANGE.get()
        for (player in server.playerList.players) {
            router.updatePosition(
                player.uuid,
                PlayerPosition(player.x, player.y, player.z, player.level().dimension().location().toString())
            )
            val radioStacks = (player.inventory.items + player.inventory.offhand)
                .filter { it.item is com.ruskserver.modernvoicechat.item.RadioItem }
            val receiveFrequencies = radioStacks
                .map(com.ruskserver.modernvoicechat.item.RadioItem::getFrequency)
                .filter { it.isFinite() && it in 30.0..3000.0 }
                .toSet()
            val transmitFrequency = if (
                player.isUsingItem &&
                player.useItem.item is com.ruskserver.modernvoicechat.item.RadioItem
            ) {
                com.ruskserver.modernvoicechat.item.RadioItem.getFrequency(player.useItem)
                    .takeIf { it.isFinite() && it in 30.0..3000.0 }
            } else null
            radioStates[player.uuid] = RadioState(transmitFrequency, receiveFrequencies)
        }
        val cutoff = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300)
        for ((playerUuid, lastPacket) in lastVoiceTransmission) {
            if (lastPacket < cutoff && lastVoiceTransmission.remove(playerUuid, lastPacket)) {
                setPlayerSpeaking(playerUuid, false)
            }
        }
        for ((playerUuid, lastPacket) in lastRadioTransmission) {
            if (lastPacket < cutoff && lastRadioTransmission.remove(playerUuid, lastPacket)) {
                val frequency = radioStates[playerUuid]?.transmitFrequency ?: 144.0
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    com.ruskserver.modernvoicechat.api.event.RadioTransmitEvent(
                        playerUuid, frequency, false
                    )
                )
            }
        }
    }
}
