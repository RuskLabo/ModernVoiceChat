package com.ruskserver.modernvoicechat.client

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent

object ClientEventHandler {

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.player == null) return

        // Vキー: 設定画面オープン
        while (KeyMappings.OPEN_SETTINGS.consumeClick()) {
            mc.setScreen(VoiceConfig.createScreen(mc.screen))
        }

        // Mキー: マイクミュート切替
        while (KeyMappings.MUTE_MIC.consumeClick()) {
            VoiceConfig.isMicMuted = !VoiceConfig.isMicMuted
            val msg = if (VoiceConfig.isMicMuted) "message.modernvoicechat.mic_muted" else "message.modernvoicechat.mic_unmuted"
            mc.player?.displayClientMessage(net.minecraft.network.chat.Component.translatable(msg), true)
        }

        // Nキー: スピーカーミュート切替
        while (KeyMappings.MUTE_SPEAKER.consumeClick()) {
            VoiceConfig.isSpeakerMuted = !VoiceConfig.isSpeakerMuted
            val msg = if (VoiceConfig.isSpeakerMuted) "message.modernvoicechat.speaker_muted" else "message.modernvoicechat.speaker_unmuted"
            mc.player?.displayClientMessage(net.minecraft.network.chat.Component.translatable(msg), true)
        }
    }

    @SubscribeEvent
    fun onLeftClickEmpty(event: net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty) {
        val stack = event.itemStack
        if (stack.item is com.ruskserver.modernvoicechat.item.RadioItem) {
            com.ruskserver.modernvoicechat.client.gui.RadioScreen.open()
        }
    }

    @SubscribeEvent
    fun onLeftClickBlock(event: net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock) {
        val stack = event.itemStack
        if (stack.item is com.ruskserver.modernvoicechat.item.RadioItem) {
            if (event.level.isClientSide) {
                com.ruskserver.modernvoicechat.client.gui.RadioScreen.open()
            }
        }
    }

    @SubscribeEvent
    fun onLoggingIn(event: net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn) {
        if (!VoiceConfig.isTutorialCompleted) {
            Minecraft.getInstance().tell {
                com.ruskserver.modernvoicechat.client.gui.FirstTimeTutorialScreen.open()
            }
        }
    }
}
