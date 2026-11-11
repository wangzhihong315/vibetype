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

const INSTRUCTIONS: &str = r#"VibeType is a thought-capsule inbox on the phone. The user holds the capsule key, speaks one thought, and the phone transcribes it locally; capsules sync to the desktop when connected. A capsule is raw material — not a todo, not a finished note.
Suggested flow: call capsules_list with status=new, group related items into a few threads, and point out what can be done now versus what still needs context. Ask one question if information is missing. Only after the user confirms, call capsules_mark with those ids. Use text_type to put the result back where the user is writing. Never batch-mark or delete without explicit consent.
Full guide: vibetype://skill.
闪念胶囊是手机上的灵感收件箱，不是待办。按住胶囊键说一句，手机本地转写，连电脑时同步。先用 capsules_list 取 status=new，归纳后让用户确认，再用 capsules_mark 标记；需要落回文档时用 text_type。完整说明见 vibetype://skill。"#;

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
            "List thought capsules. Returns unprocessed (status=new) newest first by default. 列出闪念胶囊，默认只给未消化的。",
            json!({
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["new", "used", "all"],
                        "description": "Filter by status; default new / 按状态过滤，默认 new"
                    },
                    "limit": { "type": "number", "description": "Max items; default 20 / 最多返回几条" }
                }
            }),
        ),
        tool(
            "capsules_get",
            "Read one capsule by id. 按 id 取一条胶囊的全文和时间。",
            json!({
                "type": "object",
                "properties": { "id": { "type": "string" } },
                "required": ["id"]
            }),
        ),
        tool(
            "capsules_mark",
            "Mark capsules as used (digested) or move them back to new. Call only after the user confirms. 用户确认后再标记。",
            json!({
                "type": "object",
                "properties": {
                    "ids": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "Capsule ids to mark / 要标记的胶囊 id"
                    },
                    "status": { "type": "string", "enum": ["used", "new"] }
                },
                "required": ["ids", "status"]
            }),
        ),
        tool(
            "capsules_create",
            "Create a capsule. Only when the user explicitly asks to note something. 仅在用户明确说「记一下」时使用。",
            json!({
                "type": "object",
                "properties": { "text": { "type": "string" } },
                "required": ["text"]
            }),
        ),
        tool(
            "capsules_delete",
            "Delete a capsule by id. Requires explicit user consent. 删除前必须得到用户明确同意。",
            json!({
                "type": "object",
                "properties": { "id": { "type": "string" } },
                "required": ["id"]
            }),
        ),
        tool(
            "text_type",
            "Type text into the app at the cursor, so the result lands back in the document. 把文字打到当前光标。",
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
            "Press a key: enter / delete / esc / switch. Off by default; enable it in VibeType. 默认关闭，需在 VibeType 里打开。",
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
        "title": "Organize capsules / 整理闪念胶囊",
        "description": "Take unprocessed capsules, group by theme, give at most three next steps, and mark used only after the user confirms. / 取未消化的胶囊，按主题归纳，最多三个下一步，确认后再标记。",
        "arguments": [
            {
                "name": "focus",
                "description": "Optional: focus on one area (product, writing, errands). / 可选：这次只关心哪一类",
                "required": false
            }
        ]
    })]
}

fn prompt_messages(focus: Option<&str>) -> Value {
    let focus_line = focus
        .map(|value| format!("Only look at items related to \"{value}\" .\n"))
        .unwrap_or_default();
    json!({
        "description": "Organize capsules / 整理闪念胶囊",
        "messages": [
            {
                "role": "user",
                "content": {
                    "type": "text",
                    "text": format!(
                        "Help me organize my thought capsules.\n{focus_line}\
                         First call capsules_list with status=new. Merge related items into a few threads, \
                         and point out what can be done now versus what still needs context.\n\
                         Give me at most three next steps, and say which one is worth doing today.\n\
                         After I confirm, call capsules_mark to mark those ids used. Reply in my language."
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
        Err(_) => text_result("Serialization failed".into(), true),
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
                        "The inbox is empty — no unprocessed capsules.".into()
                    } else {
                        "No capsules matched.".into()
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
                None => text_result("Capsule not found.".into(), true),
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
                return text_result("ids must not be empty.".into(), true);
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
                return text_result("None of those ids exist.".into(), true);
            }
            write_json("capsules.json", &items);
            text_result(format!("Marked {changed} capsule(s) as {next}."), false)
        }
        "capsules_create" => {
            let text = args
                .get("text")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            if text.is_empty() {
                return text_result("text must not be empty.".into(), true);
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
            text_result(format!("Created capsule {id}"), false)
        }
        "capsules_delete" => {
            let id = args.get("id").and_then(|v| v.as_str()).unwrap_or("");
            let mut items = capsules();
            let before = items.len();
            items.retain(|item| item.id != id);
            if items.len() == before {
                return text_result("Capsule not found.".into(), true);
            }
            write_json("capsules.json", &items);
            text_result("Deleted.".into(), false)
        }
        "text_type" => {
            let text = args.get("text").and_then(|v| v.as_str()).unwrap_or("");
            if text.is_empty() {
                return text_result("text must not be empty.".into(), true);
            }
            if inject::type_text(text) {
                text_result("Typed at the cursor.".into(), false)
            } else {
                text_result("No typing permission. Grant Accessibility in System Settings.".into(), true)
            }
        }
        "keys_press" => {
            if !keys_allowed() {
                return text_result("Key control is disabled in VibeType.".into(), true);
            }
            let key = args.get("key").and_then(|v| v.as_str()).unwrap_or("");
            if inject::press_key(key) {
                text_result(format!("Pressed {key}"), false)
            } else {
                text_result(format!("Unknown key: {key}"), true)
            }
        }
        _ => text_result(format!("No such tool: {name}"), true),
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
                                "title": "VibeType capsules Skill / 闪念胶囊 Skill",
                                "mimeType": "text/markdown",
                                "description": "Agent guide to capsules: what they are, why they work this way, the standard flow and boundaries. / 给 Agent 的胶囊使用说明。"
                            }]
                        }),
                    );
                }
            }
            "resources/read" => {
                let Some(id) = &id else { continue };
                let uri = params.get("uri").and_then(|v| v.as_str()).unwrap_or("");
                if uri != "vibetype://skill" {
                    respond_error(id, -32602, "No such resource");
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
