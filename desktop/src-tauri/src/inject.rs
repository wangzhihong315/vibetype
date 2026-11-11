use std::time::Duration;

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
