package com.ruskserver.modernvoicechat.client.gui

import net.minecraft.client.gui.GuiGraphics
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderNameTagEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 発話中の他プレイヤーのネームタグ横に緑色の発話インジケーターを描画するハンドラー。
 */
object NameTagIcons {
    // 現在発話中のプレイヤーUUIDセット
    private val activeSpeakers = ConcurrentHashMap.newKeySet<UUID>()

    fun setPlayerSpeaking(playerUuid: UUID, speaking: Boolean) {
        if (speaking) {
            activeSpeakers.add(playerUuid)
        } else {
            activeSpeakers.remove(playerUuid)
        }
    }

    fun isPlayerSpeaking(playerUuid: UUID): Boolean {
        return activeSpeakers.contains(playerUuid)
    }

    @SubscribeEvent
    fun onRenderNameTag(event: RenderNameTagEvent) {
        val entity = event.entity
        val playerUuid = entity.uuid

        if (activeSpeakers.contains(playerUuid)) {
            // ネームタグ横に「[🔊]」テキストバッジを追加付与
            val currentContent = event.content.copy()
            val speakingBadge = net.minecraft.network.chat.Component.literal(" 🔊").withStyle(net.minecraft.ChatFormatting.GREEN)
            event.content = currentContent.append(speakingBadge)
        }
    }
}
