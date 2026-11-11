#!/usr/bin/env python3
"""同一套句子，跑不同 ASR 模型，算 CER 和 RTF。

用法：
    python bench.py --engine sensevoice --model-dir ... --audio-dir ./audio
    python bench.py --engine xasr --model-dir ... --audio-dir ./audio --hotwords hotwords.txt
"""
import argparse
import array
import json
import re
import time
import wave

import sherpa_onnx


def read_wav(path):
    with wave.open(path, "rb") as w:
        data = w.readframes(w.getnframes())
        sr = w.getframerate()
    samples = array.array("h")
    samples.frombytes(data)
    return [s / 32768.0 for s in samples], sr


def normalize(text):
    return re.sub(r"[，。！？、,.!?;:：；\"'“”‘’()（）\[\]【】…—\-\s]", "", text.lower())


def cer(ref, hyp):
    r, h = normalize(ref), normalize(hyp)
    if not r:
        return 0.0
    prev = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        cur = [i]
        for j, hc in enumerate(h, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rc != hc)))
        prev = cur
    return prev[-1] / len(r)


def load_offline(engine, model_dir, threads):
    if engine in ("sensevoice", "nano"):
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=f"{model_dir}/model.int8.onnx",
            tokens=f"{model_dir}/tokens.txt",
            num_threads=threads,
            use_itn=True,
            language="auto",
        )
    raise SystemExit(f"未知离线引擎: {engine}")


def load_online(model_dir, threads, hotwords):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=f"{model_dir}/tokens.txt",
        encoder=f"{model_dir}/encoder.int8.onnx",
        decoder=f"{model_dir}/decoder.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx",
        num_threads=threads,
        decoding_method="modified_beam_search" if hotwords else "greedy_search",
        hotwords_file=hotwords or "",
        hotwords_score=2.0 if hotwords else 1.5,
        enable_endpoint_detection=False,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=["sensevoice", "nano", "xasr"], required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--audio-dir", required=True)
    parser.add_argument("--cases", default="cases.json")
    parser.add_argument("--hotwords", default="")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()

    cases = json.load(open(args.cases, encoding="utf-8"))
    offline = args.engine in ("sensevoice", "nano")
    rec = (
        load_offline(args.engine, args.model_dir, args.threads)
        if offline
        else load_online(args.model_dir, args.threads, args.hotwords)
    )

    total_audio = total_time = 0.0
    scores = []
    print(f"| id | cat | CER | RTF | text |")
    print("|---|---|---|---|---|")
    for case in cases:
        path = f"{args.audio_dir}/{case['id']}.wav"
        try:
            samples, sr = read_wav(path)
        except FileNotFoundError:
            print(f"| {case['id']} | {case['cat']} | — | — | 缺少 {path} |")
            continue
        start = time.perf_counter()
        if offline:
            stream = rec.create_stream()
            stream.accept_waveform(sr, samples)
            rec.decode_stream(stream)
            text = stream.result.text.strip()
        else:
            stream = rec.create_stream()
            stream.accept_waveform(sr, samples)
            stream.input_finished()
            while rec.is_ready(stream):
                rec.decode_stream(stream)
            text = rec.get_result(stream)
        elapsed = time.perf_counter() - start
        duration = len(samples) / sr
        total_audio += duration
        total_time += elapsed
        err = cer(case["text"], text)
        if case["cat"] != "英文":
            scores.append(err)
        print(
            f"| {case['id']} | {case['cat']} | {err * 100:.1f}% | "
            f"{elapsed / duration:.3f} | {text} |"
        )

    avg = sum(scores) / len(scores) if scores else 0.0
    print(f"\n平均 CER（非英文）：{avg * 100:.1f}%")
    print(f"总 RTF：{total_time / total_audio:.3f}")


if __name__ == "__main__":
    main()
