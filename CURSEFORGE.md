# 🎙️ ModernVoiceChat

**ModernVoiceChat** is a high-performance, next-generation **3D Spatial & Radio Voice Chat Mod** for **Minecraft 1.21.1 (NeoForge)**.

Unlike traditional voice mods that rely on OS-specific C/C++ native libraries or Netty native binaries, **ModernVoiceChat** uses a **100% Pure Java QUIC (kwik / RFC 9000)** implementation. This guarantees seamless, crash-free performance across **Windows, Linux, and macOS (including Apple Silicon M1/M2/M3)** without any setup hassle!

---

## ✨ Key Features

### ⚡ 100% Native-Free & Cross-Platform (Pure Java QUIC)
- Powered by `kwik` (Pure Java QUIC / RFC 9000).
- Zero native binaries (`.dll`, `.so`, `.dylib`) required.
- Superior compatibility for all server hostings and client OS environments.

### 🎙️ Immersive 3D Spatial Audio
- Real-time positional voice chat calculated dynamically based on player distance and head orientation.
- Natural 24-meter default hearing radius with smooth distance falloff.

### 📻 Handheld Radio Item & TFAR-Style DSP Filter
- **In-Game Radio Item (`modernvoicechat:radio`)**: Right-click to open an interactive GUI and set your frequency (e.g., `144.00 MHz`).
- **700m Clear Communication & Real-time Signal Degradation**:
  - **0m – 700m (Audible & Clear Range)**: Applies military-grade 300Hz–3400Hz band-pass filtering and dynamic compression for crisp, highly intelligible radio speech.
  - **700m – 1000m (Signal Degradation Zone)**: Dynamic RSSI signal fading introduces authentic background white noise, adaptive bandwidth narrowing (800Hz–1800Hz), syllable packet dropouts, and digital quantization distortion (MELPe style).
  - **1000m+**: Signal cutoff.

### 🌐 External IP & Proxy Support (`voiceHost`)
- Easily connect voice traffic to a different external IP/domain or reverse proxy via `config/modernvoicechat-server.toml`.

### 🧩 Rich Developer API (v1.0)
- **Direct 1-on-1 Voice Links**: Build telephone, intercom, or secret line features with distance-free direct audio routing.
- **Custom Voice Groups**: Create team channels or party voice rooms in seconds.
- **NeoForge Event Integration**: Hook into `PlayerSpeakingEvent` or `RadioTransmitEvent` to trigger custom in-game animations or mechanics.

---

## 🎮 Requirements

- **Minecraft**: `1.21.1`
- **Mod Loader**: `NeoForge 21.1.238+`
- **Java**: `Java 21`

---

## 📻 How to Use the Radio

1. Craft or acquire the **Radio** item in-game.
2. **Transmit (PTT)**: Hold the Radio and **[Hold Right-Click]** to transmit your voice over the radio frequency.
3. **Configure Frequency**: Hold the Radio and **[Left-Click]** to open the Frequency Configuration Screen (e.g. `144.00 MHz`).
4. **Receive**: Simply keep the Radio in your hands or inventory to **always receive** incoming radio transmissions on your frequency!

---

## ⚙️ Server Configuration (`config/modernvoicechat-server.toml`)

```toml
[server]
  # SFU Voice Server UDP/QUIC Port
  voicePort = 24454

  # External IP/Hostname for client connection (leave empty "" to use Minecraft server IP)
  voiceHost = ""

  # 3D Proximity Voice Hearing Range in blocks/meters
  voiceRange = 24.0

  # Distance in blocks up to which radio remains highly clear
  radioClearRange = 700.0

  # Maximum transmission range for radio in blocks
  radioMaxRange = 1000.0
```

---

## 🧩 Developer API Example

```kotlin
import com.ruskserver.modernvoicechat.api.ModernVoiceChatAPI

// Create a private direct 1-on-1 telephone link between two players
val serverApi = ModernVoiceChatAPI.getServerApi()
serverApi.createVoiceLink(playerAUuid, playerBUuid, bidirectional = true, isolateProximity = true)

// Check if a player is currently speaking
val isSpeaking = serverApi.isPlayerSpeaking(playerUuid)
```

---

## 📄 License

ModernVoiceChat is licensed under the **[GNU General Public License v3.0 (GPLv3)](LICENSE)**.
