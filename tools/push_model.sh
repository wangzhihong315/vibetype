#!/usr/bin/env bash
# 把 SenseVoice 模型灌进 debug 包的内部私有目录。
#
# Android 11+ 上 adb push 到 Android/data 的文件 App 读不到（FUSE 归属），
# 所以这里走 run-as 管道，文件直接落在 /data/user/0/com.vibetype/files/。
#
# 用法：tools/push_model.sh /path/to/sherpa-onnx-sense-voice-...-int8-2024-07-17
set -euo pipefail

MODEL_DIR="${1:-}"
if [ -z "$MODEL_DIR" ] || [ ! -d "$MODEL_DIR" ]; then
    echo "用法: $0 <模型目录>" >&2
    exit 1
fi

for file in model.int8.onnx tokens.txt; do
    if [ ! -f "$MODEL_DIR/$file" ]; then
        echo "缺少 $MODEL_DIR/$file" >&2
        exit 1
    fi
done

adb shell "run-as com.vibetype mkdir -p files/models/sensevoice"

for file in model.int8.onnx tokens.txt; do
    printf '推送 %s ... ' "$file"
    cat "$MODEL_DIR/$file" | adb shell "run-as com.vibetype sh -c 'cat > files/models/sensevoice/$file'"
    echo "完成"
done

adb shell "run-as com.vibetype ls -l files/models/sensevoice"
echo "重启 App 后会自动加载模型。"
