//! `vibetype --mcp`：把闪念胶囊通过 stdio 暴露给 AI 客户端。
//!
//! 设计取向和锤子的闪念胶囊一致：手机负责「记」，Agent 负责「消化」。
//! 所以这里不暴露转写历史，只暴露胶囊的读、写、标记，外加把结果打回光标。

use std::fs;
use std::io::{self, BufRead, Write};
use std::path::PathBuf;

use serde::Deserialize;
use serde_json::{json, Value};

use crate::inject;

#[derive(Deserialize, serde::Serialize)]
struct Capsule {
    id: String,
    text: String,
    at: u64,
    #[serde(default)]
    duration_ms: u32,
    #[serde(default = "default_status")]
    status: String,
    #[serde(default)]
    used_at: u64,
}

fn default_status() -> String {
    "new".into()
}

const SKILL: &str = include_str!("../../../skills/vibetype-capsules/SKILL.md");

const INSTRUCTIONS: &str = "VibeType 是手机上的闪念胶囊：用户按一下胶囊键说一句话，\
本地转成文字，连上电脑时同步到桌面。胶囊是灵感收件箱，不是待办，也不是笔记终稿。\n\
建议流程：先用 capsules_list 取 status=new 的胶囊，按主题归纳、指出可以立刻做的点；\
信息不够就问一个问题；用户确认后再用 capsules_mark 标成 used。需要把结果放回用户\
正在写的地方时用 text_type。不要替用户批量标记，也不要主动删除胶囊。\
完整说明见资源 vibetype://skill。";

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

