package com.ruskserver.modernvoicechat.api

/**
 * クライアント側でマイク・スピーカー・発話判定を操作・取得する API。
 */
interface VoiceChatClientAPI {

    /** 自分のマイクが現在声を拾って発話中か判定 */
    fun isLocalPlayerSpeaking(): Boolean

    /** マイクがミュート中か取得 */
    fun isMicrophoneMuted(): Boolean

    /** マイクのミュート状態を設定 */
    fun setMicrophoneMuted(muted: Boolean)

    /** スピーカーがミュート中か取得 */
    fun isSpeakerMuted(): Boolean

    /** スピーカーのミュート状態を設定 */
    fun setSpeakerMuted(muted: Boolean)
}
