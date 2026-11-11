#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // MCP 客户端直接用同一个可执行文件起一个 stdio 服务，不碰窗口。
    if std::env::args().any(|arg| arg == "--mcp") {
        vibetype_lib::mcp::serve();
        return;
    }
    vibetype_lib::run()
}
