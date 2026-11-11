# 架构

## 一句话

手机负责「听见并写下来」，电脑负责「把字打进去」。两者之间只有文字。

## 数据流

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

| 项 | 选择 | 说明 |
|---|---|---|
| 语言/UI | Kotlin + Compose | 沿用 VibePad 的视觉语言 |
| 录音 | `AudioRecord` 16 kHz / 16 bit / 单声道 | X-ASR 固定输入 |
| 端点 | 按住说话，松开结束；收尾补 1.2 s 静音 | 流式模型需要尾部静音把最后一段冲出来 |
| 识别 | sherpa-onnx `OnlineRecognizer` + X-ASR 960ms int8 | 流式，热词，CPU arm64-v8a |
| 模型分发 | 随 APK 打包进 `assets/models/x-asr/`（162 MB，APK 约 217 MB） | 无需首次下载；换模型要重新发版 |
| 网络 | 前台 TCP 客户端，NDJSON | 断线指数退避重连 |
| 配对 | 扫二维码（host/port/token）或手输六位码 | token 存 SharedPreferences |

模型体积：encoder 148 MB + decoder 11 MB + joiner 2.5 MB + tokens/bpe，共约 162 MB，随 APK 打包。

识别耗时的目标：红米 K80（骁龙 8 Gen 3）上 5 秒语音 ≤ 1 秒，即 RTF ≤ 0.2。达不到就退到更小的模型（Paraformer-small / 流式 Zipformer），但先以实测为准。

## 桌面端

Tauri 2：Rust 内核 + 系统 WebView，macOS 与 Windows 共用一套源码。

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

### 注入策略

1. **直接注入**（默认）：把文本按 UTF-16 拆成事件，逐个投递。
   - macOS：`CGEventKeyboardSetUnicodeString` + `.cghidEventTap`；
   - Windows：`SendInput` + `KEYEVENTF_UNICODE`。
2. **粘贴兜底**：某些应用（部分 Electron / 终端 / 远程桌面）不吃合成按键。直接注入失败或用户手动切换时，写入剪贴板 → 发 ⌘V / Ctrl+V → 300 ms 后恢复原剪贴板。

权限：

- macOS 需要「辅助功能」授权，首次启动引导用户去系统设置打开；
- Windows 对普通权限应用无需授权；对以管理员权限运行的目标程序，注入会被 UIPI 拦截，此时提示用户以管理员身份运行 VibeType。

### 已知短板（2026-11-11 实测）

- **英文专名和品牌词**：X-ASR 在热词加持下能写对 `Swift`、`JSON`，但 `README`、
  `VibeDeck`、`VibeType` 这类专名仍会错。已加两层：模型热词 + 手机端术语纠正
  （别名、英文近似词、上下文），实测失败用例 23.5% → 0.7%。
- **TTS 评测的局限**：TTS 会把 `CI` 念成罗马数字「一百零一」、把 `VibeType` 读成
  「five type」，所以英文术语的绝对 CER 不可信；真人录音才是金标准。评测集在
  `tools/asr-eval/`。
- **速度**：流式解码在录音过程中完成，松手后只需补 1.2 秒静音收尾；模拟器上收尾
  约 100–120 ms。真机（K80）待测。
- **安装包体积**：模型（162 MB）随 APK 打包，debug APK 约 217 MB。GitHub 单文件
  限制 100 MB，所以模型不进仓库，构建前用 `tools/fetch_model.sh` 拉取；CI 也走这一步。
- **粤语/日/韩**：X-ASR 只支持中英。需要时切到 SenseVoice（可选引擎，228 MB，不随包）。

## 安全模型

局域网内使用一次性配对码换取长期 token；连接必须携带 token。不做 TLS（自签证书的复杂度大于收益），但服务只监听局域网地址，且 token 校验使用常量时间比较。桌面端可随时「撤销所有设备」。

## 为什么不用虚拟麦克风

虚拟麦克风方案把电脑当成识别端，就必须解决：音频传输、虚拟声卡驱动、输入法唤起、输入法状态。四个环节任一失败都表现为「没反应」。文字注入只依赖操作系统最稳定的一个接口——键盘事件。

代价是：识别质量取决于手机端模型。默认选 X-ASR（Apache-2.0、流式、zh-en、术语最好）；粤语/日/韩切到可选的 SenseVoice。

## 许可

- 本项目代码：MIT。
- 默认模型 X-ASR：Apache-2.0。
- 可选 SenseVoiceSmall 权重：FunASR Model License v1.1。允许商用，要求在衍生物中保留版权声明与许可文本，且不得使用「FunASR」「SenseVoice」作为自身产品名背书。
- sherpa-onnx：Apache-2.0。
- 第三方转换的 int8 权重需在发布前单独核对来源与许可。
