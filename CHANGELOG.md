# 📋 ModernVoiceChat v1.0-SNAPSHOT Changelog

**Release Version**: `1.0-SNAPSHOT`  
**Target Environment**: Minecraft 1.21.1 / NeoForge 21.1.238+ / Java 21  
**License**: GNU General Public License v3.0 (GPLv3)

---

## 🌟 主要新機能 (Features)

### 1. ⚡ 完全ネイティブ非依存 (Pure Java QUIC / kwik)
- C/C++ ネイティブライブラリ (`.dll`, `.so`, `.dylib`) および `Netty Native QUIC` 依存を完全に撤去。
- RFC 9000 準拠の Pure Java QUIC ライブラリ (`kwik`) を採用し、**Windows, Linux, macOS (Apple Silicon M1/M2/M3含む)** の全環境でネイティブクラッシュなしに完璧動作。

### 2. 📻 ハンドヘルド無線機アイテム (Radio Item) ＆ 軍用 DSP エフェクト
- **アイテム & クラフト**:
  - 作業台クラフトレシピ追加（鉄インゴット × 5 + レッドストーン × 1 + 銅インゴット × 1）。
  - クリエイティブモード専用タブ (`ModernVoiceChat`) 追加。
- **直感的な操作性**:
  - **送信 (PTT)**: 右クリック長押し中のみ無線送信。
  - **周波数変更**: 左クリックで周波数・チャンネル設定 GUI (MHz) をオープン。
  - **受信**: インベントリまたは手に持っているだけで常時自動受信。
  - **スマート発話検知**: 右クリック長押し中であっても、実際に声を発声した時のみ Talking 状態 ＆ パケット送信。
- **TFAR 風リアルタイム DSP オーディオプロセッサ**:
  - **300Hz ～ 3,400Hz バンドパス ＆ ダイナミックコンプレッサー**: 無線特有の硬く聞き取りやすい音質を再現。
  - **700m 鮮明保持 ＆ 電波減衰カーブ**:
    - `0m ～ 700m`: ノイズや音切れのないクリアで非常に聞き取りやすい会話を保持。
    - `700m ～ 1,000m`: ホワイトノイズ増幅、帯域収縮 (800Hz–1800Hz)、文節ドロップアウト（「こちら────1です」）、デジタル量子化歪みが滑らかに混入。

### 3. 🔰 初回ボイス設定チュートリアルウィザード (`FirstTimeTutorialScreen`)
- 初回導入・参加時に自動ポップアップする 4 ステップ設定ガイド。
- **機能**:
  - Step 1: マイク・スピーカーのデバイス選択。
  - Step 2: VAD / PTT モード切替、**割当 PTT キーハイライト表示**、**リアルタイムアニメーション付きマイクレベルメーター ＆ ループバックテスト**。
  - Step 3: 操作キー（V / M / N / PTT）＆ 無線機操作ガイド。
  - Step 4: 保存してゲーム開始（※設定画面からいつでも再実行可能）。
- **完全日本語 ＆ 英語多言語対応 (`ja_jp.json` / `en_us.json`)**: クライアント言語に応じて自動ローカライズ。

### 4. 🧩 拡張 Mod API (v1.0)
- **1対1 ダイレクト通話 (Direct Voice Link)**: 距離制限を無視した電話・秘密通話リンク (`createVoiceLink`)。
- **カスタムボイスグループ**: チームチャンネル・パーティ通話 API (`createVoiceGroup`)。
- **NeoForge イベントシステム**: 他 Mod から発話検知・無線送信をフック可能 (`PlayerSpeakingEvent`, `RadioTransmitEvent`)。

---

## ⚙️ 設定 ＆ セキュリティ改善 (Configs & Security)

- **外部 IP/ドメイン対応 (`voiceHost`)**:
  - `config/modernvoicechat-server.toml` に `voiceHost` を追加。逆プロキシや外部 IP ボイスサーバーに対応。
- **プライバシー・セキュリティ保護**:
  - クライアントログ (`latest.log`) から接続先サーバー生 IP アドレスをマスキング・非表示化（ポート番号のみログ出力）。
- **ライセンス構成**:
  - 公式ライセンスを `GPL-3.0-only` に決定し、リポジトリに `LICENSE` 本文を追加。

---

## 🧪 品質保証 ＆ テストスイート (Testing)

- `VoiceQualityAndStutterTest`: 20ms キャデンス連続性・不連続クリックノイズ・音切れテスト通過。
- `RadioAudioFilterTest`: 300Hz–3400Hz バンドパスおよび 700m 品質減衰動作テスト通過。
- `ModernVoiceChatApiTest`: ダイレクト通話・ボイスグループ・API アクセステスト通過。
- `FirstTimeTutorialTest`: チュートリアルフラグ読み書きテスト通過。
