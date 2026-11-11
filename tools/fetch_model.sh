#!/usr/bin/env bash
# 下载 SenseVoice int8 模型，解到 Android assets，供打包进 APK。
#
# 模型 228 MB，GitHub 单文件限制 100 MB，所以不进 git；
# 构建前跑一次即可，缓存放在 .cache/（已 gitignore）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets/models/sensevoice"
CACHE="${MODEL_CACHE:-$ROOT/.cache}"
ARCHIVE="$CACHE/sensevoice-int8.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

mkdir -p "$CACHE" "$ASSETS"

if [ -f "$ASSETS/model.int8.onnx" ] && [ -f "$ASSETS/tokens.txt" ]; then
    echo "模型已就位：$ASSETS"
    exit 0
fi

if [ ! -f "$ARCHIVE" ]; then
    echo "下载模型（约 163 MB）..."
    curl -L --retry 3 --connect-timeout 15 -o "$ARCHIVE" "$URL"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xjf "$ARCHIVE" -C "$TMP"

SRC="$(find "$TMP" -maxdepth 1 -type d -name '*sense-voice*' | head -1)"
if [ -z "$SRC" ]; then
    echo "压缩包里没找到模型目录" >&2
    exit 1
fi

cp "$SRC/model.int8.onnx" "$SRC/tokens.txt" "$SRC/LICENSE" "$ASSETS/"
echo "已放入 $ASSETS："
ls -lh "$ASSETS"
