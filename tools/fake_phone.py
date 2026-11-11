#!/usr/bin/env python3
"""假手机：不录音、不转写，直接走协议把一条文字发到桌面端。

用法：
    python3 tools/fake_phone.py 127.0.0.1 47823 <配对码> "要上屏的文字"

配对码在桌面端窗口里；调试构建也会打印到终端。
"""
import json
import socket
import sys


def main() -> None:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 47823
    code = sys.argv[3] if len(sys.argv) > 3 else ""
    text = sys.argv[4] if len(sys.argv) > 4 else "这是一条来自假手机的测试文字。"
    if not code:
        raise SystemExit("用法: fake_phone.py <host> <port> <配对码> [文字]")

    sock = socket.create_connection((host, port), timeout=5)
    stream = sock.makefile("rwb")

    def send(payload: dict) -> None:
        stream.write((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
        stream.flush()

    def receive() -> dict:
        line = stream.readline()
        if not line:
            raise SystemExit("连接被桌面端关闭")
        return json.loads(line)

    send(
        {
            "t": "hello",
            "v": 1,
            "token": code,
            "device": {"name": "fake-phone", "platform": "android", "app": "test"},
        }
    )
    print("welcome:", receive())

    send(
        {
            "t": "text",
            "id": "fake-1",
            "text": text,
            "ms": 2000,
            "lang": "zh",
            "engine": "test",
        }
    )
    print("ack:", receive())


if __name__ == "__main__":
    main()
