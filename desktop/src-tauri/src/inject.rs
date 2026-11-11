use std::cell::RefCell;
use std::time::Duration;

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Mouse, Settings};
#[cfg(not(target_os = "macos"))]
use enigo::{Key, Keyboard};

/// macOS 需要「辅助功能」授权才能把按键投递给别的 App。
#[cfg(target_os = "macos")]
pub fn is_trusted() -> bool {
    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        fn AXIsProcessTrusted() -> u8;
    }
    unsafe { AXIsProcessTrusted() != 0 }
}

/// 首次启动时弹系统授权框，并把 App 加进「辅助功能」列表。
/// 已授权时不会重复弹。
#[cfg(target_os = "macos")]
pub fn request_trust() -> bool {
    use std::ffi::c_void;

    #[repr(C)]
    struct Opaque {
        _private: [u8; 0],
    }

    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        static kCFBooleanTrue: *const Opaque;
        fn CFDictionaryCreate(
            allocator: *const c_void,
            keys: *const *const c_void,
            values: *const *const c_void,
            num_values: isize,
            key_callbacks: *const c_void,
            value_callbacks: *const c_void,
        ) -> *const Opaque;
        fn CFRelease(value: *const c_void);
    }

    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        static kAXTrustedCheckOptionPrompt: *const Opaque;
        fn AXIsProcessTrustedWithOptions(options: *const Opaque) -> u8;
    }

    unsafe {
        let keys: [*const c_void; 1] = [kAXTrustedCheckOptionPrompt as *const c_void];
        let values: [*const c_void; 1] = [kCFBooleanTrue as *const c_void];
        let options = CFDictionaryCreate(
            std::ptr::null(),
            keys.as_ptr(),
            values.as_ptr(),
            1,
            std::ptr::null(),
            std::ptr::null(),
        );
        let trusted = AXIsProcessTrustedWithOptions(options) != 0;
        if !options.is_null() {
            CFRelease(options as *const c_void);
        }
        trusted
    }
}

#[cfg(not(target_os = "macos"))]
pub fn request_trust() -> bool {
    true
}

#[cfg(not(target_os = "macos"))]
pub fn is_trusted() -> bool {
    true
}

/// 把文本当作键盘输入打出去。macOS 走 CGEvent Unicode，Windows 走 SendInput。
pub fn type_text(text: &str) -> bool {
    use enigo::{Enigo, Keyboard, Settings};

    let Ok(mut enigo) = Enigo::new(&Settings::default()) else {
        return false;
    };
    enigo.text(text).is_ok()
}

thread_local! {
    /// 鼠标/按键走一个线程内复用的 Enigo，避免每个事件都重新初始化。
    static MOUSE: RefCell<Option<Enigo>> = RefCell::new(None);
}

fn with_mouse<T>(f: impl FnOnce(&mut Enigo) -> T) -> Option<T> {
    MOUSE.with(|cell| {
        let mut slot = cell.borrow_mut();
        if slot.is_none() {
            *slot = Enigo::new(&Settings::default()).ok();
        }
        slot.as_mut().map(f)
    })
}

fn button_of(name: &str) -> Button {
    match name {
        "right" => Button::Right,
        "middle" => Button::Middle,
        _ => Button::Left,
    }
}

/// 触控板指针事件。返回是否成功投递。
pub fn pointer(action: &str, button: &str, dx: f32, dy: f32) -> bool {
    with_mouse(|enigo| match action {
        "move" => enigo
            .move_mouse(dx.round() as i32, dy.round() as i32, Coordinate::Rel)
            .is_ok(),
        "scroll" => {
            let mut ok = true;
            let vertical = dy.round() as i32;
            let horizontal = dx.round() as i32;
            if vertical != 0 {
                ok &= enigo.scroll(vertical, Axis::Vertical).is_ok();
            }
            if horizontal != 0 {
                ok &= enigo.scroll(horizontal, Axis::Horizontal).is_ok();
            }
            ok
        }
        "down" => enigo
            .button(button_of(button), Direction::Press)
            .is_ok(),
        "up" => enigo
            .button(button_of(button), Direction::Release)
            .is_ok(),
        "click" => enigo
            .button(button_of(button), Direction::Click)
            .is_ok(),
        _ => false,
    })
    .unwrap_or(false)
}

