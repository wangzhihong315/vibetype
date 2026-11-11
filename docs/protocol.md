# 协议 v1

桌面端监听 TCP（默认 `47823`，被占用时顺延），手机主动连接。报文为 **NDJSON**：一行一个 JSON，UTF-8，单行不超过 64 KB。

## 配对

桌面端在配对窗口里展示二维码和六位码，二维码内容：

```
vibetype://pair?h=192.168.1.23&p=47823&c=482913
```

- `h` 局域网 IPv4，`p` 端口，`c` 一次性配对码（180 秒轮换，上一轮再保留 180 秒宽限）。
- 手机也可以手动输入 `192.168.1.23:47823` + 六位码。

配对成功后，桌面端下发长期 `deviceToken`（32 字节 hex），手机存本地；之后每次连接直接用它。

## 握手

手机 → 桌面：

```json
{"t":"hello","v":1,"token":"<配对码 或 deviceToken>","device":{"name":"Redmi K80","platform":"android","app":"0.1.0"}}
```

桌面 → 手机（成功）：

```json
{"t":"welcome","v":1,"server":"vibetype/0.1.0","platform":"macos","inject":"type","deviceToken":"<32 hex，仅首次配对时出现>"}
```

桌面 → 手机（失败，随后断开）：

```json
{"t":"error","code":"bad_token","msg":"配对码已过期"}
```

`code`：`bad_token` / `unsupported_version` / `too_many_devices` / `rate_limited`。

## 上屏

手机 → 桌面：

```json
{"t":"text","id":"9f2c...","text":"明天记得把 Readme 的英文版补上。","ms":4800,"lang":"zh","engine":"x-asr"}
```

桌面 → 手机：

```json
{"t":"ack","id":"9f2c...","ok":true,"mode":"type"}
```

`mode`：`type`（按键注入）/ `paste`（剪贴板粘贴兜底）/ `failed`（两者都失败，桌面端会弹提示）。

`text` 里的换行由 JSON 转义为 `\n`，不影响按行分帧。手机端等待 `ack` 最长 5 秒，超时标记为「已发送，未确认」，不重发（避免重复上屏）。

## 指针与按键

触控板和遥控器按键复用同一条连接，不等 ack，按顺序发：

```json
{"t":"pointer","action":"move","dx":12.5,"dy":-8.0}
{"t":"pointer","action":"scroll","dx":0,"dy":-3.0}
{"t":"pointer","action":"click","button":"left"}
{"t":"pointer","action":"zoom","dx":0,"dy":1}
{"t":"pointer","action":"down","button":"left"}
{"t":"pointer","action":"up","button":"left"}
{"t":"key","name":"enter"}
```

`action`：`move` / `scroll` / `click` / `down` / `up` / `zoom`。`button`：`left` / `right` / `middle`。
`zoom` 的 `dy` 是步数：正数放大、负数缩小；手机端按两指间距累积，跨过阈值才发一档。

## 键位

手机只发动作 id，按什么键由桌面端决定。`welcome` 里带上当前键位：

```json
{"t":"welcome","v":1,"keys":[
  {"id":"k1","label":"回车","action":"key","key":"enter","modifiers":[]},
  {"id":"k2","label":"删除","action":"key","key":"backspace","modifiers":[]},
  {"id":"k3","label":"esc","action":"key","key":"escape","modifiers":[]},
  {"id":"k4","label":"切换","action":"key","key":"tab","modifiers":["cmd"]},
  {"id":"k5","label":"WB","action":"app","target":"WorkBuddy"}
]}
```

手机按键 → 桌面：

```json
{"t":"key","name":"k1"}
```

桌面端改键位后主动推送，手机不必重连：

```json
{"t":"keymap","keys":[ ... 同上 ... ]}
```

`key` 的取值：`enter` / `tab` / `space` / `escape` / `backspace` / `delete` / `up` / `down` / `left` / `right` / `pageup` / `pagedown` / `home` / `end`。
`action`：`key` 按键，或者 `app` 打开/激活一个应用（`target` 是应用名、bundle id、exe 路径或 .lnk）。
`modifiers`：macOS 用 `cmd` / `ctrl` / `alt` / `shift`，Windows 用 `ctrl` / `alt` / `shift` / `meta`。
老版本会发 `enter` / `delete` / `esc` / `switch` / `custom` 这些别名，桌面端按 k1–k5 兼容。

## 胶囊

手机保存一条胶囊后，以及在每次连接成功时补发本地 `synced=false` 的胶囊：

```json
{"t":"capsule","id":"e772847b-...","text":"把下载链接放到 README 最上面","createdAt":1794406339322,"ms":3800}
```

桌面端按 `id` 去重后落盘；已经标成 `used` 的胶囊，重发不会把状态改回 `new`。

## 保活

任一方可发 `{"t":"ping","ts":<毫秒>}`，对方回 `{"t":"pong","ts":<原样返回>}`。空闲 180 秒由桌面端断开。

## 兼容性

`v` 为主版本号。桌面端接受 `v == 1`；手机端遇到 `welcome.v` 缺失或大于自己的主版本时提示升级。

新增字段必须可选，接收方忽略未知字段；不认识的 `t` 直接忽略。
