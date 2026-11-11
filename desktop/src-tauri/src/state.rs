use std::fs;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use qrcode::render::svg;
use qrcode::QrCode;
use rand::Rng;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::broadcast;

#[derive(Clone, Serialize, Deserialize)]
pub struct Entry {
    pub id: String,
    pub text: String,
    pub at: u64,
    pub mode: String,
}

impl Entry {
    pub fn new(text: &str, mode: &str) -> Self {
        let at = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let id = format!("{at:x}-{:04x}", rand::thread_rng().gen::<u16>());
        Self {
            id,
            text: text.to_string(),
            at,
            mode: mode.to_string(),
        }
    }
}

/// 手机底部那排键：手机只发动作 id，具体按什么键由桌面端决定。
#[derive(Clone, Serialize, Deserialize)]
pub struct KeySlot {
    pub id: String,
    pub label: String,
    /// key = 按键；app = 打开/激活一个应用
    #[serde(default = "default_action")]
    pub action: String,
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub modifiers: Vec<String>,
    /// action = app 时的应用名（macOS 用 .app 名字，Windows 用路径或名称）
    #[serde(default)]
    pub target: String,
}

fn default_action() -> String {
    "key".into()
}

#[derive(Clone, Serialize)]
pub struct DeviceView {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub last_seen: u64,
    pub online: bool,
}

/// 手机上存下来的闪念；桌面只是留一份，方便查看和用 MCP 读。
#[derive(Clone, Serialize, Deserialize)]
pub struct Capsule {
    pub id: String,
    pub text: String,
    pub at: u64,
    #[serde(default)]
    pub duration_ms: u32,
    /// new = 还没被 Agent 消化；used = 已经归纳/处理过。
    #[serde(default = "default_status")]
    pub status: String,
    #[serde(default)]
    pub used_at: u64,
}

fn default_status() -> String {
    "new".into()
}

#[derive(Clone, Serialize)]
pub struct UiState {
    pub address: String,
    pub port: u16,
    pub code: String,
    pub qr_svg: String,
    pub permission: bool,
    pub injection: String,
    pub history: Vec<Entry>,
    pub connected: u32,
    pub device: String,
    pub platform: String,
    pub devices: Vec<DeviceView>,
    pub capsules: Vec<Capsule>,
    pub mcp_keys: bool,
    pub keymap: Vec<KeySlot>,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Device {
    pub token: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub platform: String,
    #[serde(default)]
    pub last_seen: u64,
}

pub struct Inner {
    pub address: String,
    pub port: u16,
    pub code: String,
    /// 上一轮的配对码：轮换时用户可能正在输入，留一个宽限期。
    pub prev_code: String,
    pub qr_svg: String,
    pub devices: Vec<Device>,
    pub capsules: Vec<Capsule>,
    pub keymap: Vec<KeySlot>,
    pub history: Vec<Entry>,
    pub injection: String,
    pub connected: u32,
    /// 最近连上的设备名（手机型号）
    pub device: String,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum TokenKind {
    PairCode,
    Device,
    Invalid,
}

#[derive(Clone)]
pub struct AppState {
    app: AppHandle,
    inner: Arc<Mutex<Inner>>,
    /// 键位改了就推给已连接手机，不用重连。
    keymap_tx: broadcast::Sender<Vec<KeySlot>>,
}

impl AppState {
    pub fn new(app: AppHandle) -> Self {
        let code = new_code();
        let devices = load_devices(&app);
        let capsules = load_json::<Vec<Capsule>>(&app, "capsules.json").unwrap_or_default();
        let keymap = load_json::<Vec<KeySlot>>(&app, "keymap.json")
            .filter(|slots| valid_keymap(slots))
            .unwrap_or_else(default_keymap);
        let history = load_json::<Vec<Entry>>(&app, "history.json").unwrap_or_default();
        let injection = match std::env::var("VIBETYPE_INJECT").as_deref() {
            Ok("paste") => "paste",
            _ => "type",
        };
        let inner = Inner {
            address: "—".into(),
            port: 47823,
            qr_svg: String::new(),
            code,
            prev_code: String::new(),
            devices,
            capsules,
            keymap,
            history,
            injection: injection.to_string(),
            connected: 0,
            device: String::new(),
        };
        let (keymap_tx, _) = broadcast::channel(16);
        Self {
            app,
            inner: Arc::new(Mutex::new(inner)),
            keymap_tx,
        }
    }