/// 遥控器上的按键：手机只发动作，这里按桌面端配置的键位执行。
///
/// macOS 直接发 CGEvent 键码。enigo 的 `Key::Backspace` 在当前版本里
/// 投递出去 TextEdit 不认，所以按键这一层不绕它。
#[cfg(target_os = "macos")]
pub fn press_combo(key: &str, modifiers: &[String]) -> bool {
    use core_graphics::event::CGEventFlags;

    let Some(code) = mac_code(key) else {
        return false;
    };
    // ⌃↑（调度中心）用系统服务打开：这台 Mac 上 Chrome 一类 App 会开启
    // Secure Input，合成出来的 ⌃↑ 到不了系统快捷键，但 open 服务不受影响。
    if key == "up" && modifiers.iter().any(|m| m == "ctrl") && modifiers.len() == 1 {
        let opened = std::process::Command::new("open")
            .args(["-b", "com.apple.exposelauncher"])
            .status()
            .map(|status| status.success())
            .unwrap_or(false);
        if opened {
            return true;
        }
    }

    let mut flags = CGEventFlags::empty();
    let mut keys: Vec<(u16, &str)> = Vec::new();
    for modifier in modifiers {
        let (flag, keycode) = match modifier.as_str() {
            "cmd" => (CGEventFlags::CGEventFlagCommand, 55),
            "ctrl" => (CGEventFlags::CGEventFlagControl, 59),
            "alt" => (CGEventFlags::CGEventFlagAlternate, 58),
            "shift" => (CGEventFlags::CGEventFlagShift, 56),
            _ => continue,
        };
        flags |= flag;
        keys.push((keycode, modifier.as_str()));
    }
    // 修饰键要单独按下再松开：像 ⌃↑ 这种系统级快捷键不只看 flags，
    // 只看合成事件里的修饰位，Mission Control 不会响应。
    let mut flags_so_far = CGEventFlags::empty();
    for (keycode, modifier) in &keys {
        flags_so_far |= modifier_flag(*modifier);
        mac_post(*keycode, true, flags_so_far);
        std::thread::sleep(std::time::Duration::from_millis(18));
    }
    let ok = mac_post(code, true, flags) && mac_post(code, false, flags);
    std::thread::sleep(std::time::Duration::from_millis(18));
    for (keycode, _) in keys.iter().rev() {
        mac_post(*keycode, false, flags);
        std::thread::sleep(std::time::Duration::from_millis(18));
    }
    std::thread::sleep(std::time::Duration::from_millis(30));
    ok
}

#[cfg(target_os = "macos")]
fn modifier_flag(name: &str) -> core_graphics::event::CGEventFlags {
    use core_graphics::event::CGEventFlags;
    match name {
        "cmd" => CGEventFlags::CGEventFlagCommand,
        "ctrl" => CGEventFlags::CGEventFlagControl,
        "alt" => CGEventFlags::CGEventFlagAlternate,
        "shift" => CGEventFlags::CGEventFlagShift,
        _ => CGEventFlags::empty(),
    }
}

#[cfg(target_os = "macos")]
fn mac_post(keycode: u16, down: bool, flags: core_graphics::event::CGEventFlags) -> bool {
    use core_graphics::event::{CGEvent, CGEventTapLocation};
    use core_graphics::event_source::{CGEventSource, CGEventSourceStateID};

    let Ok(source) = CGEventSource::new(CGEventSourceStateID::HIDSystemState) else {
        return false;
    };
    let Ok(event) = CGEvent::new_keyboard_event(source, keycode, down) else {
        return false;
    };
    event.set_flags(flags);
    event.post(CGEventTapLocation::HID);
    true
}

#[cfg(target_os = "macos")]
fn mac_code(key: &str) -> Option<u16> {
    Some(match key {
        "enter" => 36,
        "tab" => 48,
        "space" => 49,
        "backspace" => 51,
        "escape" => 53,
        "delete" => 117,
        "home" => 115,
        "pageup" => 116,
        "end" => 119,
        "pagedown" => 121,
        "left" => 123,
        "right" => 124,
        "down" => 125,
        "up" => 126,
        _ => return None,
    })
}

#[cfg(not(target_os = "macos"))]
pub fn press_combo(key: &str, modifiers: &[String]) -> bool {
    let Some(main) = enigo_key(key) else {
        return false;
    };
    with_mouse(|enigo| {
        let mut pressed = Vec::new();
        // 先按修饰键，再点主键，最后倒序松开
        for name in ["ctrl", "alt", "shift", "meta", "cmd"] {
            if modifiers.iter().any(|m| m == name) {
                if let Some(modifier) = enigo_modifier(name) {
                    if enigo.key(modifier, Direction::Press).is_ok() {
                        pressed.push(modifier);
                    }
                }
            }
        }
        let ok = enigo.key(main, Direction::Click).is_ok();
        for modifier in pressed.into_iter().rev() {
            let _ = enigo.key(modifier, Direction::Release);
        }
        ok
    })
    .unwrap_or(false)
}

