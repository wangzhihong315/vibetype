mod inject;
pub mod mcp;
mod server;
mod state;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::Manager;
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

#[tauri::command]
fn ui_state(state: tauri::State<'_, state::AppState>) -> state::UiState {
    state.snapshot()
}

#[tauri::command]
fn regenerate_code(state: tauri::State<'_, state::AppState>) -> state::UiState {
    state.rotate_code();
    state.snapshot()
}

#[tauri::command]
fn set_injection(state: tauri::State<'_, state::AppState>, mode: String) -> state::UiState {
    state.set_injection(mode);
    state.snapshot()
}

#[tauri::command]
fn clear_history(state: tauri::State<'_, state::AppState>) -> state::UiState {
    state.clear_history();
    state.snapshot()
}

#[tauri::command]
fn revoke_devices(state: tauri::State<'_, state::AppState>) -> state::UiState {
    state.revoke_devices();
    state.snapshot()
}

#[tauri::command]
fn revoke_device(state: tauri::State<'_, state::AppState>, id: String) -> state::UiState {
    state.revoke_device(&id);
    state.snapshot()
}

#[tauri::command]
fn remove_capsule(state: tauri::State<'_, state::AppState>, id: String) -> state::UiState {
    state.remove_capsule(&id);
    state.snapshot()
}

#[tauri::command]
fn refresh_capsules(state: tauri::State<'_, state::AppState>) -> state::UiState {
    state.reload_capsules();
    state.snapshot()
}

#[derive(serde::Serialize)]
struct AppEntry {
    name: String,
    path: String,
}

/// 列出现已安装的 App，给「打开应用」选目标用。
#[tauri::command]
fn list_apps() -> Vec<AppEntry> {
    let mut found = Vec::new();

    #[cfg(target_os = "macos")]
    {
        let mut roots: Vec<std::path::PathBuf> = vec![
            "/Applications".into(),
            "/System/Applications".into(),
        ];
        if let Some(home) = std::env::var_os("HOME") {
            roots.push(std::path::PathBuf::from(home).join("Applications"));
        }
        for root in roots {
            let Ok(entries) = std::fs::read_dir(&root) else {
                continue;
            };
            for entry in entries.flatten() {
                let path = entry.path();
                if path.extension().and_then(|ext| ext.to_str()) != Some("app") {
                    continue;
                }
                let Some(name) = path.file_stem().and_then(|name| name.to_str()) else {
                    continue;
                };
                found.push(AppEntry {
                    name: name.to_string(),
                    path: path.to_string_lossy().to_string(),
                });
            }
        }
    }

    #[cfg(target_os = "windows")]
    {
        let mut roots = Vec::new();
        if let Ok(appdata) = std::env::var("APPDATA") {
            roots.push(
                std::path::PathBuf::from(appdata)
                    .join("Microsoft")
                    .join("Windows")
                    .join("Start Menu")
                    .join("Programs"),
            );
        }
        if let Ok(program_data) = std::env::var("ProgramData") {
            roots.push(
                std::path::PathBuf::from(program_data)
                    .join("Microsoft")
                    .join("Windows")
                    .join("Start Menu")
                    .join("Programs"),
            );
        }
        for root in roots {
            collect_shortcuts(&root, 0, &mut found);
        }
    }

    found.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    found.dedup_by(|a, b| a.name == b.name);
    found
}

#[cfg(target_os = "windows")]
fn collect_shortcuts(dir: &std::path::Path, depth: u8, out: &mut Vec<AppEntry>) {
    if depth > 4 {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_shortcuts(&path, depth + 1, out);
            continue;
        }
        if path.extension().and_then(|ext| ext.to_str()).and_then(|ext| Some(ext.eq_ignore_ascii_case("lnk"))) != Some(true) {
            continue;
        }
        let Some(name) = path.file_stem().and_then(|name| name.to_str()) else {
            continue;
        };
        out.push(AppEntry {
            name: name.to_string(),
            path: path.to_string_lossy().to_string(),
        });
    }
}

