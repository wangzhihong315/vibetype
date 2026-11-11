//! `vibetype --mcp`：把转写和胶囊通过 stdio 暴露给 AI 客户端。
//!
//! 单独进程读同一份配置目录里的 JSON，不和主程序抢窗口；
//! 客户端退出时进程自然结束。

use std::fs;
use std::io::{self, BufRead, Write};
use std::path::PathBuf;

use serde::Deserialize;
use serde_json::{json, Value};

use crate::inject;

#[derive(Deserialize)]
struct Entry {
    text: String,
    at: u64,
}

#[derive(Deserialize, serde::Serialize)]
struct Capsule {
    id: String,
    text: String,
    at: u64,
    #[serde(default)]
    duration_ms: u32,
}

/// 和 Tauri 的 app_config_dir 保持一致。
fn config_dir() -> Option<PathBuf> {
    #[cfg(target_os = "macos")]
    {
        std::env::var_os("HOME").map(|home| {
            PathBuf::from(home)
                .join("Library/Application Support/com.vibetype.desktop")
        })
    }
    #[cfg(target_os = "windows")]
    {
        std::env::var_os("APPDATA")
            .map(|dir| PathBuf::from(dir).join("com.vibetype.desktop"))
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".config")))
            .map(|dir| dir.join("com.vibetype.desktop"))
    }
}

