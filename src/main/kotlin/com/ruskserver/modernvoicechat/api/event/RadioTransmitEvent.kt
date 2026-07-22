package com.ruskserver.modernvoicechat.api.event

import net.neoforged.bus.api.Event
import java.util.UUID

/**
 * プレイヤーが無線機で送信を行った際に発火する NeoForge イベント。
 */
class RadioTransmitEvent(
    val playerUuid: UUID,
    val frequency: Double,
    val isTransmitting: Boolean
) : Event()
