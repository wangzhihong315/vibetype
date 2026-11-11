use std::fs;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use qrcode::render::svg;
use qrcode::QrCode;
use rand::Rng;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};

#[derive(Clone, Serialize)]
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
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Device {
    pub token: String,
    #[allow(dead_code)]
    pub name: String,
    #[allow(dead_code)]
    pub platform: String,
}

pub struct Inner {
    pub address: String,
    pub port: u16,
    pub code: String,
    /// 上一轮的配对码：轮换时用户可能正在输入，留一个宽限期。
    pub prev_code: String,
    pub qr_svg: String,
    pub devices: Vec<Device>,
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
}

impl AppState {
    pub fn new(app: AppHandle) -> Self {
        let code = new_code();
        let devices = load_devices(&app);
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
            history: Vec::new(),
            injection: injection.to_string(),
            connected: 0,
            device: String::new(),
        };
        Self {
            app,
            inner: Arc::new(Mutex::new(inner)),
        }
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
        }
        let _ = self.app.emit("text", &entry);
        self.emit_state();
    }

    pub fn clear_history(&self) {
        {
            let mut inner = self.lock();
            inner.history.clear();
        }
        self.emit_state();
    }
}

fn new_code() -> String {
    format!("{:06}", rand::thread_rng().gen_range(0..1_000_000u32))
}

fn new_token() -> String {
    let bytes: [u8; 16] = rand::thread_rng().gen();
    hex::encode(bytes)
}

/// 已配对设备存在用户配置目录，桌面端重启后手机不用重新配对。
fn devices_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    app.path()
        .app_config_dir()
        .ok()
        .map(|dir| dir.join("devices.json"))
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
