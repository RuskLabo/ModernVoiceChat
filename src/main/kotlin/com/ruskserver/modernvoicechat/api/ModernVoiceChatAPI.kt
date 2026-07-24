package com.ruskserver.modernvoicechat.api

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import com.ruskserver.modernvoicechat.item.RadioItem
import com.ruskserver.modernvoicechat.server.ServerVoiceManager
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 他 Mod / プラグインから ModernVoiceChat の全機能へアクセスするためのメイン API エントリポイント。
 */
object ModernVoiceChatAPI {

    private val serverApiInstance = ServerApiImpl()
    private val clientApiInstance = ClientApiImpl()

    @JvmStatic
    fun getServerApi(): VoiceChatServerAPI = serverApiInstance

    @JvmStatic
    fun getClientApi(): VoiceChatClientAPI = clientApiInstance

    private class ServerApiImpl : VoiceChatServerAPI {
        override fun isPlayerSpeaking(playerUuid: UUID): Boolean {
            return ServerVoiceManager.isPlayerSpeaking(playerUuid)
        }

        override fun getPlayerRadioFrequency(playerUuid: UUID): Double? {
            val player = ServerVoiceManager.getPlayerByUuid(playerUuid) ?: return null
            val stack = player.mainHandItem
            return if (stack.item is RadioItem) RadioItem.getFrequency(stack) else null
        }

        override fun setPlayerRadioFrequency(playerUuid: UUID, frequency: Double) {
            val player = ServerVoiceManager.getPlayerByUuid(playerUuid) ?: return
            val stack = player.mainHandItem
            if (stack.item is RadioItem) {
                RadioItem.setFrequency(stack, frequency)
            }
        }

        override fun setPlayerMutedByServer(playerUuid: UUID, muted: Boolean) {
            ServerVoiceManager.setPlayerMuted(playerUuid, muted)
        }

        override fun createVoiceLink(playerA: UUID, playerB: UUID, bidirectional: Boolean, isolateProximity: Boolean) {
            ServerVoiceManager.router.addDirectLink(playerA, playerB, bidirectional, isolateProximity)
        }

        override fun removeVoiceLink(playerA: UUID, playerB: UUID, bidirectional: Boolean) {
            ServerVoiceManager.router.removeDirectLink(playerA, playerB, bidirectional)
        }

        override fun createVoiceGroup(groupName: String): UUID {
            return ServerVoiceManager.router.createVoiceGroup()
        }

        override fun addPlayerToGroup(groupUuid: UUID, playerUuid: UUID) {
            ServerVoiceManager.router.addPlayerToGroup(groupUuid, playerUuid)
        }

        override fun removePlayerFromGroup(groupUuid: UUID, playerUuid: UUID) {
            ServerVoiceManager.router.removePlayerFromGroup(groupUuid, playerUuid)
        }

        override fun disbandVoiceGroup(groupUuid: UUID) {
            ServerVoiceManager.router.disbandVoiceGroup(groupUuid)
        }
    }

    private class ClientApiImpl : VoiceChatClientAPI {
        override fun isLocalPlayerSpeaking(): Boolean {
            return com.ruskserver.modernvoicechat.client.gui.VoiceHudOverlay.isSpeakingCurrent
        }

        override fun isMicrophoneMuted(): Boolean = VoiceConfig.isMicMuted

        override fun setMicrophoneMuted(muted: Boolean) {
            VoiceConfig.isMicMuted = muted
            VoiceConfig.save()
        }

        override fun isSpeakerMuted(): Boolean = VoiceConfig.isSpeakerMuted

        override fun setSpeakerMuted(muted: Boolean) {
            VoiceConfig.isSpeakerMuted = muted
            VoiceConfig.save()
        }
    }
}
