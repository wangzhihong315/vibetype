# ASR 评测

同一套句子，跑不同模型，算字错率（CER）和实时率（RTF）。

## 数据来源的优先级

1. **真人录音（金标准）**：按 `cases.json` 念一遍，每句一个 `<id>.wav`，16 kHz / 单声道 / PCM16。最好有两个人：本人正常语速 + 一位有口音的同事。
2. **TTS（回归用）**：`gen_tts.py` 用 macOS 音色生成，只用于同一模型改版前后的相对比较。中文音色读英文术语会走样，绝对 CER 不可当真。

## 目录约定

```
audio/
  short1.wav
  code1.wav
  ...
```

## 跑评测

```bash
python bench.py --engine sensevoice \
  --model-dir /path/to/sherpa-onnx-sense-voice-... \
  --audio-dir ./audio --cases cases.json
```

支持的引擎：

- `sensevoice`：离线，`model.int8.onnx` + `tokens.txt`
- `nano`：Fun-ASR-Nano，同样走 `from_sense_voice` 加载
- `xasr`：流式 transducer，`encoder.int8.onnx` + `decoder.onnx` + `joiner.int8.onnx` + `tokens.txt`

输出逐句 CER、平均 CER、总 RTF。英文句子单独算，不混进中文平均。

## 已知结论（TTS，2026-11-11）

- SenseVoice 2024：日常中文 1–2%，纯英文 0%，编码术语 26–37%
- SenseVoice 2025：中文略好，但英文被识别成粤语，不可用
- 术语纠正层（别名 + 英文近似词 + 上下文）：失败用例 23.5% → 0.7%

真人录音补齐后，以上数字需要重跑。
