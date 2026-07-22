package com.ruskserver.modernvoicechat.client.gui

import com.ruskserver.modernvoicechat.audio.AudioDeviceUtils
import com.ruskserver.modernvoicechat.client.ClientVoiceManager
import com.ruskserver.modernvoicechat.client.KeyMappings
import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 初回Mod導入・参加プレイヤー向けのインタラクティブ初期設定セットアップ・チュートリアルウィザード Screen。
 * 各ステップで直接マイク・スピーカーの切替、入力モード(VAD/PTT)変更、リアルタイムテストが可能です。
 */
class FirstTimeTutorialScreen(
    private val parent: Screen? = null
) : Screen(Component.translatable("tutorial.modernvoicechat.title")) {

    private var currentStep = 1
    private val totalSteps = 4

    private lateinit var nextButton: Button
    private lateinit var backButton: Button

    // ステップ専用インタラクティブボタン
    private var micButton: Button? = null
    private var speakerButton: Button? = null
    private var inputModeButton: Button? = null
    private var vadThresholdButton: Button? = null
    private var micVolumeButton: Button? = null

    private val availableMics by lazy { AudioDeviceUtils.getAvailableMicrophones() }
    private val availableSpeakers by lazy { AudioDeviceUtils.getAvailableSpeakers() }

    override fun init() {
        val centerX = this.width / 2
        val centerY = this.height / 2

        VoiceConfig.isMicTestingEnabled = true
        ClientVoiceManager.startLoopback()

        backButton = this.addRenderableWidget(
            Button.builder(Component.translatable("tutorial.modernvoicechat.btn.back")) {
                if (currentStep > 1) {
                    currentStep--
                    rebuildStepWidgets()
                }
            }.bounds(centerX - 130, centerY + 70, 80, 20).build()
        )

        nextButton = this.addRenderableWidget(
            Button.builder(Component.translatable("tutorial.modernvoicechat.btn.next")) {
                if (currentStep < totalSteps) {
                    currentStep++
                    rebuildStepWidgets()
                } else {
                    finishSetup()
                }
            }.bounds(centerX + 50, centerY + 70, 80, 20).build()
        )

        rebuildStepWidgets()
    }

    private fun removeStepWidgets() {
        micButton?.let { this.removeWidget(it) }
        speakerButton?.let { this.removeWidget(it) }
        inputModeButton?.let { this.removeWidget(it) }
        vadThresholdButton?.let { this.removeWidget(it) }
        micVolumeButton?.let { this.removeWidget(it) }

        micButton = null
        speakerButton = null
        inputModeButton = null
        vadThresholdButton = null
        micVolumeButton = null
    }

    private fun rebuildStepWidgets() {
        removeStepWidgets()

        val centerX = this.width / 2
        val centerY = this.height / 2

        backButton.active = currentStep > 1
        nextButton.message = if (currentStep == totalSteps) Component.translatable("tutorial.modernvoicechat.btn.finish") else Component.translatable("tutorial.modernvoicechat.btn.next")

        when (currentStep) {
            1 -> {
                // Step 1: マイク ＆ スピーカー選択ボタン
                val micName = VoiceConfig.selectedMicrophoneDevice.ifBlank { "Default" }
                micButton = this.addRenderableWidget(
                    Button.builder(Component.literal("🎤 マイク: $micName")) { btn ->
                        if (availableMics.isNotEmpty()) {
                            val currentIndex = availableMics.indexOf(VoiceConfig.selectedMicrophoneDevice).coerceAtLeast(0)
                            val nextIndex = (currentIndex + 1) % availableMics.size
                            VoiceConfig.selectedMicrophoneDevice = availableMics[nextIndex]
                            btn.message = Component.literal("🎤 マイク: ${VoiceConfig.selectedMicrophoneDevice}")
                        }
                    }.bounds(centerX - 110, centerY - 25, 220, 20).build()
                )

                val speakerName = VoiceConfig.selectedSpeakerDevice.ifBlank { "Default" }
                speakerButton = this.addRenderableWidget(
                    Button.builder(Component.literal("🔊 スピーカー: $speakerName")) { btn ->
                        if (availableSpeakers.isNotEmpty()) {
                            val currentIndex = availableSpeakers.indexOf(VoiceConfig.selectedSpeakerDevice).coerceAtLeast(0)
                            val nextIndex = (currentIndex + 1) % availableSpeakers.size
                            VoiceConfig.selectedSpeakerDevice = availableSpeakers[nextIndex]
                            btn.message = Component.literal("🔊 スピーカー: ${VoiceConfig.selectedSpeakerDevice}")
                        }
                    }.bounds(centerX - 110, centerY + 2, 220, 20).build()
                )
            }
            2 -> {
                // Step 2: 入力モード ＆ VAD感度変更ボタン
                val modeText = if (VoiceConfig.inputMode == VoiceConfig.InputMode.VOICE_ACTIVATION) "音声検知 (VAD)" else "Push-to-Talk (PTT)"
                inputModeButton = this.addRenderableWidget(
                    Button.builder(Component.literal("入力モード: $modeText")) { btn ->
                        VoiceConfig.inputMode = if (VoiceConfig.inputMode == VoiceConfig.InputMode.VOICE_ACTIVATION) {
                            VoiceConfig.InputMode.PUSH_TO_TALK
                        } else {
                            VoiceConfig.InputMode.VOICE_ACTIVATION
                        }
                        val newModeText = if (VoiceConfig.inputMode == VoiceConfig.InputMode.VOICE_ACTIVATION) "音声検知 (VAD)" else "Push-to-Talk (PTT)"
                        btn.message = Component.literal("入力モード: $newModeText")
                        rebuildStepWidgets()
                    }.bounds(centerX - 110, centerY - 45, 220, 20).build()
                )

                if (VoiceConfig.inputMode == VoiceConfig.InputMode.VOICE_ACTIVATION) {
                    vadThresholdButton = this.addRenderableWidget(
                        Button.builder(Component.literal("VAD 検出感度: ${VoiceConfig.vadThresholdPercentage}% (クリックで変更)")) { btn ->
                            var nextVal = VoiceConfig.vadThresholdPercentage + 2
                            if (nextVal > 20) nextVal = 1
                            VoiceConfig.vadThresholdPercentage = nextVal
                            btn.message = Component.literal("VAD 検出感度: ${VoiceConfig.vadThresholdPercentage}% (クリックで変更)")
                        }.bounds(centerX - 110, centerY - 20, 220, 20).build()
                    )
                }

                // マイク音量調整ボタン
                micVolumeButton = this.addRenderableWidget(
                    Button.builder(Component.literal("🎤 マイク音量: ${VoiceConfig.micVolumePercentage}%")) { btn ->
                        val levels = listOf(0, 50, 75, 100, 125, 150, 200)
                        val currentIndex = levels.indexOf(VoiceConfig.micVolumePercentage).coerceAtLeast(0)
                        val nextLevel = levels[(currentIndex + 1) % levels.size]
                        VoiceConfig.micVolumePercentage = nextLevel
                        btn.message = Component.literal("🎤 マイク音量: ${VoiceConfig.micVolumePercentage}%")
                    }.bounds(centerX - 110, centerY + 5, 220, 20).build()
                )
            }
        }
    }

    private fun finishSetup() {
        VoiceConfig.isTutorialCompleted = true
        VoiceConfig.isMicTestingEnabled = false
        ClientVoiceManager.stopLoopback()
        VoiceConfig.save()
        this.onClose()
    }

    override fun onClose() {
        VoiceConfig.isMicTestingEnabled = false
        ClientVoiceManager.stopLoopback()
        Minecraft.getInstance().setScreen(parent)
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        super.render(guiGraphics, mouseX, mouseY, partialTick)

        val centerX = this.width / 2
        val centerY = this.height / 2

        guiGraphics.drawCenteredString(this.font, "${Component.translatable("tutorial.modernvoicechat.title").string} ($currentStep / $totalSteps)", centerX, 15, 0x55FF55)
        val progressBar = "■".repeat(currentStep) + "□".repeat(totalSteps - currentStep)
        guiGraphics.drawCenteredString(this.font, "[$progressBar]", centerX, 28, 0xAAAAAA)

        when (currentStep) {
            1 -> renderStep1(guiGraphics, centerX, centerY)
            2 -> renderStep2(guiGraphics, centerX, centerY)
            3 -> renderStep3(guiGraphics, centerX, centerY)
            4 -> renderStep4(guiGraphics, centerX, centerY)
        }
    }

    private fun renderStep1(guiGraphics: GuiGraphics, centerX: Int, centerY: Int) {
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step1.title"), centerX, centerY - 65, 0xFFFF55)
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step1.desc"), centerX, centerY - 50, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step1.note"), centerX, centerY + 35, 0x888888)
    }

    private fun renderStep2(guiGraphics: GuiGraphics, centerX: Int, centerY: Int) {
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step2.title"), centerX, centerY - 70, 0xFFFF55)

        val pttKeyName = KeyMappings.PUSH_TO_TALK.key.displayName.string
        if (VoiceConfig.inputMode == VoiceConfig.InputMode.PUSH_TO_TALK) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step2.ptt_hint", pttKeyName), centerX, centerY + 5, 0xFFFFAA)
        } else {
            guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step2.vad_hint"), centerX, centerY + 5, 0xAAAAAA)
        }

        // リアルタイムマイクレベルメーター
        val micLevel = ClientVoiceManager.recorder?.currentRmsPercentage ?: 0
        val maxBarWidth = 180
        val filledWidth = (micLevel * maxBarWidth / 100).coerceIn(0, maxBarWidth)

        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step2.mic_level"), centerX - 90, centerY + 22, 0xFFFFFF)
        guiGraphics.fill(centerX - 90, centerY + 33, centerX - 90 + maxBarWidth, centerY + 43, -0x77777778)
        guiGraphics.fill(centerX - 90, centerY + 33, centerX - 90 + filledWidth, centerY + 43, if (micLevel > 5) -0x1000000 or 0x00FF00 else -0x1000000 or 0x888888)
        guiGraphics.drawString(this.font, "$micLevel%", centerX + 95, centerY + 34, 0xFFFFFF)

        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step2.hear_test"), centerX, centerY + 50, 0xAAFFFF)
    }

    private fun renderStep3(guiGraphics: GuiGraphics, centerX: Int, centerY: Int) {
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step3.title"), centerX, centerY - 70, 0xFFFF55)

        val openSettingsKey = KeyMappings.OPEN_SETTINGS.key.displayName.string
        val pttKeyName = KeyMappings.PUSH_TO_TALK.key.displayName.string
        val muteMicKey = KeyMappings.MUTE_MIC.key.displayName.string
        val muteSpeakerKey = KeyMappings.MUTE_SPEAKER.key.displayName.string

        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step3.key_settings", openSettingsKey), centerX - 110, centerY - 45, 0xFFFFFF)
        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step3.key_ptt", pttKeyName), centerX - 110, centerY - 30, 0xFFFFFF)
        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step3.key_mute_mic", muteMicKey), centerX - 110, centerY - 15, 0xFFFFFF)
        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step3.key_mute_speaker", muteSpeakerKey), centerX - 110, centerY, 0xFFFFFF)

        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step3.radio_title"), centerX - 110, centerY + 20, 0xFFFFAA)
        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step3.radio_right_click"), centerX - 100, centerY + 33, 0xDDDDDD)
        guiGraphics.drawString(this.font, Component.translatable("tutorial.modernvoicechat.step3.radio_left_click"), centerX - 100, centerY + 45, 0xDDDDDD)
    }

    private fun renderStep4(guiGraphics: GuiGraphics, centerX: Int, centerY: Int) {
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step4.title"), centerX, centerY - 45, 0x55FF55)
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step4.desc1"), centerX, centerY - 25, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step4.desc2"), centerX, centerY - 10, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, Component.translatable("tutorial.modernvoicechat.step4.desc3"), centerX, centerY + 20, 0xAAAAAA)
    }

    companion object {
        fun open(parent: Screen? = null) {
            Minecraft.getInstance().setScreen(FirstTimeTutorialScreen(parent))
        }
    }
}
