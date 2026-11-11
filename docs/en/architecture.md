[中文](../architecture.md) · English

# Architecture

## In one sentence

The phone is responsible for "hearing and writing it down"; the computer is responsible for "typing the characters in". Only text passes between them.

## Data flow

```
麦克风 16 kHz PCM
   │  AudioRecord（单声道 PCM16）
   ▼
静音裁剪（首尾能量门限）
   │
   ▼
X-ASR 960ms 流式（zh-en + 标点 + 热词，边录边出字）
   │  文本 + 语种 + 情感/事件（可选）
   ▼
TCP 长连接（NDJSON）   ── 几十字节 ──▶  桌面端
                                            │
                                  注入当前焦点输入框
                                  （按键注入，失败退到粘贴）
```

## Android

| Item | Choice | Notes |
|---|---|---|
| Language/UI | Kotlin + Compose | Follows VibePad's visual language |
| Recording | `AudioRecord` 16 kHz / 16 bit / mono | X-ASR's fixed input |
| Endpointing | Hold to talk, release to end; append 1.2 s of silence at the end | The streaming model needs trailing silence to flush the last segment |
| Recognition | sherpa-onnx `OnlineRecognizer` + X-ASR 960ms int8 | Streaming, hotwords, CPU arm64-v8a |
| Model distribution | Bundled into the APK under `assets/models/x-asr/` (162 MB, APK about 217 MB) | No first-run download; changing the model requires a new release |
| Network | Foreground TCP client, NDJSON | Exponential backoff reconnect after disconnect |
| Pairing | Scan a QR code (host/port/token) or type the six-digit code | Token stored in SharedPreferences |

Model size: encoder 148 MB + decoder 11 MB + joiner 2.5 MB + tokens/bpe, about 162 MB in total, bundled with the APK.

Recognition time target: 5 seconds of speech in ≤ 1 second on a Redmi K80 (Snapdragon 8 Gen 3), i.e. RTF ≤ 0.2. If that is not reached, fall back to a smaller model (Paraformer-small / streaming Zipformer), but measurements come first.

## Desktop

Tauri 2: a Rust core + the system WebView, one shared codebase for macOS and Windows.

```
src-tauri/src/
├── main.rs          Tauri 启动、托盘、窗口
├── server.rs        TCP 服务、握手、token 校验、事件广播
├── inject/
│   ├── macos.rs     CGEvent Unicode 注入 + 粘贴兜底
│   └── windows.rs   SendInput Unicode 注入 + 粘贴兜底
├── state.rs         配对信息、设置、历史
└── net.rs           本机局域网地址、二维码内容
```

### Keys and system shortcuts

The phone only sends action ids (`k1`–`k5`); which key is pressed is decided by the desktop, and after a change it is pushed to the phone in real time with a `keymap` frame.

On macOS, ordinary keys go through CGEvent (enter, backspace, Esc, arrow keys and Tab combinations have all been verified). System-level actions are handled separately:

- **Mission Control** (default "Custom" = ⌃↑): when an app such as Chrome that enables Secure Input is open on this Mac, a synthesized ⌃↑ does not reach the system shortcut, so `open -b com.apple.exposelauncher` is called directly;
- **Switch apps** (default "Switch" = ⌘Tab): uses synthesized events; it may not work while Secure Input is on — a system limitation.

On Windows everything uses enigo: `Alt+Tab` to switch apps, `Win+Tab` for Task View.

### Injection strategy

1. **Direct injection** (default): split the text into UTF-16 events and post them one by one.
   - macOS: `CGEventKeyboardSetUnicodeString` + `.cghidEventTap`;
   - Windows: `SendInput` + `KEYEVENTF_UNICODE`.
2. **Paste fallback**: some apps (some Electron apps / terminals / remote desktops) do not accept synthesized keys. When direct injection fails or the user switches manually, write to the clipboard → send ⌘V / Ctrl+V → restore the original clipboard after 300 ms.

Permissions:

- macOS requires Accessibility authorization; on first launch the user is guided to enable it in System Settings;
- On Windows no authorization is needed for apps at normal privilege; for target programs running as administrator, injection is blocked by UIPI, and the user is prompted to run VibeType as administrator.

### Known weaknesses (measured 2026-11-11)

- **English proper nouns and brand names**: with hotwords, X-ASR gets `Swift` and `JSON` right, but proper nouns such as `README`, `VibeDeck` and `VibeType` still come out wrong. Two layers were added: model hotwords + on-phone term correction (aliases, similar English words, context); measured failure cases went from 23.5% → 0.7%.
- **Limits of TTS evaluation**: TTS reads `CI` as the Roman numeral "one hundred one" and `VibeType` as "five type", so absolute CER for English terms is not trustworthy; real human recordings are the gold standard. The evaluation set is in `tools/asr-eval/`.
- **Speed**: streaming decoding happens during recording, so after release only 1.2 seconds of silence need to be appended to finish; on the emulator, finishing takes about 100–120 ms. The real device (K80) is still to be measured.
- **Installer size**: the model (162 MB) is bundled in the APK; the debug APK is about 217 MB. GitHub limits a single file to 100 MB, so the model is not committed; before building, fetch it with `tools/fetch_model.sh`; CI does the same.
- **Cantonese/Japanese/Korean**: X-ASR supports only Chinese and English. When needed, switch to SenseVoice (optional engine, 228 MB, not bundled).

## Security model

On the LAN, a one-time pairing code is exchanged for a long-lived token; every connection must carry the token. There is no TLS (the complexity of self-signed certificates outweighs the benefit), but the service listens only on the LAN address, and token validation uses a constant-time comparison. The desktop can "revoke all devices" at any time.

## Why not a virtual microphone

A virtual-microphone approach treats the computer as the recognition side, and then must solve: audio transport, a virtual sound-card driver, IME activation, and IME state. A failure in any of the four shows up as "nothing happened". Text injection depends on only one of the operating system's most stable interfaces — keyboard events.

The cost: recognition quality depends on the on-phone model. The default is X-ASR (Apache-2.0, streaming, zh-en, best on terminology); Cantonese/Japanese/Korean switch to the optional SenseVoice.

## License

- This project's code: MIT.
- Default model X-ASR: Apache-2.0.
- Optional SenseVoiceSmall weights: FunASR Model License v1.1. Commercial use is allowed, provided the copyright notice and license text are retained in derivatives, and "FunASR" and "SenseVoice" may not be used to endorse the product name.
- sherpa-onnx: Apache-2.0.
- Int8 weights converted by third parties must have their source and license checked separately before release.
