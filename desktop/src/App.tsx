import { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

type Entry = { id: string; text: string; at: number; mode: string };
type Device = {
  id: string;
  name: string;
  platform: string;
  last_seen: number;
  online: boolean;
};
type Capsule = { id: string; text: string; at: number; duration_ms: number };

type UiState = {
  address: string;
  port: number;
  code: string;
  qr_svg: string;
  permission: boolean;
  injection: string;
  history: Entry[];
  connected: number;
  device: string;
  platform: string;
  devices: Device[];
  capsules: Capsule[];
  mcp_keys: boolean;
};

type Page = "overview" | "connect" | "devices" | "capsules" | "mcp" | "settings";

const NAV: { id: Page; label: string; icon: string }[] = [
  {
    id: "overview",
    label: "概览",
    icon: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  },
  {
    id: "connect",
    label: "连接",
    icon: '<path d="M9.5 14.5 14.5 9.5"/><path d="M8 12l-2.5 2.5a3.5 3.5 0 0 0 5 5L13 17"/><path d="M16 12l2.5-2.5a3.5 3.5 0 0 0-5-5L11 7"/>',
  },
  {
    id: "devices",
    label: "设备",
    icon: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 19h3"/>',
  },
  {
    id: "capsules",
    label: "胶囊",
    icon: '<rect x="9" y="3.5" width="6" height="17" rx="3"/><path d="M12 7.5v2.5"/>',
  },
  {
    id: "mcp",
    label: "MCP",
    icon: '<path d="M8 8 4 12l4 4M16 8l4 4-4 4M14 5l-4 14"/>',
  },
  {
    id: "settings",
    label: "设置",
    icon: '<path d="M4 7h9M18 7h2M4 17h2M11 17h9"/><circle cx="15.5" cy="7" r="2"/><circle cx="8.5" cy="17" r="2"/>',
  },
];

const CLIENTS: { id: string; label: string; args: string; path: string }[] = [
  {
    id: "claude",
    label: "Claude Code",
    args: "claude mcp add vibetype -- /Applications/VibeType.app/Contents/MacOS/vibetype --mcp",
    path: "~/Library/Application Support/Claude/claude_desktop_config.json",
  },
  {
    id: "zcode",
    label: "ZCode",
    args: "~/.zcode/config.json → mcpServers",
    path: "~/.zcode/config.json",
  },
  {
    id: "cursor",
    label: "Cursor",
    args: "~/.cursor/mcp.json",
    path: "~/.cursor/mcp.json",
  },
];

function modeLabel(mode: string) {
  if (mode === "type") return "直接打字";
  if (mode === "paste") return "粘贴上屏";
  return "没有上屏";
}

function time(ms: number) {
  return new Date(ms).toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function day(ms: number) {
  const at = new Date(ms);
  const today = new Date();
  const sameDay =
    at.getFullYear() === today.getFullYear() &&
    at.getMonth() === today.getMonth() &&
    at.getDate() === today.getDate();
  if (sameDay) return `今天 ${time(ms)}`;
  const yesterday = new Date(today.getTime() - 86_400_000);
  if (
    at.getFullYear() === yesterday.getFullYear() &&
    at.getMonth() === yesterday.getMonth() &&
    at.getDate() === yesterday.getDate()
  ) {
    return `昨天 ${time(ms)}`;
  }
  return `${at.getMonth() + 1} 月 ${at.getDate()} 日 ${time(ms)}`;
}

function ago(ms: number) {
  if (!ms) return "还没连过";
  const diff = Date.now() - ms;
  if (diff < 90_000) return "刚刚在线";
  if (diff < 3_600_000) return `${Math.round(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.round(diff / 3_600_000)} 小时前`;
  return `${Math.round(diff / 86_400_000)} 天前`;
}

function duration(ms: number) {
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

export default function App() {
  const [state, setState] = useState<UiState | null>(null);
  const [page, setPage] = useState<Page>("overview");
  const [autostart, setAutostart] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  useEffect(() => {
    invoke<UiState>("ui_state").then(setState).catch(console.error);
    invoke<boolean>("autostart_enabled").then(setAutostart).catch(() => {});
    const unlisten = listen<UiState>("state", (event) => setState(event.payload));
    // MCP 在另一个进程里写胶囊，隔几秒把文件读回来一次
    const timer = window.setInterval(() => {
      invoke<UiState>("refresh_capsules").then(setState).catch(() => {});
    }, 4000);
    return () => {
      window.clearInterval(timer);
      unlisten.then((fn) => fn()).catch(() => {});
    };
  }, []);

  useEffect(() => {
    if (!flash) return;
    const timer = window.setTimeout(() => setFlash(null), 1600);
    return () => window.clearTimeout(timer);
  }, [flash]);

  useEffect(() => {
    if (!confirmRevoke) return;
    const timer = window.setTimeout(() => setConfirmRevoke(false), 3000);
    return () => window.clearTimeout(timer);
  }, [confirmRevoke]);

  const capsules = state?.capsules ?? [];
  const capsule = useMemo(
    () => capsules.find((item) => item.id === selected) ?? capsules[0] ?? null,
    [capsules, selected]
  );

  async function toggleAutostart() {
    try {
      setAutostart(await invoke<boolean>("set_autostart", { enabled: !autostart }));
    } catch (error) {
      console.error(error);
    }
  }

  async function setMode(mode: string) {
    setState(await invoke<UiState>("set_injection", { mode }));
  }

  async function regenerate() {
    setState(await invoke<UiState>("regenerate_code"));
  }

  async function clearHistory() {
    setState(await invoke<UiState>("clear_history"));
  }

  async function revokeAll() {
    if (!confirmRevoke) {
      setConfirmRevoke(true);
      return;
    }
    setConfirmRevoke(false);
    setState(await invoke<UiState>("revoke_devices"));
  }

  async function revokeOne(id: string) {
    setState(await invoke<UiState>("revoke_device", { id }));
  }

  async function removeCapsule(id: string) {
    setState(await invoke<UiState>("remove_capsule", { id }));
    if (selected === id) setSelected(null);
  }

  async function typeCapsule(text: string) {
    try {
      await invoke("type_text", { text });
      setFlash("打到光标了");
    } catch (error) {
      console.error(error);
      setFlash("打不出去，检查辅助功能权限");
    }
  }

  async function copy(text: string, key: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(key);
      window.setTimeout(() => setCopied(null), 1200);
    } catch {
      /* 剪贴板不可用就算了 */
    }
  }

  async function toggleMcpKeys() {
    if (!state) return;
    setState(await invoke<UiState>("set_mcp_keys", { enabled: !state.mcp_keys }));
  }

  if (!state) {
    return <div className="loading">正在启动…</div>;
  }

  const connected = state.connected > 0;
  const onlineName = state.device || "手机";

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <span className="mark" aria-hidden>
            <i style={{ height: 8 }} />
            <i style={{ height: 14 }} />
            <i style={{ height: 20 }} />
            <i style={{ height: 12 }} />
            <i style={{ height: 7 }} />
          </span>
          <b>VibeType</b>
        </div>

        <nav className="nav">
          {NAV.map((item) => (
            <button
              key={item.id}
              className={page === item.id ? "nav-item on" : "nav-item"}
              onClick={() => setPage(item.id)}
            >
              <svg viewBox="0 0 24 24" dangerouslySetInnerHTML={{ __html: item.icon }} />
              {item.label}
            </button>
          ))}
        </nav>

        <div className="sb-foot">
          <div className="row">
            <i className={connected ? "dot on" : "dot"} />
            {connected ? `${onlineName} 已连接` : "等待手机连接"}
          </div>
          <div className="row">
            <i className={state.permission ? "dot ok" : "dot warn"} />
            {state.permission ? "上屏权限已授权" : "缺上屏权限"}
          </div>
          <span className="ver">v0.1.0</span>
        </div>
      </aside>

      <main className="main">
        {page === "overview" && (
          <section className="page">
            <header className="page-head">
              <h1>概览</h1>
              <p>手机连上就能用</p>
            </header>

            <div className="status-grid">
              <div className="status-card">
                <span className="k">手机</span>
                <span className="v">
                  <i className={connected ? "dot ok" : "dot"} />
                  {connected ? onlineName : "未连接"}
                </span>
                <span className="s">
                  {connected ? "已连接 · 文字会打进当前光标" : "打开手机上的 VibeType"}
                </span>
              </div>
              <div className="status-card">
                <span className="k">上屏权限</span>
                <span className="v">
                  <i className={state.permission ? "dot ok" : "dot warn"} />
                  {state.permission ? "辅助功能" : "未授权"}
                </span>
                <span className="s">
                  {state.permission ? "已授权" : "没有它打不进别的 App"}
                </span>
              </div>
              <div className="status-card">
                <span className="k">MCP</span>
                <span className="v">
                  <i className="dot blue" />
                  按需启动
                </span>
                <span className="s">
                  {state.mcp_keys ? "6 个工具 · 含按键" : "5 个工具 · stdio"}
                </span>
              </div>
            </div>

            <div className="card grow">
              <div className="card-head">
                <h2>最近上屏</h2>
                {state.history.length > 0 && (
                  <button className="link" onClick={clearHistory}>
                    清空
                  </button>
                )}
              </div>
              {state.history.length === 0 ? (
                <p className="empty">还没有文字。在手机上按住说话，这里会出现。</p>
              ) : (
                <ul className="list">
                  {state.history.map((entry) => (
                    <li key={entry.id}>
                      <div className="text">{entry.text}</div>
                      <div className="meta">
                        <span>{time(entry.at)}</span>
                        <span>·</span>
                        <span>{modeLabel(entry.mode)}</span>
                        <span className={entry.mode === "failed" ? "tag fail" : "tag ok"}>
                          {entry.mode === "failed" ? "未上屏" : "已上屏"}
                        </span>
                        <button className="link small" onClick={() => copy(entry.text, entry.id)}>
                          {copied === entry.id ? "已复制" : "复制"}
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="quick-row">
              <button className="quick primary" onClick={() => setPage("connect")}>
                显示配对码
              </button>
              <button className="quick" onClick={() => setPage("capsules")}>
                打开胶囊
              </button>
              <button
                className="quick"
                onClick={() => copy(`${state.address}:${state.port}`, "addr")}
              >
                {copied === "addr" ? "地址已复制" : "复制地址"}
              </button>
            </div>
          </section>
        )}

        {page === "connect" && (
          <section className="page">
            <header className="page-head">
              <h1>连接手机</h1>
              <p>扫码，或手动输入六位码</p>
            </header>

            <div className="hero">
              <div className="pair">
                <span className="label">配对码</span>
                <div className="code">{state.code}</div>
                <div className="address">
                  <span>{state.address}</span>
                  <span className="port">:{state.port}</span>
                </div>
                <p className="hint">新设备用这个码，已配对的手机会自动重连</p>
              </div>
              {state.qr_svg ? (
                <div className="qr" dangerouslySetInnerHTML={{ __html: state.qr_svg }} />
              ) : (
                <div className="qr placeholder">没有局域网地址</div>
              )}
            </div>

            <div className="card">
              <div className="step">
                <span className="badge">1</span>
                <div>
                  <b>手机和电脑连同一个 Wi-Fi</b>
                  <span>跨网段时手输 IP，mDNS 不跨路由</span>
                </div>
              </div>
              <div className="step">
                <span className="badge">2</span>
                <div>
                  <b>用手机相机扫上面的码</b>
                  <span>也可以在 App 里手输地址和六位码</span>
                </div>
              </div>
              <div className="step">
                <span className="badge">3</span>
                <div>
                  <b>之后自动重连</b>
                  <span>手机换 Wi-Fi 或电脑重启，都会自己接回来</span>
                </div>
              </div>
            </div>

            <div className="quick-row">
              <button className="quick" onClick={regenerate}>
                换一个配对码
              </button>
              <button
                className="quick"
                onClick={() => copy(`${state.address}:${state.port}`, "addr")}
              >
                {copied === "addr" ? "已复制" : "复制地址"}
              </button>
            </div>
          </section>
        )}

        {page === "devices" && (
          <section className="page">
            <header className="page-head">
              <h1>设备</h1>
              <p>已配对的手机</p>
            </header>

            <div className="card">
              {state.devices.length === 0 ? (
                <p className="empty">还没有配对过的手机。去「连接」页拿配对码。</p>
              ) : (
                state.devices.map((device) => (
                  <div className="device" key={device.id}>
                    <span className={device.online ? "ic on" : "ic"}>
                      <svg viewBox="0 0 24 24">
                        <rect x="7" y="2.5" width="10" height="19" rx="2.5" />
                        <path d="M10.5 19h3" />
                      </svg>
                    </span>
                    <div className="who">
                      <b>{device.name || "未命名设备"}</b>
                      <span>
                        {device.platform === "android" ? "Android" : device.platform || "手机"}
                        {" · "}
                        {device.online ? "已连接" : ago(device.last_seen)}
                      </span>
                    </div>
                    <button className="link danger" onClick={() => revokeOne(device.id)}>
                      移除
                    </button>
                  </div>
                ))
              )}
            </div>

            <p className="hint">移除后，那台手机需要重新扫码或输入配对码。</p>
            <button className={confirmRevoke ? "danger-btn on" : "danger-btn"} onClick={revokeAll}>
              {confirmRevoke ? "再点一次，确认移除全部" : "移除全部设备"}
            </button>
          </section>
        )}

        {page === "capsules" && (
          <section className="page">
            <header className="page-head">
              <h1>胶囊</h1>
              <p>
                手机攒下来的灵感
                {capsules.length > 0 ? `，${capsules.length} 条` : ""}
              </p>
            </header>

            {capsules.length === 0 ? (
              <div className="card grow center">
                <div className="empty-capsule">＋</div>
                <b>还没有胶囊</b>
                <p className="empty">
                  在手机上按住胶囊键说一句话，它就会出现在这里。
                </p>
              </div>
            ) : (
              <div className="inbox">
                <div className="inbox-list">
                  {capsules.map((item) => (
                    <button
                      key={item.id}
                      className={capsule?.id === item.id ? "ientry on" : "ientry"}
                      onClick={() => setSelected(item.id)}
                    >
                      <span className="t">{day(item.at)}</span>
                      <span className="x">{item.text}</span>
                    </button>
                  ))}
                </div>
                {capsule && (
                  <div className="inbox-detail">
                    <span className="t">
                      {day(capsule.at)} · {duration(capsule.duration_ms)}
                    </span>
                    <div className="x">{capsule.text}</div>
                    {flash && <span className="flash">{flash}</span>}
                    <div className="actions">
                      <button className="pill-btn" onClick={() => copy(capsule.text, capsule.id)}>
                        {copied === capsule.id ? "已复制" : "复制"}
                      </button>
                      <button className="pill-btn primary" onClick={() => typeCapsule(capsule.text)}>
                        打到光标
                      </button>
                      <button
                        className="pill-btn danger"
                        onClick={() => removeCapsule(capsule.id)}
                      >
                        删除
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </section>
        )}

        {page === "mcp" && (
          <section className="page">
            <header className="page-head spread">
              <div>
                <h1>MCP</h1>
                <p>让 AI 客户端读你的转写和胶囊</p>
              </div>
              <span className="wpill">
                <i className="dot blue" />
                按需启动 · stdio
              </span>
            </header>

            <div className="card">
              <div className="card-head">
                <div>
                  <h2>接入客户端</h2>
                  <p className="sub">复制这段命令到终端，或在客户端里加一个 stdio server</p>
                </div>
                <button
                  className="link"
                  onClick={() => copy(cliSnippet(state), "snippet")}
                >
                  {copied === "snippet" ? "已复制" : "复制配置"}
                </button>
              </div>
              <div className="tabs">
                {CLIENTS.map((client) => (
                  <span
                    key={client.id}
                    className={copied === client.id ? "tab on" : "tab"}
                    onClick={() => copy(client.path, client.id)}
                  >
                    {client.label}
                  </span>
                ))}
              </div>
              <pre className="codeblock">{jsonSnippet()}</pre>
              <p className="hint">
                客户端会用它自己的权限启动 VibeType，所以上屏前请确认「辅助功能」已授权。
              </p>
            </div>

            <div className="card">
              <div className="card-head">
                <div>
                  <h2>工具</h2>
                  <p className="sub">
                    {state.mcp_keys ? "6 个工具 · 5 个默认开启" : "5 个工具 · 全部开启"}
                  </p>
                </div>
              </div>

              <div className="tool">
                <div>
                  <b>读取最近转写</b>
                  <span>拿到刚说的话，直接给 Agent 当输入</span>
                </div>
                <span className="tog on" />
              </div>
              <div className="tool">
                <div>
                  <b>胶囊读写</b>
                  <span>列出、查看，或让 Agent 新建和删除</span>
                </div>
                <span className="tog on" />
              </div>
              <div className="tool">
                <div>
                  <b>打字到光标</b>
                  <span>把 Agent 的结果打进当前应用</span>
                </div>
                <span className="tog on" />
              </div>
              <div className="tool">
                <div>
                  <b className={state.mcp_keys ? "coral" : ""}>按键控制</b>
                  <span>回车 / 删除 / Esc / 切换窗口，默认关闭</span>
                </div>
                <button
                  className={state.mcp_keys ? "toggle on" : "toggle"}
                  onClick={toggleMcpKeys}
                  aria-label="允许 MCP 按键"
                >
                  <span className="knob" />
                </button>
              </div>
            </div>
          </section>
        )}

        {page === "settings" && (
          <section className="page">
            <header className="page-head">
              <h1>设置</h1>
              <p>上屏方式、启动项与权限</p>
            </header>

            <div className="card">
              <div className="row-item">
                <div>
                  <b>上屏方式</b>
                  <span>直接打字最快，粘贴对中文输入法更稳</span>
                </div>
                <div className="seg">
                  <button
                    className={state.injection === "type" ? "on" : ""}
                    onClick={() => setMode("type")}
                  >
                    直接打字
                  </button>
                  <button
                    className={state.injection === "paste" ? "on" : ""}
                    onClick={() => setMode("paste")}
                  >
                    粘贴
                  </button>
                </div>
              </div>

              <div className="row-item">
                <div>
                  <b>开机自启</b>
                  <span>开机后安静待在托盘里</span>
                </div>
                <button
                  className={autostart ? "toggle on" : "toggle"}
                  onClick={toggleAutostart}
                  aria-label="开机自启"
                >
                  <span className="knob" />
                </button>
              </div>

              {state.platform === "macos" && (
                <div className="row-item">
                  <div>
                    <b className={state.permission ? "" : "coral"}>辅助功能权限</b>
                    <span>
                      {state.permission ? "已授权，可以打进别的 App" : "没授权，文字发不出去"}
                    </span>
                  </div>
                  <button className="quick" onClick={() => invoke("open_permission_settings")}>
                    {state.permission ? "打开系统设置" : "去授权"}
                  </button>
                </div>
              )}

              <div className="row-item">
                <div>
                  <b>配对码</b>
                  <span>每 3 分钟自动换，已配对设备不受影响</span>
                </div>
                <button className="quick" onClick={regenerate}>
                  换一个
                </button>
              </div>
            </div>

            <p className="hint">
              VibeType 0.1.0 · 手机本地转写（X-ASR）· 这里只收文字和胶囊。
            </p>
          </section>
        )}

        {flash && page !== "capsules" && <div className="toast">{flash}</div>}
      </main>
    </div>
  );
}

function jsonSnippet() {
  return `{
  "mcpServers": {
    "vibetype": {
      "command": "/Applications/VibeType.app/Contents/MacOS/vibetype",
      "args": ["--mcp"]
    }
  }
}`;
}

function cliSnippet(state: UiState) {
  return `claude mcp add vibetype -- "$HOME/Applications/VibeType.app/Contents/MacOS/vibetype" --mcp\n# 当前地址 ${state.address}:${state.port}`;
}
