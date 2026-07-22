package com.ruskserver.modernvoicechat.api.event

import net.neoforged.bus.api.Event
import java.util.UUID

/**
 * プレイヤーが音声を発話し始めた、または発話を停止した際に発火する NeoForge イベント。
 */
class PlayerSpeakingEvent(
    val playerUuid: UUID,
    val isSpeaking: Boolean
) : Event()
