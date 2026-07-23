# 📋 ModernVoiceChat Changelog

## 🚀 Version 1.2

**Release Date**: July 23, 2026  
**Target Environment**: Minecraft 1.21.1 / NeoForge 21.1.238+ / Java 21  

---

### 🌟 New Features & Enhancements

#### 1. 🎚️ Per-Player Volume Adjustment
- Added a new **"Player Volumes"** category in the settings screen (`[V]`).
- Individual volume sliders (0% to 200%) for online players allow fine-tuning of each player's voice output level.
- Saved volume preferences persist across sessions.

#### 2. 🖱️ Improved Audio Device Selection UI
- Refactored Microphone and Speaker device selectors from text-input dropdowns to single-click **Selector Buttons**.
- Players can now cycle through available audio devices easily with a single click.

#### 3. 🔰 Interactive First-Time Setup Wizard
- Added a 4-step interactive tutorial screen (`FirstTimeTutorialScreen`) for first-time setup.
- Allows real-time microphone test (loopback), input mode toggle (VAD/PTT), threshold adjustment, and volume level testing.
- Full internationalization support with English (`en_us`) and Japanese (`ja_jp`) localizations.
- Can be re-launched anytime via the settings screen.

#### 4. 📻 Radio Transmit State Refinements
- Updated radio behavior so that holding the radio item only transmits voice and displays the talking indicator when actual speech or PTT is active.
- Fixed radio item missing texture icon issue in model registry.

---

### 🛠️ Bug Fixes & Audio Engine Improvements

- **Low-Latency Loopback Test**:
  - Rewrote the mic test loopback engine to bypass redundant queues, reducing loopback latency down to ~60ms.
- **Audio Artifacts & Clicks**:
  - Fixed click noises and buffer underruns during loopback by injecting silent frames when no audio is detected.
- **Device Conflict Prevention**:
  - Resolved `LineUnavailableException` errors caused by attempting to open duplicate audio target lines while active on a server.

---

## 📋 Version 1.0-SNAPSHOT

**Release Date**: July 22, 2026  

### 🌟 Features
- **Pure Java QUIC Transport (`kwik`)**: Cross-platform UDP network transport.
- **Handheld Radio Item & Real-Time DSP**: 300Hz-3.4kHz bandpass filter, distance attenuation, and white noise distortion.
- **Developer API**: Direct 1-on-1 voice links, custom voice channels, and NeoForge events.
- **Configuration & Privacy**: External `voiceHost` support and redacted IP logging.
