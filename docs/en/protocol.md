[中文](../protocol.md) · English

# Protocol v1

The desktop listens on TCP (default `47823`, moving to the next port if occupied); the phone connects. Messages are **NDJSON**: one JSON per line, UTF-8, no line over 64 KB.

## Pairing

The desktop shows a QR code and a six-digit code in the pairing window; the QR code contains:

```
vibetype://pair?h=192.168.1.23&p=47823&c=482913
```

- `h` LAN IPv4, `p` port, `c` one-time pairing code (rotates every 180 seconds; the previous round is kept for another 180 seconds as a grace period).
- The phone can also manually enter `192.168.1.23:47823` + the six-digit code.

After pairing succeeds, the desktop issues a long-lived `deviceToken` (32-byte hex), which the phone stores locally; every later connection uses it directly.

## LAN discovery

When the computer changes IP (new office, new Wi-Fi), the phone no longer needs the new address typed in. The phone broadcasts over UDP `47824`:

```
VIBETYPE_DISCOVER
```

The desktop replies from the same port:

```json
{"vibetype":1,"id":"d7906fbc66dba52b","port":47823,"platform":"macos"}
```

- `id` is a stable, persisted desktop identifier. The phone only accepts replies whose id matches the `desktopId` it remembers (or the first reply if it has none yet), so it does not attach to another VibeType on the same subnet.
- The phone uses the reply's source IP and re-runs the TCP handshake with the existing `deviceToken`. The broadcast carries no token; authentication stays on the server.
- If broadcast is blocked by AP isolation or a subnet boundary, the phone offers "Enter address": change only host/port, no re-pairing.

## Handshake

Phone → Desktop:

```json
{"t":"hello","v":1,"token":"<配对码 或 deviceToken>","device":{"name":"Redmi K80","platform":"android","app":"0.1.0"}}
```

Desktop → phone (success):

```json
{"t":"welcome","v":1,"server":"vibetype/0.1.0","platform":"macos","inject":"type","deviceToken":"<32 hex，仅首次配对时出现>"}
```

Desktop → phone (failure, then disconnect):

```json
{"t":"error","code":"bad_token","msg":"配对码已过期"}
```

`code`: `bad_token` / `unsupported_version` / `too_many_devices` / `rate_limited`.

## Text output

Phone → Desktop:

```json
{"t":"text","id":"9f2c...","text":"明天记得把 Readme 的英文版补上。","ms":4800,"lang":"zh","engine":"x-asr"}
```

Desktop → phone:

```json
{"t":"ack","id":"9f2c...","ok":true,"mode":"type"}
```

`mode`: `type` (key injection) / `paste` (clipboard paste fallback) / `failed` (both failed; the desktop shows a notification).

Newlines in `text` are escaped by JSON as `\n` and do not affect line-based framing. The phone waits at most 5 seconds for `ack`; on timeout it marks the item "sent, not confirmed" and does not resend (to avoid typing twice).

## Pointer and keys

The trackpad and the remote-control keys share the same connection; they do not wait for an ack and are sent in order:

```json
{"t":"pointer","action":"move","dx":12.5,"dy":-8.0}
{"t":"pointer","action":"scroll","dx":0,"dy":-3.0}
{"t":"pointer","action":"click","button":"left"}
{"t":"pointer","action":"zoom","dx":0,"dy":1}
{"t":"pointer","action":"down","button":"left"}
{"t":"pointer","action":"up","button":"left"}
{"t":"key","name":"enter"}
```

`action`: `move` / `scroll` / `click` / `down` / `up` / `zoom`. `button`: `left` / `right` / `middle`.
For `zoom`, `dy` is the number of steps: positive zooms in, negative zooms out; the phone accumulates the two-finger distance and sends one step only after crossing a threshold.

## Keymap

The phone only sends action ids; the desktop decides which key is pressed. `welcome` carries the current keymap:

```json
{"t":"welcome","v":1,"keys":[
  {"id":"k1","label":"回车","action":"key","key":"enter","modifiers":[]},
  {"id":"k2","label":"删除","action":"key","key":"backspace","modifiers":[]},
  {"id":"k3","label":"esc","action":"key","key":"escape","modifiers":[]},
  {"id":"k4","label":"切换","action":"key","key":"tab","modifiers":["cmd"]},
  {"id":"k5","label":"WB","action":"app","target":"WorkBuddy"}
]}
```

Phone key → Desktop:

```json
{"t":"key","name":"k1"}
```

After the keymap is edited the desktop pushes it; the phone does not need to reconnect:

```json
{"t":"keymap","keys":[ ... 同上 ... ]}
```

Values of `key`: `enter` / `tab` / `space` / `escape` / `backspace` / `delete` / `up` / `down` / `left` / `right` / `pageup` / `pagedown` / `home` / `end`.
`action`: `key` presses a key, or `app` opens/activates an application (`target` is the application name, bundle id, exe path or .lnk).
`modifiers`: on macOS `cmd` / `ctrl` / `alt` / `shift`; on Windows `ctrl` / `alt` / `shift` / `meta`.
Older versions send the aliases `enter` / `delete` / `esc` / `switch` / `custom`; the desktop maps them to k1–k5 for compatibility.

## Capsules

After the phone saves a capsule, and on every successful connection, it re-sends local capsules with `synced=false`:

```json
{"t":"capsule","id":"e772847b-...","text":"把下载链接放到 README 最上面","createdAt":1794406339322,"ms":3800}
```

The desktop deduplicates by `id` before writing to disk; for a capsule already marked `used`, a re-send does not change the state back to `new`.

## Keepalive

Either side may send `{"t":"ping","ts":<毫秒>}` and the other replies `{"t":"pong","ts":<原样返回>}`. The desktop disconnects after 180 seconds idle.

## Compatibility

`v` is the major version. The desktop accepts `v == 1`; the phone prompts to upgrade when `welcome.v` is missing or greater than its own major version.

New fields must be optional and receivers ignore unknown fields; an unknown `t` is ignored outright.
