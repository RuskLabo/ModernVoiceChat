package com.ruskserver.modernvoicechat.item

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import java.util.List

/**
 * 長距離無線通信を可能にする「ハンドヘルド無線機 (Radio)」アイテム。
 */
class RadioItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack, entity: net.minecraft.world.entity.LivingEntity): Int = 72000

    override fun getUseAnimation(stack: ItemStack): net.minecraft.world.item.UseAnim = net.minecraft.world.item.UseAnim.SPYGLASS

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltipComponents: MutableList<Component>,
        tooltipFlag: TooltipFlag
    ) {
        val freq = getFrequency(stack)
        tooltipComponents.add(Component.literal("§7周波数: §b${String.format("%.2f", freq)} MHz"))
        tooltipComponents.add(Component.literal("§8[右クリック長押し] 無線送信 (PTT)"))
        tooltipComponents.add(Component.literal("§8[左クリック] 周波数・チャンネル設定"))
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag)
    }

    companion object {
        fun getFrequency(stack: ItemStack): Double {
            val stored = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getDouble("modernvoicechat_frequency")
            if (stored.isFinite() && stored in 30.0..3000.0) return stored
            val customName = stack.get(DataComponents.CUSTOM_NAME)
            return try {
                val text = customName?.string ?: ""
                val legacy = if (text.startsWith("Freq:")) text.substring(5).toDouble() else 144.00
                if (legacy.isFinite() && legacy in 30.0..3000.0) legacy else 144.00
            } catch (e: Exception) {
                144.00
            }
        }

        fun setFrequency(stack: ItemStack, frequency: Double) {
            val value = if (frequency.isFinite()) frequency.coerceIn(30.0, 3000.0) else 144.00
            CustomData.update(DataComponents.CUSTOM_DATA, stack) {
                it.putDouble("modernvoicechat_frequency", value)
            }
        }
    }
}
