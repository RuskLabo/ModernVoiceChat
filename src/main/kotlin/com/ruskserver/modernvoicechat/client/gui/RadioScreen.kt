package com.ruskserver.modernvoicechat.client.gui

import com.ruskserver.modernvoicechat.item.RadioItem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 無線機アイテムの周波数・チャンネル変更 UI スクリーン。
 */
class RadioScreen : Screen(Component.literal("無線機 周波数設定")) {

    private lateinit var freqEditBox: EditBox

    override fun init() {
        val centerX = this.width / 2
        val centerY = this.height / 2

        val mc = Minecraft.getInstance()
        val player = mc.player
        val currentStack = if (player != null && player.mainHandItem.item is RadioItem) {
            player.mainHandItem
        } else if (player != null && player.offhandItem.item is RadioItem) {
            player.offhandItem
        } else null

        val currentFreq = if (currentStack != null) RadioItem.getFrequency(currentStack) else 144.00

        // 周波数入力ボックス
        freqEditBox = EditBox(this.font, centerX - 60, centerY - 20, 120, 20, Component.literal("周波数 (MHz)"))
        freqEditBox.value = String.format("%.2f", currentFreq)
        freqEditBox.setMaxLength(8)
        this.addRenderableWidget(freqEditBox)

        // 完了ボタン
        this.addRenderableWidget(
            Button.builder(Component.literal("設定保存")) { _ ->
                try {
                    val newFreq = freqEditBox.value.toDouble()
                    if (currentStack != null) {
                        RadioItem.setFrequency(currentStack, newFreq)
                    }
                } catch (_: Exception) {}
                this.onClose()
            }.bounds(centerX - 60, centerY + 10, 120, 20).build()
        )
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        super.render(guiGraphics, mouseX, mouseY, partialTick)

        val centerX = this.width / 2
        val centerY = this.height / 2
        guiGraphics.drawCenteredString(this.font, this.title, centerX, centerY - 45, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, "§7周波数 (MHz) を入力してください", centerX, centerY - 32, 0xAAAAAA)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        fun open() {
            Minecraft.getInstance().setScreen(RadioScreen())
        }
    }
}
