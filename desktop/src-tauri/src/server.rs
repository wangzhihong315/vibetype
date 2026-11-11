use std::time::Duration;

use serde::Deserialize;
use serde_json::json;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::tcp::OwnedWriteHalf;
use tokio::net::{TcpListener, TcpStream};
use tokio::time::timeout;

use crate::inject;
use crate::state::{AppState, Entry, TokenKind};

const PROTOCOL_VERSION: u32 = 1;
const IDLE_TIMEOUT: Duration = Duration::from_secs(180);

#[derive(Deserialize, Default)]
struct DeviceInfo {
    #[serde(default)]
    name: String,
    #[serde(default)]
    platform: String,
}

#[derive(Deserialize, Default)]
struct Frame {
    #[serde(default)]
    t: String,
    #[serde(default)]
    v: u32,
    #[serde(default)]
    token: String,
    #[serde(default)]
    device: Option<DeviceInfo>,
    #[serde(default)]
    id: String,
    #[serde(default)]
    text: String,
    #[serde(default, rename = "ms")]
    duration_ms: u32,
    #[serde(default)]
    lang: String,
}

/// 监听局域网，直到进程退出。端口从 47823 开始顺延。
pub async fn run(state: AppState) {
    let mut port: u16 = 47823;
    let listener = loop {
        match TcpListener::bind(("0.0.0.0", port)).await {
            Ok(listener) => break listener,
            Err(_) => {
                if port >= 47890 {
                    return;
                }
                port += 1;
            }
        }
    };

    let address = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string());
    state.set_network(port, address);

    // 配对码定期轮换；上一轮仍然有效，已经配对过的设备用 deviceToken，不受影响。
    let rotator = state.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(180)).await;
            rotator.rotate_code();
        }
    });

    loop {
        match listener.accept().await {
            Ok((stream, _)) => {
                let state = state.clone();
                tokio::spawn(async move {
                    let _ = handle(stream, state).await;
                });
            }
            Err(_) => tokio::time::sleep(Duration::from_millis(200)).await,
        }
    }
}

async fn handle(stream: TcpStream, state: AppState) -> anyhow::Result<()> {
    let _ = stream.set_nodelay(true);
    let (reader, mut writer) = stream.into_split();
    let mut lines = BufReader::new(reader).lines();

    let first = match timeout(Duration::from_secs(5), lines.next_line()).await {
        Ok(Ok(Some(line))) => line,
        _ => return Ok(()),
    };

    let hello: Frame = match serde_json::from_str(&first) {
        Ok(frame) => frame,
        Err(_) => return send_error(&mut writer, "bad_hello", "第一条消息必须是 hello").await,
    };

    if hello.t != "hello" {
        return send_error(&mut writer, "bad_hello", "第一条消息必须是 hello").await;
    }
    if hello.v != PROTOCOL_VERSION {
        return send_error(&mut writer, "unsupported_version", "只支持协议 v1").await;
    }

    let kind = state.check_token(&hello.token);
    if kind == TokenKind::Invalid {
        return send_error(&mut writer, "bad_token", "配对码已过期").await;
    }

    let name = hello.device.as_ref().map(|d| d.name.clone()).unwrap_or_default();
    let platform = hello
        .device
        .as_ref()
        .map(|d| d.platform.clone())
        .unwrap_or_default();

    let issued = if kind == TokenKind::PairCode {
        Some(state.issue_device(&name, &platform))
    } else {
        None
    };

    let welcome = json!({
        "t": "welcome",
        "v": PROTOCOL_VERSION,
        "server": "vibetype/0.1.0",
        "platform": std::env::consts::OS,
        "inject": state.injection(),
        "deviceToken": issued.unwrap_or_default(),
    });
    send_line(&mut writer, welcome).await?;

    state.connected_delta(1);
    if !name.is_empty() {
        state.set_device(&name);
    } else if !platform.is_empty() {
        state.set_device(&platform);
    }

    loop {
        let line = match timeout(IDLE_TIMEOUT, lines.next_line()).await {
            Ok(Ok(Some(line))) => line,
            _ => break,
        };

        let frame: Frame = match serde_json::from_str(&line) {
            Ok(frame) => frame,
            Err(_) => continue,
        };

        match frame.t.as_str() {
            "text" => {
                let text = frame.text.trim().to_string();
                if text.is_empty() {
                    continue;
                }
                let (ok, used) = deliver(&state, text.clone()).await;
                state.push_entry(Entry::new(&text, used));
                let ack = json!({
                    "t": "ack",
                    "id": frame.id,
                    "ok": ok,
                    "mode": used,
                });
                send_line(&mut writer, ack).await?;
            }
            "ping" => {
                send_line(&mut writer, json!({ "t": "pong", "ts": 0 })).await?;
            }
            _ => {}
        }

        let _ = (&frame.duration_ms, &frame.lang);
    }

    state.connected_delta(-1);
    Ok(())
}

/// 先按用户设置的偏好注入；直接打字失败就自动退到粘贴。
async fn deliver(state: &AppState, text: String) -> (bool, &'static str) {
    let preferred = state.injection();
    if preferred == "paste" {
        let payload = text;
        let ok = tokio::task::spawn_blocking(move || inject::paste_text(&payload))
            .await
            .unwrap_or(false);
        return (ok, if ok { "paste" } else { "failed" });
    }

    let payload = text.clone();
    let typed = tokio::task::spawn_blocking(move || inject::type_text(&payload))
        .await
        .unwrap_or(false);
    if typed {
        return (true, "type");
    }

    let payload = text;
    let pasted = tokio::task::spawn_blocking(move || inject::paste_text(&payload))
        .await
        .unwrap_or(false);
    (pasted, if pasted { "paste" } else { "failed" })
}

async fn send_line(writer: &mut OwnedWriteHalf, value: serde_json::Value) -> anyhow::Result<()> {
    let mut line = serde_json::to_string(&value)?;
    line.push('\n');
    writer.write_all(line.as_bytes()).await?;
    writer.flush().await?;
    Ok(())
}

async fn send_error(
    writer: &mut OwnedWriteHalf,
    code: &str,
    message: &str,
) -> anyhow::Result<()> {
    send_line(
        writer,
        json!({ "t": "error", "code": code, "msg": message }),
    )
    .await
}
