package com.ruskserver.modernvoicechat.config

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * NeoForge Server ModConfig 仕様 (config/modernvoicechat-server.toml に保存).
 * サーバー管理者がボイスポート、可聴距離上限、ビットレート上限を自由に調整可能。
 */
object ServerConfig {
    val SPEC: ModConfigSpec
    
    val VOICE_PORT: ModConfigSpec.IntValue
    val VOICE_HOST: ModConfigSpec.ConfigValue<String>
    val MAX_BITRATE_BPS: ModConfigSpec.IntValue
    val VOICE_RANGE: ModConfigSpec.DoubleValue
    val RADIO_CLEAR_RANGE: ModConfigSpec.DoubleValue
    val RADIO_MAX_RANGE: ModConfigSpec.DoubleValue
    val KEEP_ALIVE_TIMEOUT_MS: ModConfigSpec.IntValue

    init {
        val builder = ModConfigSpec.Builder()

        builder.comment("ModernVoiceChat Server Configuration").push("server")

        VOICE_PORT = builder
            .comment("The UDP/QUIC port for the SFU Voice Server")
            .defineInRange("voicePort", 24454, 1024, 65535)

        VOICE_HOST = builder
            .comment("The external IP address or hostname for clients to connect to the Voice Server. Leave empty (\"\" ) to use the Minecraft server's connection address.")
            .define("voiceHost", "")

        MAX_BITRATE_BPS = builder
            .comment("The maximum allowed bitrate in bps for clients (e.g. 32000 = 32kbps)")
            .defineInRange("maxBitrateBps", 32000, 8000, 128000)

        VOICE_RANGE = builder
            .comment("The maximum 3D spatial voice audible range in blocks/meters")
            .defineInRange("voiceRange", 24.0, 1.0, 128.0)

        RADIO_CLEAR_RANGE = builder
            .comment("The distance in blocks/meters up to which radio voice remains 100% clear with zero noise")
            .defineInRange("radioClearRange", 700.0, 10.0, 5000.0)

        RADIO_MAX_RANGE = builder
            .comment("The maximum distance in blocks/meters for radio transmission before signal loss")
            .defineInRange("radioMaxRange", 1000.0, 10.0, 5000.0)

        KEEP_ALIVE_TIMEOUT_MS = builder
            .comment("Client voice session keep-alive timeout in milliseconds")
            .defineInRange("keepAliveTimeoutMs", 10000, 1000, 60000)

        builder.pop()
        SPEC = builder.build()
    }
}
