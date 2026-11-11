# 协议 v1

桌面端监听 TCP（默认 `47823`，被占用时顺延），手机主动连接。报文为 **NDJSON**：一行一个 JSON，UTF-8，单行不超过 64 KB。

## 配对

桌面端在配对窗口里展示二维码和六位码，二维码内容：

```
vibetype://pair?h=192.168.1.23&p=47823&c=482913
```

- `h` 局域网 IPv4，`p` 端口，`c` 一次性配对码（120 秒轮换）。
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
{"t":"text","id":"9f2c...","text":"明天记得把 Readme 的英文版补上。","ms":4800,"lang":"zh","engine":"sensevoice-int8"}
```

桌面 → 手机：

```json
{"t":"ack","id":"9f2c...","ok":true,"mode":"type"}
```

`mode`：`type`（按键注入）/ `paste`（剪贴板粘贴兜底）/ `failed`（两者都失败，桌面端会弹提示）。

`text` 里的换行由 JSON 转义为 `\n`，不影响按行分帧。手机端等待 `ack` 最长 5 秒，超时标记为「已发送，未确认」，不重发（避免重复上屏）。

## 保活

任一方可发 `{"t":"ping","ts":<毫秒>}`，对方回 `{"t":"pong","ts":<原样返回>}`。空闲 60 秒由桌面端断开。

## 兼容性

`v` 为主版本号。桌面端接受 `v == 1`；手机端遇到 `welcome.v` 缺失或大于自己的主版本时提示升级。

新增字段必须可选，接收方忽略未知字段；不认识的 `t` 直接忽略。
