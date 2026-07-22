package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.client.ClientEventHandler
import com.ruskserver.modernvoicechat.client.ClientVoiceManager
import com.ruskserver.modernvoicechat.client.KeyMappings
import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import com.ruskserver.modernvoicechat.client.gui.NameTagIcons
import com.ruskserver.modernvoicechat.client.gui.VoiceHudOverlay
import com.ruskserver.modernvoicechat.config.ServerConfig
import com.ruskserver.modernvoicechat.network.ModNetwork
import com.ruskserver.modernvoicechat.server.ServerVoiceManager
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(Modernvoicechat.ID)
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
object Modernvoicechat {
    const val ID = "modernvoicechat"

    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        LOGGER.log(Level.INFO, "Initializing ModernVoiceChat mod...")

        try {
            ModLoadingContext.get().activeContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC)
        } catch (e: Throwable) {
            LOGGER.log(Level.WARN, "Registering config via activeContainer fallback", e)
        }

        com.ruskserver.modernvoicechat.item.ModItems.register(MOD_BUS)

        MOD_BUS.addListener(this::registerPayloads)
        MOD_BUS.addListener(this::registerKeyMappings)
        MOD_BUS.addListener(this::registerGuiLayers)
        MOD_BUS.addListener(this::onClientSetup)
        MOD_BUS.addListener(this::onServerSetup)

        NeoForge.EVENT_BUS.register(ServerVoiceManager)
        NeoForge.EVENT_BUS.register(ClientVoiceManager)
        NeoForge.EVENT_BUS.register(ClientEventHandler)
        NeoForge.EVENT_BUS.register(NameTagIcons)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        ModNetwork.register(event)
    }

    private fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        KeyMappings.register(event)
    }

    private fun registerGuiLayers(event: RegisterGuiLayersEvent) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(ID, "voice_hud"),
            VoiceHudOverlay
        )
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.log(Level.INFO, "ModernVoiceChat Client Initializing & Loading Config...")
        VoiceConfig.load()
    }

    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.log(Level.INFO, "ModernVoiceChat Dedicated Server Initialized.")
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.log(Level.INFO, "ModernVoiceChat Common Setup Complete.")
    }
}