#[tauri::command]
fn set_keymap(
    state: tauri::State<'_, state::AppState>,
    slots: Vec<state::KeySlot>,
) -> Result<state::UiState, String> {
    if state.set_keymap(slots) {
        Ok(state.snapshot())
    } else {
        Err("键位不合法".into())
    }
}

#[tauri::command]
fn reset_keymap(state: tauri::State<'_, state::AppState>) -> state::UiState {
    state.reset_keymap();
    state.snapshot()
}

#[tauri::command]
fn install_skill(target: String) -> Result<String, String> {
    let home = std::env::var("HOME").map_err(|_| "找不到 HOME".to_string())?;
    let dir = match target.as_str() {
        "claude" => format!("{home}/.claude/skills/vibetype-capsules"),
        "zcode" => format!("{home}/.agents/skills/vibetype-capsules"),
        "opencode" => format!("{home}/.config/opencode/skills/vibetype-capsules"),
        "codex" => format!("{home}/.codex/skills/vibetype-capsules"),
        _ => return Err("不支持的客户端".into()),
    };
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = format!("{dir}/SKILL.md");
    std::fs::write(&path, include_str!("../../../skills/vibetype-capsules/SKILL.md"))
        .map_err(|e| e.to_string())?;
    Ok(path)
}

#[tauri::command]
fn set_mcp_keys(state: tauri::State<'_, state::AppState>, enabled: bool) -> state::UiState {
    state.set_mcp_keys(enabled);
    state.snapshot()
}

#[tauri::command]
fn autostart_enabled(app: tauri::AppHandle) -> bool {
    app.autolaunch().is_enabled().unwrap_or(false)
}

#[tauri::command]
fn set_autostart(app: tauri::AppHandle, enabled: bool) -> Result<bool, String> {
    let manager = app.autolaunch();
    if enabled {
        manager.enable().map_err(|e| e.to_string())?;
    } else {
        manager.disable().map_err(|e| e.to_string())?;
    }
    manager.is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
fn open_permission_settings() {
    inject::open_settings();
}

#[tauri::command]
fn type_text(text: String) -> Result<(), String> {
    if inject::type_text(&text) {
        Ok(())
    } else {
        Err("没有上屏权限".into())
    }
}

pub fn run() {
    tauri::Builder::default()
        // 单实例必须最先注册：第二次启动只把已有窗口叫出来
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .setup(|app| {
            let handle = app.handle().clone();
            let state = state::AppState::new(handle);
            app.manage(state.clone());

            // 首次运行时把系统授权框弹出来，免得用户找不到入口。
            let trusted = inject::request_trust();
            #[cfg(debug_assertions)]
            println!("VibeType accessibility trusted: {trusted}");

            tauri::async_runtime::spawn(server::run(state));

            let show = MenuItem::with_id(app, "show", "打开 VibeType", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;

            let icon = app
                .default_window_icon()
                .cloned()
                .ok_or("缺少应用图标")?;

            TrayIconBuilder::new()
                .icon(icon)
                .tooltip("VibeType")
                .menu(&menu)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;

            // 开机自启时不弹窗口，只在托盘里待着
            let minimized = std::env::args().any(|arg| arg == "--minimized");
            if !minimized {
                if let Some(window) = app.get_webview_window("main") {
                    // 显示器重排后窗口可能落在屏幕外，每次启动居中到主屏
                    let _ = window.center();
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            // 关窗口只是收起，服务继续跑；退出走托盘菜单。
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            ui_state,
            regenerate_code,
            set_injection,
            clear_history,
            revoke_devices,
            revoke_device,
            remove_capsule,
            refresh_capsules,
            install_skill,
            set_keymap,
            reset_keymap,
            list_apps,
            set_mcp_keys,
            autostart_enabled,
            set_autostart,
            open_permission_settings,
            type_text
        ])
        .run(tauri::generate_context!())
        .expect("error while running VibeType");
}
