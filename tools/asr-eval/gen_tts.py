#!/usr/bin/env python3
"""用 macOS 自带 TTS 生成回归音频。

注意：中文音色读英文术语会走样，生成的数据只用于同一模型改版前后的相对比较。
真人录音才是金标准。
"""
import argparse
import json
import pathlib
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="cases.json")
    parser.add_argument("--out", default="audio-tts")
    parser.add_argument("--voice", default="Tingting")
    parser.add_argument("--voice-en", default="Samantha")
    parser.add_argument("--rate", type=int, default=180)
    args = parser.parse_args()

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    cases = json.load(open(args.cases, encoding="utf-8"))

    for case in cases:
        voice = args.voice_en if case["cat"] == "英文" else args.voice
        aiff = out / f"{case['id']}.aiff"
        wav = out / f"{case['id']}.wav"
        subprocess.run(
            ["say", "-v", voice, "-r", str(args.rate), "-o", str(aiff), case["text"]],
            check=True,
        )
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error", "-i", str(aiff),
                "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav),
            ],
            check=True,
        )
        aiff.unlink()
        print(f"{case['id']}  {voice}  {case['cat']}")


if __name__ == "__main__":
    main()
