package com.ruskserver.modernvoicechat.item

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import java.util.List

/**
 * 長距離無線通信を可能にする「ハンドヘルド無線機 (Radio)」アイテム。
 */
class RadioItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) {
            // クライアント側で周波数設定 UI を起動
            com.ruskserver.modernvoicechat.client.gui.RadioScreen.open()
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltipComponents: MutableList<Component>,
        tooltipFlag: TooltipFlag
    ) {
        val freq = getFrequency(stack)
        tooltipComponents.add(Component.literal("§7周波数: §b${String.format("%.2f", freq)} MHz"))
        tooltipComponents.add(Component.literal("§8[右クリック] 周波数・チャンネル設定"))
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag)
    }

    companion object {
        fun getFrequency(stack: ItemStack): Double {
            val customName = stack.get(DataComponents.CUSTOM_NAME)
            return try {
                val text = customName?.string ?: ""
                if (text.startsWith("Freq:")) text.substring(5).toDouble() else 144.00
            } catch (e: Exception) {
                144.00
            }
        }

        fun setFrequency(stack: ItemStack, frequency: Double) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Freq:${String.format("%.2f", frequency)}"))
        }
    }
}
