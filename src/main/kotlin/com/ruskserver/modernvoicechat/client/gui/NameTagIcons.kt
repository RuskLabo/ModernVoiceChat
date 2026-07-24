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
    private const val SPEAKING_TIMEOUT_NANOS = 500_000_000L
    private val lastPacketTimes = ConcurrentHashMap<UUID, Long>()

    fun setPlayerSpeaking(playerUuid: UUID, speaking: Boolean) {
        if (speaking) {
            lastPacketTimes[playerUuid] = System.nanoTime()
        } else {
            lastPacketTimes.remove(playerUuid)
        }
    }

    fun isPlayerSpeaking(playerUuid: UUID): Boolean {
        val lastPacketTime = lastPacketTimes[playerUuid] ?: return false
        if (System.nanoTime() - lastPacketTime <= SPEAKING_TIMEOUT_NANOS) return true
        lastPacketTimes.remove(playerUuid, lastPacketTime)
        return false
    }

    @SubscribeEvent
    fun onRenderNameTag(event: RenderNameTagEvent) {
        val entity = event.entity
        val playerUuid = entity.uuid

        if (isPlayerSpeaking(playerUuid)) {
            // ネームタグ横に「[🔊]」テキストバッジを追加付与
            val currentContent = event.content.copy()
            val speakingBadge = net.minecraft.network.chat.Component.literal(" 🔊").withStyle(net.minecraft.ChatFormatting.GREEN)
            event.content = currentContent.append(speakingBadge)
        }
    }
}
