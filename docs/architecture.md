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
SenseVoiceSmall int8（sherpa-onnx，非自回归，一次前向）
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
| 录音 | `AudioRecord` 16 kHz / 16 bit / 单声道 | SenseVoice 固定输入 |
| 端点 | 按住说话，松开结束；首尾按能量裁剪 | 不用 VAD 模型，v0.1 保持简单 |
| 识别 | sherpa-onnx `OfflineRecognizer` + SenseVoice int8 | CPU，arm64-v8a |
| 模型分发 | 随 APK 打包进 `assets/models/sensevoice/`（228 MB，APK 约 284 MB） | 无需首次下载；换模型要重新发版 |
| 网络 | 前台 TCP 客户端，NDJSON | 断线指数退避重连 |
| 配对 | 扫二维码（host/port/token）或手输六位码 | token 存 SharedPreferences |

模型体积（int8）：`model.int8.onnx` 约 230 MB，`tokens.txt` 约 300 KB。首次下载需在 Wi-Fi 下进行，界面显示进度和校验（sha256）。

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

- **中英混排里的英文术语容易错**：测试录音里的「JSON」被识别成「J song」，
  强制 `language=zh` 后变成「J some」，两种设置都没解决。这不是语言判定问题，
  是声学/词表问题。下一步可以：换 2025-09-09 的 int8 模型对比；在手机端加一张
  常用术语替换表（JSON / README / API 等）；或在桌面端做一次轻量后处理。
- **速度**：4.8 秒录音在 arm64 模拟器上转写 410 ms（RTF ≈ 0.09），真机（红米 K80）
  还没测。模拟器与真机 CPU 调度差异较大，这个数字只能当量级参考。
- **安装包体积**：模型（228 MB）随 APK 打包，debug APK 约 284 MB。GitHub 单文件
  限制 100 MB，所以模型不进仓库，构建前用 `tools/fetch_model.sh` 拉取；CI 也走这一步。

## 安全模型

局域网内使用一次性配对码换取长期 token；连接必须携带 token。不做 TLS（自签证书的复杂度大于收益），但服务只监听局域网地址，且 token 校验使用常量时间比较。桌面端可随时「撤销所有设备」。

## 为什么不用虚拟麦克风

虚拟麦克风方案把电脑当成识别端，就必须解决：音频传输、虚拟声卡驱动、输入法唤起、输入法状态。四个环节任一失败都表现为「没反应」。文字注入只依赖操作系统最稳定的一个接口——键盘事件。

代价是：识别质量取决于手机端模型。这是选择 SenseVoice 的原因（中英日韩粤、非自回归、int8 可跑）。

## 许可

- 本项目代码：MIT。
- SenseVoiceSmall 权重：FunASR Model License v1.1。允许商用，要求在衍生物中保留版权声明与许可文本，且不得使用「FunASR」「SenseVoice」作为自身产品名背书。
- sherpa-onnx：Apache-2.0。
- 第三方转换的 int8 权重需在发布前单独核对来源与许可。
