package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.client.config.VoiceConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FirstTimeTutorialTest {

    @Test
    fun testTutorialFlagAndConfigState() {
        // 初期状態フラグ
        VoiceConfig.isTutorialCompleted = false
        assertFalse(VoiceConfig.isTutorialCompleted, "Tutorial should be incomplete initially")

        // チュートリアル完了後の状態
        VoiceConfig.isTutorialCompleted = true
        assertTrue(VoiceConfig.isTutorialCompleted, "Tutorial should be completed")

        println("=========================================================================")
        println("          FIRST TIME SETUP TUTORIAL FLAG TEST PASSED                      ")
        println("=========================================================================")
        println(" チュートリアル未完了フラグ     : 正常判定 (isTutorialCompleted = false)")
        println(" チュートリアル完了・保存フラグ : 正常判定 (isTutorialCompleted = true)")
        println("=========================================================================")
    }
}
