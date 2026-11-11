use std::cell::RefCell;
use std::time::Duration;

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Mouse, Settings};
#[cfg(not(target_os = "macos"))]
use enigo::Key;

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

/// 遥控器上的固定按键。
///
/// macOS 直接发 CGEvent 键码。enigo 的 `Key::Backspace` 在当前版本里
/// 投递出去 TextEdit 不认，所以按键这一层不绕它。
#[cfg(target_os = "macos")]
pub fn press_key(name: &str) -> bool {
    use core_graphics::event::CGEventFlags;

    match name {
        "enter" => mac_key(36, CGEventFlags::empty()),
        "delete" => mac_key(51, CGEventFlags::empty()),
        "esc" => mac_key(53, CGEventFlags::empty()),
        "switch" => mac_key(48, CGEventFlags::CGEventFlagCommand),
        "custom" => mac_key(126, CGEventFlags::CGEventFlagControl),
        _ => false,
    }
}

#[cfg(target_os = "macos")]
fn mac_key(keycode: u16, flags: core_graphics::event::CGEventFlags) -> bool {
    use core_graphics::event::{CGEvent, CGEventTapLocation};
    use core_graphics::event_source::{CGEventSource, CGEventSourceStateID};

    let Ok(source) = CGEventSource::new(CGEventSourceStateID::CombinedSessionState) else {
        return false;
    };
    let Ok(down) = CGEvent::new_keyboard_event(source.clone(), keycode, true) else {
        return false;
    };
    down.set_flags(flags);
    down.post(CGEventTapLocation::HID);
    let Ok(up) = CGEvent::new_keyboard_event(source, keycode, false) else {
        return false;
    };
    up.set_flags(flags);
    up.post(CGEventTapLocation::HID);
    true
}

#[cfg(not(target_os = "macos"))]
pub fn press_key(name: &str) -> bool {
    with_mouse(|enigo| match name {
        "enter" => enigo.key(Key::Return, Direction::Click).is_ok(),
        "delete" => enigo.key(Key::Backspace, Direction::Click).is_ok(),
        "esc" => enigo.key(Key::Escape, Direction::Click).is_ok(),
        "switch" => {
            enigo.key(Key::Alt, Direction::Press).is_ok()
                && enigo.key(Key::Tab, Direction::Click).is_ok()
                && enigo.key(Key::Alt, Direction::Release).is_ok()
        }
        "custom" => {
            enigo.key(Key::Meta, Direction::Press).is_ok()
                && enigo.key(Key::Tab, Direction::Click).is_ok()
                && enigo.key(Key::Meta, Direction::Release).is_ok()
        }
        _ => false,
    })
    .unwrap_or(false)
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
