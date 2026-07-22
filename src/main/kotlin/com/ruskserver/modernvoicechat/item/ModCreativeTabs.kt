package com.ruskserver.modernvoicechat.item

import com.ruskserver.modernvoicechat.Modernvoicechat
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

object ModCreativeTabs {
    val CREATIVE_MODE_TABS: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Modernvoicechat.ID)

    val VOICE_CHAT_TAB: DeferredHolder<CreativeModeTab, CreativeModeTab> =
        CREATIVE_MODE_TABS.register("tab") { ->
            CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.modernvoicechat"))
                .icon { ItemStack(ModItems.RADIO.get()) }
                .displayItems { _, output ->
                    output.accept(ModItems.RADIO.get())
                }
                .build()
        }

    fun register(eventBus: IEventBus) {
        CREATIVE_MODE_TABS.register(eventBus)
    }
}
