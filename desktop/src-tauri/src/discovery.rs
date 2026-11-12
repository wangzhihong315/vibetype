//! 局域网自动发现：手机换 Wi-Fi、电脑换 IP 之后，靠广播找到桌面端。
//!
//! 这层只负责“告诉我你在哪”。真正的身份校验仍然走 TCP 的 hello/deviceToken，
//! 而且手机会核对发现回包里的 desktopId，避免连到同网段的另一台 VibeType。

use std::net::UdpSocket;
use std::thread;

pub const DISCOVERY_PORT: u16 = 47824;
const MAGIC: &[u8] = b"VIBETYPE_DISCOVER";

pub fn spawn(tcp_port: u16, desktop_id: String) {
    thread::spawn(move || {
        let socket = match UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)) {
            Ok(socket) => socket,
            Err(_) => return,
        };
        let _ = socket.set_broadcast(true);
        let mut buf = [0u8; 128];
        loop {
            let Ok((n, from)) = socket.recv_from(&mut buf) else {
                continue;
            };
            if &buf[..n] != MAGIC {
                continue;
            }
            let reply = format!(
                "{{\"vibetype\":1,\"id\":\"{desktop_id}\",\"port\":{tcp_port},\"platform\":\"{}\"}}",
                std::env::consts::OS
            );
            let _ = socket.send_to(reply.as_bytes(), from);
        }
    });
}
