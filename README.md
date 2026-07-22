# 🎙️ ModernVoiceChat

**ModernVoiceChat** は、Minecraft 1.21.1 (NeoForge) 向けに開発された、ネイティブ非依存（Pure Java QUIC）の次世代リアルタイム 3D 空間ボイスチャット ＆ 無線 Mod です。

---

## ✨ 主な特徴

- ⚡ **ネイティブ非依存 (Pure Java QUIC / kwik)**:
  - `Netty Native QUIC` や C++ C/C++ ネイティブライブラリに一切依存せず、Pure Java の QUIC ライブラリ (`kwik` / RFC 9000) を採用。
  - Windows、Linux、macOS (Intel / Apple Silicon) すべてのOS環境でクラッシュなく動作します。
- 🎙️ **3D 空間ボイスチャット (Spatial Audio)**:
  - プレイヤー同士の距離（デフォルト 24m）および空間位置に基づくリアルタイム 3D 空間音響。
- 📻 **TFAR 風 高度軍用無線アイテム (Radio Item)**:
  - ゲーム内無線機アイテム (`modernvoicechat:radio`) を右クリックして周波数 (MHz) を変更可能。
  - **700m 鮮明保持 ＆ 電波減衰エフェクト**:
    - **0m ～ 700m**: 無線特有のバンドパス (300Hz–3400Hz) ＆ コンプレッサー加工を施しつつ、ノイズや音切れのない **「まともに聞き取りやすい快適な会話」** を維持。
    - **700m ～ 1,000m**: ホワイトノイズ増幅、帯域収縮 (800Hz–1800Hz)、文節ドロップアウト、デジタル量子化歪みが加算され、リアルに電波が低下。
- 🌐 **マルチサーバー・外部 IP 対応 (`voiceHost`)**:
  - Minecraft サーバーとボイスチャットサーバーの IP/ドメインが異なる場合や、逆プロキシ環境でも設定ファイル (`voiceHost`) 一つで接続可能。
- 🧩 **高度な拡張 Mod API (v1.0)**:
  - **1対1 ダイレクト通話 (電話・インカム機能)** や **カスタムチームグループ** を数行のコードで構築可能。

---

## 🎮 動作環境

- **Minecraft**: `1.21.1`
- **Mod Loader**: `NeoForge 21.1.238+`
- **Java**: `Java 21 (Corretto 21 / Temurin 21 推奨)`

---

## 📻 無線機アイテムの使い方 ＆ クラフトレシピ

1. **取得・クラフト**:
   - **クリエイティブタブ**: `ModernVoiceChat` 専用タブから直接取り出すことができます。
   - **サバイバルクラフト**: 作業台で以下の材料を並べて作成します：
     - `鉄インゴット` × 5
     - `レッドストーン` × 1
     - `銅インゴット` × 1
     ```text
     [ 空白 ] [ 鉄インゴット ] [ 空白 ]
     [ 鉄インゴット ] [ レッドストーン ] [ 鉄インゴット ]
     [ 鉄インゴット ] [ 銅インゴット ] [ 鉄インゴット ]
     ```
2. **周波数設定**:
   - 手に持って **[右クリック]** すると、周波数設定 GUI（例: `144.00 MHz`）が開きます。
3. 同じ周波数に合わせたプレイヤー同士は、距離無制限（～1,000m）で長距離無線通話が行えます。

---

## ⚙️ サーバー設定 (`config/modernvoicechat-server.toml`)

```toml
[server]
  # SFU ボイスサーバーの UDP/QUIC ポート (デフォルト: 24454)
  voicePort = 24454

  # クライアントがボイスサーバーに接続するための外部 IP / ドメイン
  # マイクラサーバーと同じ IP の場合は空文字 ("") のままでOK
  voiceHost = ""

  # 3D 空間ボイスの最大可聴範囲 (ブロック/メートル)
  voiceRange = 24.0

  # 無線音声がノイズなく極めて聞き取りやすい距離 (デフォルト: 700m)
  radioClearRange = 700.0

  # 無線音声の最大到達距離 (デフォルト: 1000m)
  radioMaxRange = 1000.0
```

---

## 🧩 Mod API 利用方法 (他 Mod 開発者向け)

`ModernVoiceChat` は他 Mod やアドオンからボイス機能を制御できる API を提供しています。

```kotlin
import com.ruskserver.modernvoicechat.api.ModernVoiceChatAPI

// 1. 特定のプレイヤー同士をダイレクト通話リンク (電話・密室モード)
val serverApi = ModernVoiceChatAPI.getServerApi()
serverApi.createVoiceLink(playerAUuid, playerBUuid, bidirectional = true, isolateProximity = true)

// 2. プレイヤーの発話中判定
val isSpeaking = serverApi.isPlayerSpeaking(playerUuid)

// 3. 発話開始・停止イベントのフック (NeoForge @SubscribeEvent)
@SubscribeEvent
fun onPlayerSpeaking(event: PlayerSpeakingEvent) {
    println("Player ${event.playerUuid} is speaking: ${event.isSpeaking}")
}
```

---

## 📄 ライセンス

本プロジェクトは **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** の下で公開されています。
