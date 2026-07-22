package com.ruskserver.modernvoicechat.api

import java.util.UUID

/**
 * サーバー側でボイスチャット機能・ダイレクト通話・無線周波数・プレイヤー状態を操作・取得する API。
 */
interface VoiceChatServerAPI {

    /** プレイヤーが現在発話中か判定 */
    fun isPlayerSpeaking(playerUuid: UUID): Boolean

    /** プレイヤーが保持する無線機の周波数 (MHz) を取得 */
    fun getPlayerRadioFrequency(playerUuid: UUID): Double?

    /** プレイヤーの無線周波数を設定 */
    fun setPlayerRadioFrequency(playerUuid: UUID, frequency: Double)

    /** サーバー主導での音声ミュート/解除 */
    fun setPlayerMutedByServer(playerUuid: UUID, muted: Boolean)

    /** 特定のプレイヤー 2 名間に距離無制限の 1対1 ダイレクト音声リンクを構築 */
    fun createVoiceLink(playerA: UUID, playerB: UUID, bidirectional: Boolean = true, isolateProximity: Boolean = false)

    /** 1対1 ダイレクト音声リンクを解除 */
    fun removeVoiceLink(playerA: UUID, playerB: UUID, bidirectional: Boolean = true)

    /** カスタムボイスグループ（チームチャンネル）を作成 */
    fun createVoiceGroup(groupName: String = "Group"): UUID

    /** プレイヤーをボイスグループに追加 */
    fun addPlayerToGroup(groupUuid: UUID, playerUuid: UUID)

    /** プレイヤーをボイスグループから削除 */
    fun removePlayerFromGroup(groupUuid: UUID, playerUuid: UUID)

    /** ボイスグループを解散 */
    fun disbandVoiceGroup(groupUuid: UUID)
}
