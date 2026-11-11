[中文](../modules.md) · English

# Feature modules

Status: ✅ implemented　◐ partially implemented　○ not implemented

## 1. Shared capabilities

| # | Module | Responsibility | Status |
|---|---|---|---|
| C1 | Pairing and connection | QR-code / six-digit-code pairing, exchanged for a long-lived deviceToken; heartbeat keepalive, reconnect after disconnect, fallback when pairing expires | ✅ |
| C2 | On-device speech recognition | X-ASR 960ms streaming zh-en + punctuation + hotwords; term-correction layer; Cantonese/Japanese/Korean switch to SenseVoice | ✅ |
| C3 | Text output | Key injection (CGEvent / SendInput), falling back to clipboard paste on failure; ack report | ✅ |
| C4 | Capsule storage and sync | Record on the phone, transcribe locally, persist locally; on save and on every connection, re-send unsynced capsules | ✅ |
| C5 | Model management | Currently bundled with the APK; models can be updated independently later | ◐ |
| C6 | Diagnostics | Debug self-test (bundled recording through the full chain), connection error messages, version info | ◐ |
| C7 | Pointer channel | Phone trackpad movement / left click / right click / scroll (two-finger and edge) over the same TCP connection; the desktop turns them into mouse events | ✅ |
| C8 | MCP service | Local stdio MCP: read transcriptions, read/write capsules, type at the cursor, key control (off by default) | ✅ |
| C9 | Key mapping | The phone only sends action ids; the desktop decides the key/modifiers or "open an application" (launch if not running, bring to front if running), and pushes changes to the phone | ✅ |

## 2. Phone (Android)

| # | Page / module | Key content and status | Stage |
|---|---|---|---|
| P1 | First-run onboarding | One-line product description, microphone permission, go connect to the computer | ○ |
| P2 | Pairing | Address / port / six-digit code; connecting, failed, pairing expired | ✅ |
| P3 | Remote-control home screen | One trackpad + voice key + capsule key + five configurable keys; tap for left click, two-finger scroll, two-finger tap for right click, pinch to zoom, press and hold to drag | ✅ |
| P4 | Voice overlay | Recording (level, timer, live text), transcribing, typed, typing failed; all floating over the trackpad | ✅ |
| P5 | Capsule overlay | Hold the capsule key to record; release to transcribe and save automatically; on save the card flies into the capsule key | ✅ |
| P6 | Capsule list | Sheet sliding up from the bottom; time, body, duration, sync status; empty state | ✅ |
| P7 | Capsule detail | Full text, duration, copy, send to the computer cursor, edit, delete | ✅ |
| P8 | Settings | Computer / Remote (gesture help, left-hand mode, pointer speed, edge scrolling, natural scrolling) / Capsules / Debug self-test; the whole page scrolls | ✅ |
| P9 | Gestures | Eight rows of gesture descriptions + an animation stage at the top (tap a row to play it): one-finger move, tap, two-finger swipe, two-finger tap, pinch, stop then drag, edge swipe, hold the capsule key; opens automatically on first connection | ✅ |
| P10 | States and errors | Not connected, token expired, model load failure, permission denied, typing failed | ◐ |

## 3. Desktop (macOS / Windows)

| # | Page / module | Key content and status | Stage |
|---|---|---|---|
| D1 | First-run setup | macOS Accessibility permission, pairing; no sidebar; complete the two steps, then enter the main UI | ◐ |
| D2 | Overview | Three status cards: phone / typing permission / MCP; recent typing; show pairing code, open capsules, copy address | ✅ |
| D3 | Connect | Pairing code / QR code / address; three-step instructions; change code | ✅ |
| D4 | Devices | Paired list, online status, remove one, remove all | ✅ |
| D5 | Capsule inbox | List on the left, detail on the right; copy, type at the cursor, delete; capsules created over MCP are polled in too | ✅ |
| D6 | MCP | On-demand stdio service, client config snippet, tool list with per-tool toggles; call log still to do | ◐ |
| D7 | Settings | Output method, launch at login, Accessibility, pairing code, phone key editing (bind each of the 5 actions) | ✅ |
| D8 | Tray and notifications | Open / pause receiving / quit; typing-failure HUD | ◐ |

### MCP: letting an agent digest capsules

This layer follows the positioning of Smartisan's Flash Capsule: the phone is responsible for "capturing", the agent for "digesting". So MCP **does not expose transcription history**; it is built around capsules only: fetch the undigested → summarize → user confirms → mark as organized, and put the result back at the cursor when needed.

| Tool | Purpose | Default |
|---|---|---|
| `capsules_list` | List capsules, `status=new` by default, newest first | On |
| `capsules_get` | Get the full text of one capsule | On |
| `capsules_mark` | Mark as `used` (organized) or put back to `new` | On |
| `capsules_create` | Create when the user says "note this down" | On |
| `capsules_delete` | Delete, requires explicit user consent | On |
| `text_type` | Type the digest result at the current cursor | On |
| `keys_press` | Enter / Delete / Esc / switch window | Off (dangerous; enable in the app) |

Capsule states: `new` is the inbox, `used` is digested. A re-send from the phone does not overwrite `used` back to `new`.

### Companion Skill

Tools only solve "can be called"; a Skill solves "when and in what order to use them":

- **MCP handshake**: `initialize` carries `instructions`, so a client understands the product and the recommended flow without extra configuration;
- **Prompt**: `prompts/list` offers `organize_capsules` to trigger "organize capsules" with one line;
- **Resource**: `vibetype://skill` from `resources/read` provides the full text; clients that support resources can read it directly;
- **Installable**: one click in Settings writes it to `~/.claude/skills`, `~/.agents/skills`, `~/.config/opencode/skills`, `~/.codex/skills`.

Implementation: the desktop app has a `--mcp` subcommand that speaks stdio JSON-RPC; the config snippet is copied with one click on the D6 page.

## 4. Explicitly out of scope (this stage)

- Cloud sync, accounts: capsules and records stay local.
- Mobile iOS: no developer account; get Android fully right first.
- Bluetooth HID: the trackpad now uses the existing TCP connection, so there is no second BLE HID protocol to maintain.

## 5. Implementation order

1. **P0 (done)**: C1 / C2 / C3 + P2 / P3 voice parts + D1 / D2 / D4 + installers for all three platforms.
2. **P1 (done)**: C7 pointer channel + P3 trackpad and keys + desktop mouse / key injection + real-device verification.
3. **P2 (done)**: C4 capsules + P5 / P6 / P7 + D5 inbox + sync a copy to the desktop when saving on the phone.
4. **P3 (done)**: D2 / D3 / D4 / D7 sidebar console + C8 / D6 MCP service and configuration page.
5. **P4 (next)**: P1 first-run onboarding, P9 gesture playground, P8 sensitivity and custom keys, D8 tray HUD, MCP call log.
