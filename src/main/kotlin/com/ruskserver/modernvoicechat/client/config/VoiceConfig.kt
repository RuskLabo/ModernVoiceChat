package com.ruskserver.modernvoicechat.client.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ruskserver.modernvoicechat.audio.AudioDeviceUtils
import com.ruskserver.modernvoicechat.client.ClientVoiceManager
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigCategory
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.Optional
import java.util.function.Function

object VoiceConfig {
    private val logger = LoggerFactory.getLogger(VoiceConfig::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File
        get() = File(Minecraft.getInstance().gameDirectory, "config/modernvoicechat-client.json")

    enum class InputMode {
        VOICE_ACTIVATION,
        PUSH_TO_TALK
    }

    // 保存対象データ構造
    data class ConfigData(
        var inputMode: InputMode = InputMode.VOICE_ACTIVATION,
        var vadThresholdPercentage: Int = 3,
        var micVolumePercentage: Int = 100,
        var speakerVolumePercentage: Int = 100,
        var selectedMicrophoneDevice: String = AudioDeviceUtils.DEFAULT_DEVICE,
        var selectedSpeakerDevice: String = AudioDeviceUtils.DEFAULT_DEVICE,
        var isTutorialCompleted: Boolean = false,
        var playerVolumes: MutableMap<String, Int> = mutableMapOf()
    )

    private var data = ConfigData()

    var inputMode: InputMode
        get() = data.inputMode
        set(value) { data.inputMode = value }

    var vadThresholdPercentage: Int
        get() = data.vadThresholdPercentage
        set(value) { data.vadThresholdPercentage = value }

    var micVolumePercentage: Int
        get() = data.micVolumePercentage
        set(value) { data.micVolumePercentage = value }

    var speakerVolumePercentage: Int
        get() = data.speakerVolumePercentage
        set(value) { data.speakerVolumePercentage = value }

    var selectedMicrophoneDevice: String
        get() = data.selectedMicrophoneDevice
        set(value) { data.selectedMicrophoneDevice = value }

    var selectedSpeakerDevice: String
        get() = data.selectedSpeakerDevice
        set(value) { data.selectedSpeakerDevice = value }

    var isTutorialCompleted: Boolean
        get() = data.isTutorialCompleted
        set(value) { data.isTutorialCompleted = value }

    fun getPlayerVolume(playerUuid: java.util.UUID): Int {
        return data.playerVolumes[playerUuid.toString()] ?: 100
    }

    fun setPlayerVolume(playerUuid: java.util.UUID, volume: Int) {
        data.playerVolumes[playerUuid.toString()] = volume
    }

    // ランタイム状態
    var isMicMuted: Boolean = false
    var isSpeakerMuted: Boolean = false
    var isMicTestingEnabled: Boolean = false

    fun load() {
        try {
            val file = configFile
            if (file.exists()) {
                FileReader(file).use { reader ->
                    data = gson.fromJson(reader, ConfigData::class.java) ?: ConfigData()
                }
                logger.info("Loaded client voice config from ${file.absolutePath}")
            } else {
                save()
            }
        } catch (e: Exception) {
            logger.error("Failed to load client voice config", e)
        }
    }

    fun save() {
        try {
            val file = configFile
            file.parentFile?.mkdirs()
            FileWriter(file).use { writer ->
                gson.toJson(data, writer)
            }
            logger.info("Saved client voice config to ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to save client voice config", e)
        }
    }

    fun createScreen(parent: Screen?): Screen {
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("title.modernvoicechat.config"))
            .setSavingRunnable { save() }

        val entryBuilder: ConfigEntryBuilder = builder.entryBuilder()
        val audioCategory: ConfigCategory = builder.getOrCreateCategory(Component.translatable("category.modernvoicechat.audio"))
        val deviceCategory: ConfigCategory = builder.getOrCreateCategory(Component.translatable("category.modernvoicechat.devices"))
        val playersCategory: ConfigCategory = builder.getOrCreateCategory(Component.translatable("category.modernvoicechat.players"))

        // --- オーディオ設定カテゴリー ---

        // チュートリアル起動ボタン
        audioCategory.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable("option.modernvoicechat.restart_tutorial"),
                false
            )
            .setTooltip(Component.translatable("option.modernvoicechat.restart_tutorial.tooltip"))
            .setSaveConsumer { if (it) com.ruskserver.modernvoicechat.client.gui.FirstTimeTutorialScreen.open(builder.build()) }
            .build()
        )

        // 入力モード切替
        audioCategory.addEntry(
            entryBuilder.startEnumSelector(
                Component.translatable("option.modernvoicechat.input_mode"),
                InputMode::class.java,
                inputMode
            )
            .setDefaultValue(InputMode.VOICE_ACTIVATION)
            .setSaveConsumer { inputMode = it }
            .build()
        )

        // マイクテスト（ループバック再生）トグルボタン
        audioCategory.addEntry(
            entryBuilder.startBooleanToggle(
                Component.translatable("option.modernvoicechat.mic_test"),
                isMicTestingEnabled
            )
            .setDefaultValue(false)
            .setTooltip(Component.literal("ONにするとマイク音声を自分自身のスピーカーでループバック再生してテストできます"))
            .setSaveConsumer { isMicTestingEnabled = it }
            .build()
        )

        // VAD感度スライダー & リアルタイム音量メーター指示
        audioCategory.addEntry(
            entryBuilder.startIntSlider(
                Component.translatable("option.modernvoicechat.vad_threshold"),
                vadThresholdPercentage,
                1,
                30
            )
            .setDefaultValue(3)
            .setTooltipSupplier(Function<Int, Optional<Array<Component>>> { valVal ->
                val currentMicLevel = ClientVoiceManager.recorder?.currentRmsPercentage ?: 0
                val barLength = 10
                val filled = (currentMicLevel / 10).coerceIn(0, barLength)
                val meterBar = "█".repeat(filled) + "░".repeat(barLength - filled)

                val status = if (currentMicLevel >= (valVal * 3.3)) "【発話検知中 (TALKING)】" else "【待機中 (SILENT)】"

                Optional.of(arrayOf(
                    Component.literal("マイク音量レベル: [$meterBar] $currentMicLevel%"),
                    Component.literal("判定ステータス: $status"),
                    Component.literal("※バーが感度設定を超えるとマイクがオンになります")
                ))
            })
            .setSaveConsumer { newValue -> vadThresholdPercentage = newValue }
            .build()
        )

        // マイク音量
        audioCategory.addEntry(
            entryBuilder.startIntSlider(
                Component.translatable("option.modernvoicechat.mic_volume"),
                micVolumePercentage,
                0,
                200
            )
            .setDefaultValue(100)
            .setSaveConsumer { micVolumePercentage = it }
            .build()
        )

        // スピーカー音量
        audioCategory.addEntry(
            entryBuilder.startIntSlider(
                Component.translatable("option.modernvoicechat.speaker_volume"),
                speakerVolumePercentage,
                0,
                200
            )
            .setDefaultValue(100)
            .setSaveConsumer { speakerVolumePercentage = it }
            .build()
        )

        // --- デバイス設定カテゴリー ---

        val availableMics = AudioDeviceUtils.getAvailableMicrophones()
        val availableSpeakers = AudioDeviceUtils.getAvailableSpeakers()

        deviceCategory.addEntry(
            entryBuilder.startStringDropdownMenu(
                Component.translatable("option.modernvoicechat.mic_device"),
                selectedMicrophoneDevice
            ) { Component.literal(it) }
            .setSelections(availableMics)
            .setDefaultValue(AudioDeviceUtils.DEFAULT_DEVICE)
            .setSaveConsumer { newValue -> selectedMicrophoneDevice = newValue }
            .build()
        )

        deviceCategory.addEntry(
            entryBuilder.startStringDropdownMenu(
                Component.translatable("option.modernvoicechat.speaker_device"),
                selectedSpeakerDevice
            ) { Component.literal(it) }
            .setSelections(availableSpeakers)
            .setDefaultValue(AudioDeviceUtils.DEFAULT_DEVICE)
            .setSaveConsumer { newValue -> selectedSpeakerDevice = newValue }
            .build()
        )

        // --- プレイヤー別音量設定カテゴリー ---

        val mc = Minecraft.getInstance()
        val localPlayerUuid = mc.player?.uuid
        val onlinePlayers = mc.connection?.onlinePlayers?.filter { it.profile.id != localPlayerUuid } ?: emptyList()

        if (onlinePlayers.isNotEmpty()) {
            for (playerInfo in onlinePlayers) {
                val uuid = playerInfo.profile.id
                val name = playerInfo.profile.name
                val currentVol = getPlayerVolume(uuid)

                playersCategory.addEntry(
                    entryBuilder.startIntSlider(
                        Component.translatable("option.modernvoicechat.player_volume", name),
                        currentVol,
                        0,
                        200
                    )
                    .setDefaultValue(100)
                    .setSaveConsumer { newVol -> setPlayerVolume(uuid, newVol) }
                    .build()
                )
            }
        } else {
            // 保存済みの他プレイヤー音量設定があればそれを表示、なければ「オンラインプレイヤーなし」ラベルを表示
            val savedVolumes = data.playerVolumes
            if (savedVolumes.isNotEmpty()) {
                for ((uuidStr, vol) in savedVolumes) {
                    try {
                        val uuid = java.util.UUID.fromString(uuidStr)
                        playersCategory.addEntry(
                            entryBuilder.startIntSlider(
                                Component.literal("Player [$uuidStr]"),
                                vol,
                                0,
                                200
                            )
                            .setDefaultValue(100)
                            .setSaveConsumer { newVol -> setPlayerVolume(uuid, newVol) }
                            .build()
                        )
                    } catch (_: Exception) {}
                }
            } else {
                playersCategory.addEntry(
                    entryBuilder.startTextDescription(
                        Component.translatable("option.modernvoicechat.no_players")
                    ).build()
                )
            }
        }

        return builder.build()
    }
}
