# 📋 ModernVoiceChat v1.0-SNAPSHOT Changelog

**Release Version**: `1.0-SNAPSHOT`  
**Target Environment**: Minecraft 1.21.1 / NeoForge 21.1.238+ / Java 21  
**License**: GNU General Public License v3.0 (GPLv3)

---

## 🌟 Major Features

### 1. ⚡ Pure Java QUIC Transport (`kwik`)
- Removed C/C++ native library (`Netty Native QUIC`) dependencies to prevent platform-specific native crashes.
- Adopted pure Java QUIC implementation (`kwik` / RFC 9000) for cross-platform stability across Windows, Linux, and macOS.

### 2. 📻 Handheld Radio Item & Real-Time Audio DSP
- **Item & Crafting**:
  - Survival crafting recipe added (5x Iron Ingot + 1x Redstone + 1x Copper Ingot).
  - Dedicated creative mode tab (`ModernVoiceChat`).
- **Control Scheme**:
  - **Transmit (PTT)**: Hold Right-Click to transmit.
  - **Frequency**: Left-Click to open the frequency setup GUI (MHz).
  - **Receive**: Always active while held in inventory or hands.
  - **Voice Detection**: Only transmits audio packets and shows talking indicator when actual voice or PTT is detected during hold.
- **Real-Time DSP Audio Processing**:
  - **300Hz - 3,400Hz Bandpass & Dynamic Compressor**: Recreates realistic radio sound.
  - **700m Clear Range & Distance Attenuation**:
    - `0m - 700m`: Clear audio transmission.
    - `700m - 1,000m`: Smooth attenuation introducing white noise, band narrowing (800Hz-1800Hz), packet dropouts, and digital quantization distortion.

### 3. 🔰 First-Time Setup Wizard (`FirstTimeTutorialScreen`)
- Automatic popup setup screen for first-time players.
- **4-Step Wizard**:
  - Step 1: Microphone & Speaker device selection.
  - Step 2: VAD / PTT mode toggle, PTT key indicator, and real-time mic volume level meter with loopback test.
  - Step 3: Keybinds & Radio usage guide.
  - Step 4: Finish & Save (Can be re-opened anytime from `[V]` settings screen).
- **Full Internationalization (i18n)**: English (`en_us.json`) and Japanese (`ja_jp.json`) support.

### 4. 🧩 Developer API (v1.0)
- **Direct 1-on-1 Voice Links**: Distance-free direct audio link between players (`createVoiceLink`).
- **Custom Voice Groups**: Team and party voice channels (`createVoiceGroup`).
- **NeoForge Events**: Event hooks (`PlayerSpeakingEvent`, `RadioTransmitEvent`).

---

## ⚙️ Configuration & Security

- **External Host Configuration (`voiceHost`)**:
  - Added `voiceHost` setting in `config/modernvoicechat-server.toml` for reverse proxy or external IP connection.
- **Privacy Protection**:
  - Redacted raw server IP address from client log files (`latest.log`).
- **License**:
  - GPLv3 license documentation (`LICENSE`).
