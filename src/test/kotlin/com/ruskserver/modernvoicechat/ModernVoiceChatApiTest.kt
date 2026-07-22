package com.ruskserver.modernvoicechat

import com.ruskserver.modernvoicechat.api.ModernVoiceChatAPI
import com.ruskserver.modernvoicechat.sfu.PlayerPosition
import com.ruskserver.modernvoicechat.sfu.SFURouter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ModernVoiceChatApiTest {

    @Test
    fun testDirectVoiceLinkAndGroupApi() {
        val router = SFURouter(maxDistance = 24.0)

        val playerA = UUID.randomUUID()
        val playerB = UUID.randomUUID()
        val playerC = UUID.randomUUID()

        // 1. 通常状態: 100m 離れた位置 (通常近接ボイスの 24m 可聴範囲外)
        router.updatePosition(playerA, PlayerPosition(0.0, 64.0, 0.0, "minecraft:overworld"))
        router.updatePosition(playerB, PlayerPosition(100.0, 64.0, 0.0, "minecraft:overworld"))
        router.updatePosition(playerC, PlayerPosition(200.0, 64.0, 0.0, "minecraft:overworld"))

        var recipientsA = router.getRecipientsForSender(playerA)
        assertTrue(recipientsA.isEmpty(), "Player A should have 0 recipients due to 100m distance")

        // 2. API による 1対1 ダイレクトリンク構築 (距離無制限で直接会話)
        router.addDirectLink(playerA, playerB, bidirectional = true, isolateProximity = false)
        recipientsA = router.getRecipientsForSender(playerA)
        assertEquals(1, recipientsA.size)
        assertTrue(recipientsA.contains(playerB), "Player A must route to Player B via direct link")

        var recipientsB = router.getRecipientsForSender(playerB)
        assertTrue(recipientsB.contains(playerA), "Player B must route to Player A via bidirectional direct link")

        // 3. API によるカスタムボイスグループ (チームチャンネル) 構築
        val groupUuid = router.createVoiceGroup()
        router.addPlayerToGroup(groupUuid, playerA)
        router.addPlayerToGroup(groupUuid, playerC)

        recipientsA = router.getRecipientsForSender(playerA)
        assertEquals(2, recipientsA.size)
        assertTrue(recipientsA.contains(playerB), "Should contain direct link recipient B")
        assertTrue(recipientsA.contains(playerC), "Should contain team group recipient C")

        // 4. ダイレクトリンク解除 ＆ グループ解散
        router.removeDirectLink(playerA, playerB)
        router.disbandVoiceGroup(groupUuid)

        recipientsA = router.getRecipientsForSender(playerA)
        assertTrue(recipientsA.isEmpty(), "Recipients should be empty after disbanding links and groups")

        // 5. Client API アクセスチェック
        val clientApi = ModernVoiceChatAPI.getClientApi()
        assertNotNull(clientApi)
        assertFalse(clientApi.isSpeakerMuted())

        println("=========================================================================")
        println("          MODERN VOICE CHAT API & DIRECT LINK TEST PASSED                ")
        println("=========================================================================")
        println(" 1対1 ダイレクトボイスリンク    : 成功 (100m遠距離でも直接ルーティング)")
        println(" カスタムボイスグループ (チーム) : 成功 (チームメンバー相互ルーティング)")
        println(" クライアント/サーバー API 参照 : 正常アクセス")
        println("=========================================================================")
    }
}
