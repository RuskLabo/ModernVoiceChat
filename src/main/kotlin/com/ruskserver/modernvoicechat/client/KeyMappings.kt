package com.ruskserver.modernvoicechat.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import org.lwjgl.glfw.GLFW

object KeyMappings {
    const val CATEGORY = "key.categories.modernvoicechat"

    val OPEN_SETTINGS = KeyMapping(
        "key.modernvoicechat.open_settings",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_V,
        CATEGORY
    )

    val MUTE_MIC = KeyMapping(
        "key.modernvoicechat.mute_mic",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_M,
        CATEGORY
    )

    val MUTE_SPEAKER = KeyMapping(
        "key.modernvoicechat.mute_speaker",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_N,
        CATEGORY
    )

    val PUSH_TO_TALK = KeyMapping(
        "key.modernvoicechat.push_to_talk",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_LEFT_ALT,
        CATEGORY
    )

    fun register(event: RegisterKeyMappingsEvent) {
        event.register(OPEN_SETTINGS)
        event.register(MUTE_MIC)
        event.register(MUTE_SPEAKER)
        event.register(PUSH_TO_TALK)
    }
}
