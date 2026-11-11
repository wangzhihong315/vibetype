mod inject;
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

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
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
            autostart_enabled,
            set_autostart,
            open_permission_settings
        ])
        .run(tauri::generate_context!())
        .expect("error while running VibeType");
}