    pub fn subscribe_keymap(&self) -> broadcast::Receiver<Vec<KeySlot>> {
        self.keymap_tx.subscribe()
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn snapshot(&self) -> UiState {
        let inner = self.lock();
        UiState {
            address: inner.address.clone(),
            port: inner.port,
            code: inner.code.clone(),
            qr_svg: inner.qr_svg.clone(),
            permission: crate::inject::is_trusted(),
            injection: inner.injection.clone(),
            history: inner.history.clone(),
            connected: inner.connected,
            device: inner.device.clone(),
            platform: std::env::consts::OS.to_string(),
            devices: inner
                .devices
                .iter()
                .map(|d| DeviceView {
                    id: device_id(&d.token),
                    name: d.name.clone(),
                    platform: d.platform.clone(),
                    last_seen: d.last_seen,
                    online: inner.connected > 0
                        && !d.name.is_empty()
                        && d.name == inner.device,
                })
                .collect(),
            capsules: inner.capsules.clone(),
            mcp_keys: mcp_keys_enabled(&self.app),
            keymap: inner.keymap.clone(),
        }
    }

    pub fn emit_state(&self) {
        let snapshot = self.snapshot();
        let _ = self.app.emit("state", snapshot);
    }

    pub fn injection(&self) -> String {
        self.lock().injection.clone()
    }

    pub fn set_injection(&self, mode: String) {
        {
            let mut inner = self.lock();
            inner.injection = if mode == "paste" { "paste".into() } else { "type".into() };
        }
        self.emit_state();
    }

    pub fn set_network(&self, port: u16, address: String) {
        {
            let mut inner = self.lock();
            inner.port = port;
            inner.address = address;
            inner.qr_svg = qr_for(&inner.address, inner.port, &inner.code);
            #[cfg(debug_assertions)]
            println!(
                "VibeType listening on {}:{}  pairing code: {}",
                inner.address, inner.port, inner.code
            );
        }
        self.emit_state();
    }

    pub fn rotate_code(&self) {
        {
            let mut inner = self.lock();
            inner.prev_code = inner.code.clone();
            inner.code = new_code();
            inner.qr_svg = qr_for(&inner.address, inner.port, &inner.code);
        }
        self.emit_state();
    }

    pub fn check_token(&self, token: &str) -> TokenKind {
        let inner = self.lock();
        let pair = secure_eq(token, &inner.code) || secure_eq(token, &inner.prev_code);
        let mut device = false;
        for entry in &inner.devices {
            device |= secure_eq(token, &entry.token);
        }
        match (pair, device) {
            (true, _) => TokenKind::PairCode,
            (false, true) => TokenKind::Device,
            _ => TokenKind::Invalid,
        }
    }

    pub fn issue_device(&self, name: &str, platform: &str) -> String {
        let token = new_token();
        {
            let mut inner = self.lock();
            if inner.devices.len() >= 8 {
                inner.devices.remove(0);
            }
            inner.devices.push(Device {
                token: token.clone(),
                name: name.to_string(),
                platform: platform.to_string(),
                last_seen: now_ms(),
            });
            save_devices(&self.app, &inner.devices);
        }
        token
    }

    pub fn connected_delta(&self, delta: i32) {
        {
            let mut inner = self.lock();
            inner.connected = (inner.connected as i32 + delta).max(0) as u32;
        }
        self.emit_state();
    }

    pub fn set_device(&self, name: &str) {
        {
            let mut inner = self.lock();
            inner.device = name.to_string();
        }
        self.emit_state();
    }

    /// 撤销所有已配对设备，手机下次连接必须重新输配对码。
    /// 连接时记一下时间，设备页显示「刚刚在线」用。
    pub fn touch_device(&self, token: &str) {
        let changed = {
            let mut inner = self.lock();
            let mut changed = false;
            for device in inner.devices.iter_mut() {
                if secure_eq(token, &device.token) {
                    device.last_seen = now_ms();
                    changed = true;
                }
            }
            changed
        };
        if changed {
            let devices = self.lock().devices.clone();
            save_devices(&self.app, &devices);
            self.emit_state();
        }
    }

    pub fn revoke_device(&self, id: &str) {
        {
            let mut inner = self.lock();
            inner.devices.retain(|d| device_id(&d.token) != id);
            if inner.device.is_empty() || !inner.devices.iter().any(|d| d.name == inner.device) {
                inner.device.clear();
            }
            save_devices(&self.app, &inner.devices);
        }
        self.emit_state();
    }

    pub fn push_capsule(&self, capsule: Capsule) {
        {
            let mut inner = self.lock();
            // 手机重连后会把没同步过的胶囊再发一遍；已经消化过的状态不能被覆盖。
            let previous = inner
                .capsules
                .iter()
                .find(|item| item.id == capsule.id)
                .map(|item| (item.status.clone(), item.used_at));
            let mut incoming = capsule;
            if let Some((status, used_at)) = previous {
                incoming.status = status;
                incoming.used_at = used_at;
            }
            inner.capsules.retain(|c| c.id != incoming.id);
            inner.capsules.insert(0, incoming);
            inner.capsules.truncate(200);
            save_json(&self.app, "capsules.json", &inner.capsules);
        }
        self.emit_state();
    }

    pub fn keymap(&self) -> Vec<KeySlot> {
        self.lock().keymap.clone()
    }

    pub fn set_keymap(&self, slots: Vec<KeySlot>) -> bool {
        if !valid_keymap(&slots) {
            return false;
        }
        {
            let mut inner = self.lock();
            inner.keymap = slots;
            save_json(&self.app, "keymap.json", &inner.keymap);
            let _ = self.keymap_tx.send(inner.keymap.clone());
        }
        self.emit_state();
        true
    }

    pub fn reset_keymap(&self) {
        {
            let mut inner = self.lock();
            inner.keymap = default_keymap();
            save_json(&self.app, "keymap.json", &inner.keymap);
            let _ = self.keymap_tx.send(inner.keymap.clone());
        }
        self.emit_state();
    }

    /// 手机发来的是动作 id（k1..k5），老版本还会发 enter/delete/... 等别名。
    pub fn resolve_key(&self, name: &str) -> Option<KeySlot> {
        let alias = match name {
            "enter" => "k1",
            "delete" => "k2",
            "esc" => "k3",
            "switch" => "k4",
            "custom" => "k5",
            other => other,
        };
        let inner = self.lock();
        inner.keymap.iter().find(|slot| slot.id == alias).cloned()
    }

    /// MCP 进程也会往 capsules.json 里写，定时把文件读回来。
    pub fn reload_capsules(&self) {
        let Some(items) = load_json::<Vec<Capsule>>(&self.app, "capsules.json") else {
            return;
        };
        {
            let mut inner = self.lock();
            if inner.capsules.len() == items.len()
                && inner
                    .capsules
                    .iter()
                    .zip(items.iter())
                    .all(|(a, b)| a.id == b.id && a.text == b.text && a.status == b.status)
            {
                return;
            }
            inner.capsules = items;
        }
        self.emit_state();
    }

    pub fn remove_capsule(&self, id: &str) {
        {
            let mut inner = self.lock();
            inner.capsules.retain(|c| c.id != id);
            save_json(&self.app, "capsules.json", &inner.capsules);
        }
        self.emit_state();
    }

    pub fn set_mcp_keys(&self, enabled: bool) {
        write_mcp_keys(&self.app, enabled);
        self.emit_state();
    }

    pub fn revoke_devices(&self) {
        {
            let mut inner = self.lock();
            inner.devices.clear();
            inner.device.clear();
            save_devices(&self.app, &inner.devices);
        }
        self.emit_state();
    }

    pub fn push_entry(&self, entry: Entry) {
        {
            let mut inner = self.lock();
            inner.history.insert(0, entry.clone());
            inner.history.truncate(50);
            save_json(&self.app, "history.json", &inner.history);
        }
        let _ = self.app.emit("text", &entry);
        self.emit_state();
    }

    pub fn clear_history(&self) {
        {
            let mut inner = self.lock();
            inner.history.clear();
            save_json(&self.app, "history.json", &inner.history);
        }
        self.emit_state();
    }
}

pub const KEYS: [&str; 14] = [
    "enter", "tab", "space", "escape", "backspace", "delete", "up", "down", "left", "right",
    "pageup", "pagedown", "home", "end",
];

fn modifiers_for_os() -> [&'static str; 4] {
    if cfg!(target_os = "macos") {
        ["cmd", "ctrl", "alt", "shift"]
    } else {
        ["ctrl", "alt", "shift", "meta"]
    }
}

fn valid_keymap(slots: &[KeySlot]) -> bool {
    if slots.len() != 5 {
        return false;
    }
    let allowed_mods = modifiers_for_os();
    for (index, slot) in slots.iter().enumerate() {
        if slot.id != format!("k{}", index + 1) {
            return false;
        }
        if slot.label.trim().is_empty() || slot.label.chars().count() > 12 {
            return false;
        }
        match slot.action.as_str() {
            "app" => {
                let target = slot.target.trim();
                if target.is_empty() || target.chars().count() > 200 {
                    return false;
                }
            }
            "key" => {
                if !KEYS.contains(&slot.key.as_str()) {
                    return false;
                }
                if slot.modifiers.iter().any(|m| !allowed_mods.contains(&m.as_str())) {
                    return false;
                }
            }
            _ => return false,
        }
    }
    true
}

pub fn default_keymap() -> Vec<KeySlot> {
    let mac = cfg!(target_os = "macos");
    let switch_mods: Vec<String> = if mac {
        vec!["cmd".into()]
    } else {
        vec!["alt".into()]
    };
    let custom_mods: Vec<String> = if mac {
        vec!["ctrl".into()]
    } else {
        vec!["meta".into()]
    };
    let custom_key = if mac { "up" } else { "tab" };
    vec![
        KeySlot {
            id: "k1".into(),
            label: "回车".into(),
            action: "key".into(),
            key: "enter".into(),
            modifiers: vec![],
            target: String::new(),
        },
        KeySlot {
            id: "k2".into(),
            label: "删除".into(),
            action: "key".into(),
            key: "backspace".into(),
            modifiers: vec![],
            target: String::new(),
        },
        KeySlot {
            id: "k3".into(),
            label: "esc".into(),
            action: "key".into(),
            key: "escape".into(),
            modifiers: vec![],
            target: String::new(),
        },
        KeySlot {
            id: "k4".into(),
            label: "切换".into(),
            action: "key".into(),
            key: "tab".into(),
            modifiers: switch_mods,
            target: String::new(),
        },
        KeySlot {
            id: "k5".into(),
            label: "自定义".into(),
            action: "key".into(),
            key: custom_key.into(),
            modifiers: custom_mods,
            target: String::new(),
        },
    ]
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// 设备列表里给前端一个稳定的短 id，别把长期 token 递进网页。
fn device_id(token: &str) -> String {
    let mut hash: u32 = 0x811c9dc5;
    for byte in token.as_bytes() {
        hash ^= *byte as u32;
        hash = hash.wrapping_mul(0x01000193);
    }
    format!("{hash:08x}")
}

fn new_code() -> String {
    format!("{:06}", rand::thread_rng().gen_range(0..1_000_000u32))
}

fn new_token() -> String {
    let bytes: [u8; 16] = rand::thread_rng().gen();
    hex::encode(bytes)
}

/// 已配对设备、胶囊和历史都存在用户配置目录，重启后还在。
pub fn config_path(app: &AppHandle, name: &str) -> Option<std::path::PathBuf> {
    app.path().app_config_dir().ok().map(|dir| dir.join(name))
}

fn devices_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    config_path(app, "devices.json")
}

fn load_json<T: serde::de::DeserializeOwned>(app: &AppHandle, name: &str) -> Option<T> {
    let path = config_path(app, name)?;
    let text = fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

fn save_json<T: Serialize>(app: &AppHandle, name: &str, value: &T) {
    let Some(path) = config_path(app, name) else {
        return;
    };
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    if let Ok(text) = serde_json::to_string_pretty(value) {
        let _ = fs::write(path, text);
    }
}

fn mcp_config_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    config_path(app, "mcp.json")
}

fn mcp_keys_enabled(app: &AppHandle) -> bool {
    let Some(path) = mcp_config_path(app) else {
        return false;
    };
    let Ok(text) = fs::read_to_string(path) else {
        return false;
    };
    let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) else {
        return false;
    };
    value.get("allowKeys").and_then(|v| v.as_bool()).unwrap_or(false)
}

fn write_mcp_keys(app: &AppHandle, enabled: bool) {
    let Some(path) = mcp_config_path(app) else {
        return;
    };
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    let _ = fs::write(path, serde_json::json!({ "allowKeys": enabled }).to_string());
}

fn load_devices(app: &AppHandle) -> Vec<Device> {
    let Some(path) = devices_path(app) else {
        return Vec::new();
    };
    let Ok(text) = fs::read_to_string(path) else {
        return Vec::new();
    };
    serde_json::from_str(&text).unwrap_or_default()
}

fn save_devices(app: &AppHandle, devices: &[Device]) {
    let Some(path) = devices_path(app) else {
        return;
    };
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    if let Ok(text) = serde_json::to_string_pretty(devices) {
        let _ = fs::write(path, text);
    }
}

fn secure_eq(a: &str, b: &str) -> bool {
    let a = a.as_bytes();
    let b = b.as_bytes();
    let mut diff = (a.len() ^ b.len()) as u8;
    for index in 0..a.len().max(b.len()) {
        let x = a.get(index).copied().unwrap_or(0);
        let y = b.get(index).copied().unwrap_or(0);
        diff |= x ^ y;
    }
    diff == 0
}

fn qr_for(address: &str, port: u16, code: &str) -> String {
    if address == "—" {
        return String::new();
    }
    let uri = format!("vibetype://pair?h={address}&p={port}&c={code}");
    match QrCode::new(uri.as_bytes()) {
        Ok(qr) => qr
            .render::<svg::Color>()
            .min_dimensions(190, 190)
            .quiet_zone(true)
            .build(),
        Err(_) => String::new(),
    }
}
