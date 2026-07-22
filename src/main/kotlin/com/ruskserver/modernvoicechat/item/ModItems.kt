package com.ruskserver.modernvoicechat.item

import com.ruskserver.modernvoicechat.Modernvoicechat
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister

object ModItems {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Modernvoicechat.ID)

    val RADIO: DeferredItem<RadioItem> = ITEMS.register("radio") { ->
        RadioItem(Item.Properties().stacksTo(1))
    }

    fun register(eventBus: IEventBus) {
        ITEMS.register(eventBus)
    }
}
