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
 * 初回Mod導入・参加プレイヤー向けの初期設定セットアップ・チュートリアルウィザード Screen。
 */
class FirstTimeTutorialScreen(
    private val parent: Screen? = null
) : Screen(Component.literal("ModernVoiceChat 初回セットアップガイド")) {

    private var currentStep = 1
    private val totalSteps = 4

    private lateinit var nextButton: Button
    private lateinit var backButton: Button

    override fun init() {
        val centerX = this.width / 2
        val centerY = this.height / 2

        // マイクテスト用ループバックを起動
        VoiceConfig.isMicTestingEnabled = true
        ClientVoiceManager.startLoopback()

        // 戻るボタン
        backButton = this.addRenderableWidget(
            Button.builder(Component.literal("◀ 戻る")) {
                if (currentStep > 1) {
                    currentStep--
                    rebuildStepWidgets()
                }
            }.bounds(centerX - 130, centerY + 70, 80, 20).build()
        )

        // 次へ / 完了ボタン
        nextButton = this.addRenderableWidget(
            Button.builder(Component.literal("次へ ▶")) {
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

    private fun rebuildStepWidgets() {
        backButton.active = currentStep > 1
        nextButton.message = if (currentStep == totalSteps) Component.literal("✔ 完了・保存") else Component.literal("次へ ▶")
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

        // タイトル ＆ プログレス表示
        guiGraphics.drawCenteredString(this.font, "🎙️ ModernVoiceChat 初回セットアップ ($currentStep / $totalSteps)", centerX, 15, 0x55FF55)
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
        guiGraphics.drawCenteredString(this.font, "【Step 1: デバイス選択】", centerX, centerY - 65, 0xFFFF55)
        guiGraphics.drawCenteredString(this.font, "使用するマイクとスピーカーを確認してください。", centerX, centerY - 50, 0xFFFFFF)

        val micName = VoiceConfig.selectedMicrophoneDevice.ifBlank { "既定のデバイス (Default)" }
        val speakerName = VoiceConfig.selectedSpeakerDevice.ifBlank { "既定のデバイス (Default)" }

        guiGraphics.drawString(this.font, "🎤 選択中のマイク: §a$micName", centerX - 120, centerY - 20, 0xFFFFFF)
        guiGraphics.drawString(this.font, "🔊 選択中のスピーカー: §a$speakerName", centerX - 120, centerY + 5, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, "※変更したい場合は後から [V] キー設定画面でいつでも変更可能です", centerX, centerY + 35, 0x888888)
    }

    private fun renderStep2(guiGraphics: GuiGraphics, centerX: Int, centerY: Int) {
        guiGraphics.drawCenteredString(this.font, "【Step 2: 入力モード ＆ マイクテスト】", centerX, centerY - 70, 0xFFFF55)

        val modeText = if (VoiceConfig.inputMode == VoiceConfig.InputMode.VOICE_ACTIVATION) "音声検知 (VAD)" else "Push-to-Talk (PTT)"
        guiGraphics.drawCenteredString(this.font, "現在の入力モード: §b$modeText", centerX, centerY - 55, 0xFFFFFF)

        // PTT キー案内の明記
        val pttKeyName = KeyMappings.PUSH_TO_TALK.key.displayName.string
        if (VoiceConfig.inputMode == VoiceConfig.InputMode.PUSH_TO_TALK) {
            guiGraphics.drawCenteredString(this.font, "👉 発話用 PTT キー: §e[$pttKeyName] §7(キーを押すと声が送られます)", centerX, centerY - 40, 0xFFFFAA)
        } else {
            guiGraphics.drawCenteredString(this.font, "👉 話すと自動で声を検知して送信します", centerX, centerY - 40, 0xAAAAAA)
        }

        // リアルタイムマイクレベルメーターアニメーション
        val micLevel = ClientVoiceManager.recorder?.currentRmsPercentage ?: 0
        val maxBarWidth = 180
        val filledWidth = (micLevel * maxBarWidth / 100).coerceIn(0, maxBarWidth)

        guiGraphics.drawString(this.font, "マイク音量レベル:", centerX - 90, centerY - 15, 0xFFFFFF)
        guiGraphics.fill(centerX - 90, centerY - 3, centerX - 90 + maxBarWidth, centerY + 9, -0x77777778)
        guiGraphics.fill(centerX - 90, centerY - 3, centerX - 90 + filledWidth, centerY + 9, if (micLevel > 5) -0x1000000 or 0x00FF00 else -0x1000000 or 0x888888)
        guiGraphics.drawString(this.font, "$micLevel%", centerX + 95, centerY - 2, 0xFFFFFF)

        guiGraphics.drawCenteredString(this.font, "🔊 マイクのテスト音がスピーカーから聞こえることを確認してください", centerX, centerY + 25, 0xAAFFFF)
    }

    private fun renderStep3(guiGraphics: GuiGraphics, centerX: Int, centerY: Int) {
        guiGraphics.drawCenteredString(this.font, "【Step 3: 操作キー ＆ 無線機ガイド】", centerX, centerY - 70, 0xFFFF55)

        val pttKeyName = KeyMappings.PUSH_TO_TALK.key.displayName.string

        guiGraphics.drawString(this.font, "⌨️ ボイス設定画面を開く  : §e[V] キー", centerX - 110, centerY - 45, 0xFFFFFF)
        guiGraphics.drawString(this.font, "🎙️ Push-to-Talk 発話     : §e[$pttKeyName] キー", centerX - 110, centerY - 30, 0xFFFFFF)
        guiGraphics.drawString(this.font, "🔇 マイクミュート切替     : §e[M] キー", centerX - 110, centerY - 15, 0xFFFFFF)
        guiGraphics.drawString(this.font, "🎧 スピーカーミュート切替 : §e[N] キー", centerX - 110, centerY, 0xFFFFFF)

        guiGraphics.drawString(this.font, "📻 無線機アイテムの操作:", centerX - 110, centerY + 20, 0xFFFFAA)
        guiGraphics.drawString(this.font, "  ・右クリック長押し ➔ 無線送信 (PTT)", centerX - 100, centerY + 33, 0xDDDDDD)
        guiGraphics.drawString(this.font, "  ・左クリック       ➔ 周波数・チャンネル変更", centerX - 100, centerY + 45, 0xDDDDDD)
    }

    private fun renderStep4(guiGraphics: GuiGraphics, centerX: Int, centerY: Int) {
        guiGraphics.drawCenteredString(this.font, "🎉 セットアップ完了！", centerX, centerY - 45, 0x55FF55)
        guiGraphics.drawCenteredString(this.font, "すべての基本準備が整いました。", centerX, centerY - 25, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, "ボイスチャットと無線通信をお楽しみください！", centerX, centerY - 10, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, "下の [完了・保存] ボタンを押してゲームを開始してください。", centerX, centerY + 20, 0xAAAAAA)
    }

    companion object {
        fun open(parent: Screen? = null) {
            Minecraft.getInstance().setScreen(FirstTimeTutorialScreen(parent))
        }
    }
}