fn read_json<T: serde::de::DeserializeOwned>(name: &str) -> Option<T> {
    let path = config_dir()?.join(name);
    let text = fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

fn write_json<T: serde::Serialize>(name: &str, value: &T) {
    let Some(dir) = config_dir() else {
        return;
    };
    let _ = fs::create_dir_all(&dir);
    if let Ok(text) = serde_json::to_string_pretty(value) {
        let _ = fs::write(dir.join(name), text);
    }
}

fn keys_allowed() -> bool {
    read_json::<Value>("mcp.json")
        .and_then(|value| value.get("allowKeys").and_then(|v| v.as_bool()))
        .unwrap_or(false)
}

fn capsules() -> Vec<Capsule> {
    read_json("capsules.json").unwrap_or_default()
}

fn history(limit: usize) -> Vec<Entry> {
    let mut items: Vec<Entry> = read_json("history.json").unwrap_or_default();
    items.truncate(limit.clamp(1, 100));
    items
}

fn tools() -> Vec<Value> {
    let mut list = vec![
        tool(
            "transcripts_recent",
            "最近在电脑上屏的转写文字，新的在前。",
            json!({
                "type": "object",
                "properties": {
                    "limit": { "type": "number", "description": "返回条数，默认 10" }
                }
            }),
        ),
        tool(
            "capsules_list",
            "列出手机同步过来的闪念胶囊。",
            json!({ "type": "object", "properties": {} }),
        ),
        tool(
            "capsules_get",
            "按 id 取一条胶囊。",
            json!({
                "type": "object",
                "properties": { "id": { "type": "string" } },
                "required": ["id"]
            }),
        ),
        tool(
            "capsules_create",
            "新建一条胶囊，先存到桌面这一份。",
            json!({
                "type": "object",
                "properties": { "text": { "type": "string" } },
                "required": ["text"]
            }),
        ),
        tool(
            "capsules_delete",
            "按 id 删除一条胶囊。",
            json!({
                "type": "object",
                "properties": { "id": { "type": "string" } },
                "required": ["id"]
            }),
        ),
        tool(
            "text_type",
            "把文字打到当前光标所在的应用里。",
            json!({
                "type": "object",
                "properties": { "text": { "type": "string" } },
                "required": ["text"]
            }),
        ),
    ];
    if keys_allowed() {
        list.push(tool(
            "keys_press",
            "按一个键：enter / delete / esc / switch。默认关闭，需在 App 里打开。",
            json!({
                "type": "object",
                "properties": {
                    "key": { "type": "string", "enum": ["enter", "delete", "esc", "switch"] }
                },
                "required": ["key"]
            }),
        ));
    }
    list
}

fn tool(name: &str, description: &str, schema: Value) -> Value {
    json!({
        "name": name,
        "description": description,
        "inputSchema": schema,
    })
}

fn text_result(text: String, is_error: bool) -> Value {
    json!({
        "content": [{ "type": "text", "text": text }],
        "isError": is_error,
    })
}

fn call(name: &str, args: &Value) -> Value {
    match name {
        "transcripts_recent" => {
            let limit = args
                .get("limit")
                .and_then(|v| v.as_u64())
                .unwrap_or(10) as usize;
            let items = history(limit);
            if items.is_empty() {
                return text_result("还没有上屏记录。".into(), false);
            }
            let body = items
                .iter()
                .map(|item| format!("[{}] {}", item.at / 1000, item.text))
                .collect::<Vec<_>>()
                .join("\n");
            text_result(body, false)
        }
        "capsules_list" => {
            let items = capsules();
            if items.is_empty() {
                return text_result("还没有胶囊。".into(), false);
            }
            let body = items
                .iter()
                .map(|item| format!("{} · {:.1}s · {}", item.id, item.duration_ms as f64 / 1000.0, item.text))
                .collect::<Vec<_>>()
                .join("\n");
            text_result(body, false)
        }
        "capsules_get" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            match capsules().into_iter().find(|item| item.id == id) {
                Some(item) => text_result(item.text, false),
                None => text_result("没找到这条胶囊。".into(), true),
            }
        }
        "capsules_create" => {
            let text = args
                .get("text")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            if text.is_empty() {
                return text_result("text 不能为空。".into(), true);
            }
            let mut items = capsules();
            let id = format!("mcp-{}", random_hex());
            let at = now_ms();
            items.insert(
                0,
                Capsule {
                    id: id.clone(),
                    text,
                    at,
                    duration_ms: 0,
                },
            );
            items.truncate(200);
            write_json("capsules.json", &items);
            text_result(format!("已新建胶囊 {id}"), false)
        }
        "capsules_delete" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            let mut items = capsules();
            let before = items.len();
            items.retain(|item| item.id != id);
            if items.len() == before {
                return text_result("没找到这条胶囊。".into(), true);
            }
            write_json("capsules.json", &items);
            text_result("已删除。".into(), false)
        }
        "text_type" => {
            let text = args.get("text").and_then(|v| v.as_str()).unwrap_or("");
            if text.is_empty() {
                return text_result("text 不能为空。".into(), true);
            }
            if inject::type_text(text) {
                text_result("已打到光标。".into(), false)
            } else {
                text_result("没有上屏权限，去系统设置里授权。".into(), true)
            }
        }
        "keys_press" => {
            if !keys_allowed() {
                return text_result("按键控制在 App 里是关闭的。".into(), true);
            }
            let key = args.get("key").and_then(|v| v.as_str()).unwrap_or("");
            if inject::press_key(key) {
                text_result(format!("已按 {key}"), false)
            } else {
                text_result(format!("不认识的键：{key}"), true)
            }
        }
        _ => text_result(format!("没有这个工具：{name}"), true),
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn random_hex() -> String {
    use rand::Rng;
    let bytes: [u8; 6] = rand::thread_rng().gen();
    hex::encode(bytes)
}

fn respond(id: &Value, result: Value) {
    let line = json!({ "jsonrpc": "2.0", "id": id, "result": result });
    let mut out = io::stdout().lock();
    let _ = writeln!(out, "{line}");
    let _ = out.flush();
}

fn respond_error(id: &Value, code: i32, message: &str) {
    let line = json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": { "code": code, "message": message }
    });
    let mut out = io::stdout().lock();
    let _ = writeln!(out, "{line}");
    let _ = out.flush();
}

pub fn serve() {
    let stdin = io::stdin();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let Ok(message) = serde_json::from_str::<Value>(trimmed) else {
            continue;
        };
        let method = message.get("method").and_then(|v| v.as_str()).unwrap_or("");
        let id = message.get("id").cloned();

        match method {
            "initialize" => {
                if let Some(id) = &id {
                    respond(
                        id,
                        json!({
                            "protocolVersion": "2024-11-05",
                            "capabilities": { "tools": {} },
                            "serverInfo": { "name": "vibetype", "version": "0.1.0" }
                        }),
                    );
                }
            }
            "tools/list" => {
                if let Some(id) = &id {
                    respond(id, json!({ "tools": tools() }));
                }
            }
            "tools/call" => {
                let Some(id) = &id else { continue };
                let params = message.get("params").cloned().unwrap_or(Value::Null);
                let name = params.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let args = params.get("arguments").cloned().unwrap_or(json!({}));
                respond(id, call(name, &args));
            }
            "ping" => {
                if let Some(id) = &id {
                    respond(id, json!({}));
                }
            }
            _ => {
                if let Some(id) = &id {
                    respond_error(id, -32601, "不支持的方法");
                }
            }
        }
    }
}