#[cfg(not(target_os = "macos"))]
fn enigo_key(key: &str) -> Option<Key> {
    Some(match key {
        "enter" => Key::Return,
        "tab" => Key::Tab,
        "space" => Key::Space,
        "backspace" => Key::Backspace,
        "escape" => Key::Escape,
        "delete" => Key::Delete,
        "home" => Key::Home,
        "end" => Key::End,
        "pageup" => Key::PageUp,
        "pagedown" => Key::PageDown,
        "left" => Key::LeftArrow,
        "right" => Key::RightArrow,
        "up" => Key::UpArrow,
        "down" => Key::DownArrow,
        _ => return None,
    })
}

#[cfg(not(target_os = "macos"))]
fn enigo_modifier(name: &str) -> Option<Key> {
    Some(match name {
        "ctrl" => Key::Control,
        "alt" => Key::Alt,
        "shift" => Key::Shift,
        "meta" => Key::Meta,
        "cmd" => Key::Meta,
        _ => return None,
    })
}

/// 旧的固定按键入口：MCP 的 `keys_press` 还在用。
pub fn press_key(name: &str) -> bool {
    match name {
        "enter" => press_combo("enter", &[]),
        "delete" => press_combo("backspace", &[]),
        "esc" => press_combo("escape", &[]),
        "switch" => press_combo(
            "tab",
            &[if cfg!(target_os = "macos") { "cmd" } else { "alt" }.to_string()],
        ),
        "custom" => press_combo(
            if cfg!(target_os = "macos") { "up" } else { "tab" },
            &[if cfg!(target_os = "macos") { "ctrl" } else { "meta" }.to_string()],
        ),
        _ => false,
    }
}

/// 剪贴板 + ⌘V / Ctrl+V，给不吃合成按键的 App 兜底。
pub fn paste_text(text: &str) -> bool {
    let Ok(mut clipboard) = arboard::Clipboard::new() else {
        return false;
    };
    let previous = clipboard.get_text().ok();
    if clipboard.set_text(text.to_string()).is_err() {
        return false;
    }
    std::thread::sleep(Duration::from_millis(80));

    let ok = press_paste();
    std::thread::sleep(Duration::from_millis(350));

    match previous {
        Some(old) => {
            let _ = clipboard.set_text(old);
        }
        None => {
            let _ = clipboard.clear();
        }
    }
    ok
}

/// ⌘V / Ctrl+V。
///
/// 这里不用 enigo 的 `Key::Unicode('v')`：它在 macOS 上会去查当前输入法（TSM），
/// 而 TSM 只能在主线程调用，从 tokio 的阻塞线程里调用会直接被系统 SIGTRAP 掉。
/// 直接发虚拟键码 9（V）并带 Command 修饰位，既不走输入法，也没有线程限制。
#[cfg(target_os = "macos")]
fn press_paste() -> bool {
    use core_graphics::event::{CGEvent, CGEventFlags, CGEventTapLocation};
    use core_graphics::event_source::{CGEventSource, CGEventSourceStateID};

    let Ok(source) = CGEventSource::new(CGEventSourceStateID::CombinedSessionState) else {
        return false;
    };
    let Ok(down) = CGEvent::new_keyboard_event(source.clone(), 9, true) else {
        return false;
    };
    down.set_flags(CGEventFlags::CGEventFlagCommand);
    down.post(CGEventTapLocation::HID);

    let Ok(up) = CGEvent::new_keyboard_event(source, 9, false) else {
        return false;
    };
    up.set_flags(CGEventFlags::CGEventFlagCommand);
    up.post(CGEventTapLocation::HID);
    true
}

#[cfg(not(target_os = "macos"))]
fn press_paste() -> bool {
    use enigo::{Direction, Enigo, Key, Keyboard, Settings};

    let Ok(mut enigo) = Enigo::new(&Settings::default()) else {
        return false;
    };
    let pressed = enigo.key(Key::Control, Direction::Press).is_ok();
    let clicked = enigo.key(Key::Unicode('v'), Direction::Click).is_ok();
    let released = enigo.key(Key::Control, Direction::Release).is_ok();
    pressed && clicked && released
}

pub fn open_settings() {
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open")
            .arg("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
            .spawn();
    }
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("cmd")
            .args(["/C", "start", "ms-settings:easeofaccess"])
            .spawn();
    }
}
