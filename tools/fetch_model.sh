#!/usr/bin/env bash
# 下载 X-ASR 960ms 流式 zh-en 模型，解到 Android assets。
#
# 模型 134 MB，GitHub 单文件限制 100 MB，所以不进 git；
# bpe.vocab / hotwords.txt 在 tools/asr-models/x-asr/ 里，跟代码一起走。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets/models/x-asr"
CACHE="${MODEL_CACHE:-$ROOT/.cache}"
ARCHIVE="$CACHE/x-asr-960ms.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2"
NAME="sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"

mkdir -p "$CACHE" "$ASSETS"

if [ -f "$ASSETS/encoder.int8.onnx" ] && [ -f "$ASSETS/tokens.txt" ]; then
    echo "模型已就位：$ASSETS"
    exit 0
fi

if [ ! -f "$ARCHIVE" ]; then
    echo "下载 X-ASR 模型（约 134 MB）..."
    curl -L --retry 3 --connect-timeout 15 -o "$ARCHIVE" "$URL"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xjf "$ARCHIVE" -C "$TMP"

SRC="$TMP/$NAME"
if [ ! -d "$SRC" ]; then
    SRC="$(find "$TMP" -maxdepth 1 -type d -name '*x-asr*' | head -1)"
fi
if [ -z "$SRC" ]; then
    echo "压缩包里没找到模型目录" >&2
    exit 1
fi

cp "$SRC/encoder.int8.onnx" "$SRC/decoder.onnx" "$SRC/joiner.int8.onnx" \
   "$SRC/tokens.txt" "$SRC/bpe.model" "$ASSETS/"
cp "$ROOT/tools/asr-models/x-asr/bpe.vocab" "$ASSETS/"
cp "$ROOT/tools/asr-models/x-asr/hotwords.txt" "$ASSETS/"
cp "$ROOT/tools/asr-models/x-asr/LICENSE" "$ASSETS/"

echo "已放入 $ASSETS："
ls -lh "$ASSETS"