fn tools() -> Vec<Value> {
    let mut list = vec![
        tool(
            "capsules_list",
            "列出闪念胶囊。默认只给还没消化的（status=new），按时间新到旧。",
            json!({
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["new", "used", "all"],
                        "description": "按状态过滤，默认 new"
                    },
                    "limit": { "type": "number", "description": "最多返回几条，默认 20" }
                }
            }),
        ),
        tool(
            "capsules_get",
            "按 id 取一条胶囊的全文和时间。",
            json!({
                "type": "object",
                "properties": { "id": { "type": "string" } },
                "required": ["id"]
            }),
        ),
        tool(
            "capsules_mark",
            "把胶囊标成已消化（used）或重新放回收件箱（new）。用户确认后再调用。",
            json!({
                "type": "object",
                "properties": {
                    "ids": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "要标记的胶囊 id"
                    },
                    "status": { "type": "string", "enum": ["used", "new"] }
                },
                "required": ["ids", "status"]
            }),
        ),
        tool(
            "capsules_create",
            "新建一条胶囊。仅在用户明确说「记一下」时使用。",
            json!({
                "type": "object",
                "properties": { "text": { "type": "string" } },
                "required": ["text"]
            }),
        ),
        tool(
            "capsules_delete",
            "按 id 删除一条胶囊。删除前必须得到用户明确同意。",
            json!({
                "type": "object",
                "properties": { "id": { "type": "string" } },
                "required": ["id"]
            }),
        ),
        tool(
            "text_type",
            "把文字打到用户当前光标所在的应用里（整理结果落回文档）。",
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
            "按一个键：enter / delete / esc / switch。默认关闭，需在 VibeType 里打开。",
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

fn prompts() -> Vec<Value> {
    vec![json!({
        "name": "organize_capsules",
        "title": "整理闪念胶囊",
        "description": "取未消化的胶囊，按主题归纳，给出最多三个下一步，等用户确认后再标记 used。",
        "arguments": [
            {
                "name": "focus",
                "description": "可选：这次只关心哪一类（比如产品、写作、家里的杂事）",
                "required": false
            }
        ]
    })]
}

fn prompt_messages(focus: Option<&str>) -> Value {
    let focus_line = focus
        .map(|value| format!("这次只看和「{value}」有关的。"))
        .unwrap_or_default();
    json!({
        "description": "整理闪念胶囊",
        "messages": [
            {
                "role": "user",
                "content": {
                    "type": "text",
                    "text": format!(
                        "帮我整理一下闪念胶囊。{focus_line}\n\
                         先调用 capsules_list，status 用 new。把内容相近的合并成几条线索，\
                         指出哪些可以马上做、哪些还缺信息。\n\
                         最多给我三个下一步，并说明哪个最值得今天做。\n\
                         我确认之后，你再调用 capsules_mark 把对应的 id 标成 used。"
                    )
                }
            }
        ]
    })
}

fn text_result(text: String, is_error: bool) -> Value {
    json!({
        "content": [{ "type": "text", "text": text }],
        "isError": is_error,
    })
}

fn json_result(value: Value) -> Value {
    match serde_json::to_string_pretty(&value) {
        Ok(text) => text_result(text, false),
        Err(_) => text_result("序列化失败".into(), true),
    }
}

fn call(name: &str, args: &Value) -> Value {
    match name {
        "capsules_list" => {
            let status = args
                .get("status")
                .and_then(|v| v.as_str())
                .unwrap_or("new");
            let limit = args.get("limit").and_then(|v| v.as_u64()).unwrap_or(20) as usize;
            let mut items: Vec<Capsule> = capsules()
                .into_iter()
                .filter(|item| match status {
                    "all" => true,
                    "used" => item.status == "used",
                    _ => item.status != "used",
                })
                .collect();
            items.sort_by(|a, b| b.at.cmp(&a.at));
            items.truncate(limit.clamp(1, 200));
            if items.is_empty() {
                return text_result(
                    if status == "new" {
                        "收件箱是空的，没有未消化的胶囊。".into()
                    } else {
                        "没有符合条件的胶囊。".into()
                    },
                    false,
                );
            }
            let payload: Vec<Value> = items
                .iter()
                .map(|item| {
                    json!({
                        "id": item.id,
                        "at": item.at,
                        "duration_ms": item.duration_ms,
                        "status": item.status,
                        "text": item.text,
                    })
                })
                .collect();
            json_result(json!(payload))
        }
        "capsules_get" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            match capsules().into_iter().find(|item| item.id == id) {
                Some(item) => json_result(json!({
                    "id": item.id,
                    "at": item.at,
                    "duration_ms": item.duration_ms,
                    "status": item.status,
                    "used_at": item.used_at,
                    "text": item.text,
                })),
                None => text_result("没找到这条胶囊。".into(), true),
            }
        }
        "capsules_mark" => {
            let ids: Vec<String> = args
                .get("ids")
                .and_then(|v| v.as_array())
                .map(|list| {
                    list.iter()
                        .filter_map(|v| v.as_str().map(|s| s.to_string()))
                        .collect()
                })
                .unwrap_or_default();
            let status = args
                .get("status")
                .and_then(|v| v.as_str())
                .unwrap_or("used");
            if ids.is_empty() {
                return text_result("ids 不能为空。".into(), true);
            }
            let next = if status == "new" { "new" } else { "used" };
            let mut items = capsules();
            let mut changed = 0;
            let at = now_ms();
            for item in items.iter_mut() {
                if ids.iter().any(|id| id == &item.id) {
                    item.status = next.to_string();
                    item.used_at = if next == "used" { at } else { 0 };
                    changed += 1;
                }
            }
            if changed == 0 {
                return text_result("这些 id 都不存在。".into(), true);
            }
            write_json("capsules.json", &items);
            text_result(format!("已把 {changed} 条标记为 {next}。"), false)
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
            items.insert(
                0,
                Capsule {
                    id: id.clone(),
                    text,
                    at: now_ms(),
                    duration_ms: 0,
                    status: "new".into(),
                    used_at: 0,
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
                return text_result("按键控制在 VibeType 里是关闭的。".into(), true);
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
        let params = message.get("params").cloned().unwrap_or(Value::Null);

        match method {
            "initialize" => {
                if let Some(id) = &id {
                    respond(
                        id,
                        json!({
                            "protocolVersion": "2024-11-05",
                            "capabilities": {
                                "tools": {},
                                "prompts": {},
                                "resources": {}
                            },
                            "serverInfo": { "name": "vibetype", "version": "0.2.0" },
                            "instructions": INSTRUCTIONS
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
                let name = params.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let args = params.get("arguments").cloned().unwrap_or(json!({}));
                respond(id, call(name, &args));
            }
            "prompts/list" => {
                if let Some(id) = &id {
                    respond(id, json!({ "prompts": prompts() }));
                }
            }
            "prompts/get" => {
                let Some(id) = &id else { continue };
                if params.get("name").and_then(|v| v.as_str()) != Some("organize_capsules") {
                    respond_error(id, -32602, "没有这个 prompt");
                    continue;
                }
                let focus = params
                    .get("arguments")
                    .and_then(|v| v.get("focus"))
                    .and_then(|v| v.as_str());
                respond(id, prompt_messages(focus));
            }
            "resources/list" => {
                if let Some(id) = &id {
                    respond(
                        id,
                        json!({
                            "resources": [{
                                "uri": "vibetype://skill",
                                "name": "vibetype-capsules",
                                "title": "闪念胶囊 Skill",
                                "mimeType": "text/markdown",
                                "description": "给 Agent 的胶囊使用说明：这是什么、为什么这样设计、标准流程与边界。"
                            }]
                        }),
                    );
                }
            }
            "resources/read" => {
                let Some(id) = &id else { continue };
                let uri = params.get("uri").and_then(|v| v.as_str()).unwrap_or("");
                if uri != "vibetype://skill" {
                    respond_error(id, -32602, "没有这个资源");
                    continue;
                }
                respond(
                    id,
                    json!({
                        "contents": [{
                            "uri": "vibetype://skill",
                            "mimeType": "text/markdown",
                            "text": SKILL
                        }]
                    }),
                );
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
