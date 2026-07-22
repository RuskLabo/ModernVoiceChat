package com.ruskserver.modernvoicechat.client.gui

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.LayeredDraw
import net.minecraft.network.chat.Component

/**
 * 画面左下に現在のボイスチャット状態（発話中/待機中/ミュート中）をクリアに表示するHUDオーバーレイ。
 */
object VoiceHudOverlay : LayeredDraw.Layer {

    var isSpeakingCurrent: Boolean = false

    override fun render(guiGraphics: GuiGraphics, deltaTracker: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.player == null) return

        val font = mc.font
        val padding = 4
        val x = 10
        val y = guiGraphics.guiHeight() - 25

        val (statusText, colorBg, colorText) = when {
            VoiceConfig.isSpeakerMuted -> Triple("🔇 SPK MUTED", 0xDDCC0000.toInt(), 0xFFFFFFFF.toInt())
            VoiceConfig.isMicMuted -> Triple("🔇 MIC MUTED", 0xDDCC0000.toInt(), 0xFFFFFFFF.toInt())
            isSpeakingCurrent -> Triple("🎤 TALKING...", 0xFF00AA00.toInt(), 0xFFFFFFFF.toInt())
            else -> Triple("🎤 IDLE", 0x88444444.toInt(), 0xFFDDDDDD.toInt())
        }

        val textWidth = font.width(statusText)
        val rectWidth = textWidth + padding * 2
        val rectHeight = font.lineHeight + padding

        // 背景バッジ描画
        guiGraphics.fill(x, y, x + rectWidth, y + rectHeight, colorBg)
        // 枠線描画
        guiGraphics.renderOutline(x, y, rectWidth, rectHeight, 0xFF000000.toInt())
        // テキスト描画
        guiGraphics.drawString(font, Component.literal(statusText), x + padding, y + padding / 2 + 1, colorText, true)
    }
}
