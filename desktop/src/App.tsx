import { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { ago, clock, count, day, getLang, setLang, t, useLang } from "./i18n";
import { getTheme, setTheme, useTheme } from "./theme";

type Entry = { id: string; text: string; at: number; mode: string };
type Device = {
  id: string;
  name: string;
  platform: string;
  last_seen: number;
  online: boolean;
};
type KeySlot = {
  id: string;
  label: string;
  action: string;
  key: string;
  modifiers: string[];
  target: string;
};
type AppEntry = { name: string; path: string };
type Capsule = {
  id: string;
  text: string;
  at: number;
  duration_ms: number;
  status?: string;
  used_at?: number;
};

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
  keymap: KeySlot[];
};

type Page = "overview" | "connect" | "devices" | "capsules" | "mcp" | "settings";

const NAV: { id: Page; label: string; icon: string }[] = [
  {
    id: "overview",
    label: t("概览", "Overview"),
    icon: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  },
  {
    id: "connect",
    label: t("连接", "Connect"),
    icon: '<path d="M9.5 14.5 14.5 9.5"/><path d="M8 12l-2.5 2.5a3.5 3.5 0 0 0 5 5L13 17"/><path d="M16 12l2.5-2.5a3.5 3.5 0 0 0-5-5L11 7"/>',
  },
  {
    id: "devices",
    label: t("设备", "Devices"),
    icon: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M10.5 19h3"/>',
  },
  {
    id: "capsules",
    label: t("胶囊", "Capsules"),
    icon: '<rect x="9" y="3.5" width="6" height="17" rx="3" fill="currentColor" fill-opacity="0.16"/><rect x="9" y="3.5" width="6" height="17" rx="3"/><path d="M9 12h6"/><circle cx="10.7" cy="7.2" r="0.9" fill="currentColor" stroke="none"/>',
  },
  {
    id: "mcp",
    label: "MCP",
    icon: '<path d="M8 8 4 12l4 4M16 8l4 4-4 4M14 5l-4 14"/>',
  },
  {
    id: "settings",
    label: t("设置", "Settings"),
    icon: '<path d="M4 7h9M18 7h2M4 17h2M11 17h9"/><circle cx="15.5" cy="7" r="2"/><circle cx="8.5" cy="17" r="2"/>',
  },
];

type McpClient = {
  id: string;
  label: string;
  how: string;
  cli?: string;
  path: string;
  snippet: string;
};

function binaryPath(platform: string) {
  return platform === "windows"
    ? "C:/Program Files/VibeType/VibeType.exe"
    : "/Applications/VibeType.app/Contents/MacOS/vibetype";
}

function mcpClients(bin: string): McpClient[] {
  return [
    {
      id: "claude",
      label: "Claude Code",
      how: t("一条命令注册，或用 ~/.claude.json 里同样的 mcpServers 条目", "One command, or the same mcpServers entry in ~/.claude.json"),
      cli: `claude mcp add --transport stdio vibetype -- "${bin}" --mcp`,
      path: t("~/.claude.json（项目级 .mcp.json）", "~/.claude.json (project: .mcp.json)"),
      snippet: `{
  "mcpServers": {
    "vibetype": {
      "command": "${bin}",
      "args": ["--mcp"]
    }
  }
}`,
    },
    {
      id: "codex",
      label: "Codex",
      how: t("codex mcp add 写进 config.toml 的 [mcp_servers]", "`codex mcp add` writes [mcp_servers] in config.toml"),
      cli: `codex mcp add vibetype -- "${bin}" --mcp`,
      path: "~/.codex/config.toml",
      snippet: `[mcp_servers.vibetype]
command = "${bin}"
args = ["--mcp"]
enabled = true`,
    },
    {
      id: "opencode",
      label: "OpenCode",
      how: t("本地服务器用 type=local，命令是数组", "Local servers use type=local with an array command"),
      cli: `opencode mcp add vibetype -- "${bin}" --mcp --global`,
      path: "~/.config/opencode/opencode.jsonc",
      snippet: `{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "vibetype": {
        "type": "local",
        "command": ["${bin}", "--mcp"],
        "disabled": false
      }
    }
  }
}`,
    },
    {
      id: "zcode",
      label: "ZCode",
      how: t("在设置 → MCP 里加一条 stdio server，或直接写配置", "Add a stdio server in Settings → MCP, or edit the config"),
      path: "~/.zcode/cli/config.json",
      snippet: `{
  "mcp": {
    "servers": {
      "vibetype": {
        "command": "${bin}",
        "args": ["--mcp"]
      }
    }
  }
}`,
    },
    {
      id: "qwen",
      label: "Qwen Code",
      how: t("和 Gemini CLI 同源，settings.json 里是 mcpServers", "Same format as Gemini CLI: mcpServers in settings.json"),
      cli: `qwen mcp add vibetype "${bin}" --mcp`,
      path: "~/.qwen/settings.json",
      snippet: `{
  "mcpServers": {
    "vibetype": {
      "command": "${bin}",
      "args": ["--mcp"]
    }
  }
}`,
    },
    {
      id: "dsh",
      label: "DeepSeek Harness",
      how: t("dsh 用 Cordis overlay 声明 MCP，工具会显示成 mcp__vibetype__*", "dsh declares MCP via a Cordis overlay; tools show as mcp__vibetype__*"),
      cli: `dsh web --patch ~/.dsh/vibetype.cordis.yml`,
      path: t("$DSH_HOME/cordis.patch.yml（默认 ~/.dsh）", "$DSH_HOME/cordis.patch.yml (default ~/.dsh)"),
      snippet: `- insert:
    - id: vibetype-mcp
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: vibetype
        transport: stdio
        command: "${bin}"
        args: ["--mcp"]
        env: {}`,
    },
    {
      id: "workbuddy",
      label: "WorkBuddy",
      how: t("mcp.json 里加一条 stdio，和 Claude 的格式基本一致", "Add a stdio entry in mcp.json; same shape as Claude"),
      path: "~/.workbuddy/mcp.json",
      snippet: `{
  "mcpServers": {
    "vibetype": {
      "type": "stdio",
      "command": "${bin}",
      "args": ["--mcp"],
      "disabled": false
    }
  }
}`,
    },
  ];
}

function modeLabel(mode: string) {
  if (mode === "type") return t("直接打字", "Typed");
  if (mode === "paste") return t("粘贴上屏", "Pasted");
  return t("没有上屏", "Not sent");
}

function duration(ms: number) {
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

export default function App() {
  useLang();
  const themeMode = useTheme();
  const [state, setState] = useState<UiState | null>(null);
  const [page, setPage] = useState<Page>("overview");
  const [autostart, setAutostart] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [clientId, setClientId] = useState("claude");
  const [keyDraft, setKeyDraft] = useState<KeySlot[]>([]);
  const [keyDirty, setKeyDirty] = useState(false);
  const [appList, setAppList] = useState<AppEntry[]>([]);

  useEffect(() => {
    invoke<UiState>("ui_state").then(setState).catch(console.error);
    invoke<boolean>("autostart_enabled").then(setAutostart).catch(() => {});
    invoke<AppEntry[]>("list_apps").then(setAppList).catch(() => {});
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

  useEffect(() => {
    if (!keyDirty) setKeyDraft(state?.keymap ?? []);
  }, [state?.keymap, keyDirty]);

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
      setFlash(t("打到光标了", "Typed at the cursor"));
    } catch (error) {
      console.error(error);
      setFlash(t("打不出去，检查辅助功能权限", "Couldn't type. Check Accessibility permission."));
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

  function patchKey(index: number, patch: Partial<KeySlot>) {
    setKeyDraft((prev) =>
      prev.map((slot, i) => (i === index ? { ...slot, ...patch } : slot))
    );
    setKeyDirty(true);
  }

  function toggleModifier(index: number, modifier: string) {
    setKeyDraft((prev) =>
      prev.map((slot, i) => {
        if (i !== index) return slot;
        const has = slot.modifiers.includes(modifier);
        return {
          ...slot,
          modifiers: has
            ? slot.modifiers.filter((item) => item !== modifier)
            : [...slot.modifiers, modifier],
        };
      })
    );
    setKeyDirty(true);
  }

  async function saveKeymap() {
    try {
      setState(await invoke<UiState>("set_keymap", { slots: keyDraft }));
      setKeyDirty(false);
      setFlash(t("键位已保存，手机同步更新", "Keys saved and pushed to the phone"));
    } catch (error) {
      console.error(error);
      setFlash(t("保存失败：标签 1–8 个字，按键要在列表里", "Save failed: labels need 1–8 characters and keys must be in the list"));
    }
  }

  async function resetKeymap() {
    setState(await invoke<UiState>("reset_keymap"));
    setKeyDirty(false);
    setFlash(t("已恢复默认键位", "Default keys restored"));
  }

  async function installSkill(target: string, label: string) {
    try {
      const path = await invoke<string>("install_skill", { target });
      setFlash(t(`已装到 ${label}：${path}`, `Installed for ${label}: ${path}`));
    } catch (error) {
      console.error(error);
      setFlash(t("装不进去，检查目录权限", "Install failed. Check folder permissions."));
    }
  }

  async function toggleMcpKeys() {
    if (!state) return;
    setState(await invoke<UiState>("set_mcp_keys", { enabled: !state.mcp_keys }));
  }

  if (!state) {
    return <div className="loading">{t("正在启动…", "Starting…")}</div>;
  }

  const connected = state.connected > 0;
  const onlineName = state.device || t("手机", "Phone");
  const clients = mcpClients(binaryPath(state.platform));
  const client = clients.find((item) => item.id === clientId) ?? clients[0];

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
            <span className="txt">
              {connected ? `${onlineName} 已连接` : "等待手机连接"}
            </span>
          </div>
          <div className="row">
            <i className={state.permission ? "dot ok" : "dot warn"} />
            <span className="txt">
              {state.permission ? t("上屏权限已授权", "Typing permission granted") : t("缺上屏权限", "Typing permission missing")}
            </span>
          </div>
          <span className="ver">v0.1.0</span>
        </div>
      </aside>

      <main className="main">
        {page === "overview" && (
          <section className="page">
            <header className="page-head">
              <h1>{t("概览", "Overview")}</h1>
              <p>{t("手机连上就能用", "Connect your phone to start")}</p>
            </header>

            <div className="status-grid">
              <div className="status-card">
                <span className="k">{t("手机", "Phone")}</span>
                <span className="v">
                  <i className={connected ? "dot ok" : "dot"} />
                  {connected ? onlineName : t("未连接", "Not connected")}
                </span>
                <span className="s">
                  {connected ? t("已连接 · 文字会打进当前光标", "Connected · Text goes to the cursor") : t("打开手机上的 VibeType", "Open VibeType on your phone")}
                </span>
              </div>
              <div className="status-card">
                <span className="k">{t("上屏权限", "Typing permission")}</span>
                <span className="v">
                  <i className={state.permission ? "dot ok" : "dot warn"} />
                  {state.permission ? t("辅助功能", "Accessibility") : t("未授权", "Not granted")}
                </span>
                <span className="s">
                  {state.permission ? t("已授权", "Granted") : t("没有它打不进别的 App", "Required to type into other apps")}
                </span>
              </div>
              <div className="status-card">
                <span className="k">MCP</span>
                <span className="v">
                  <i className="dot blue" />{t("按需启动", "Runs on demand")}</span>
                <span className="s">
                  {state.mcp_keys ? t("7 个工具 · 含按键", "7 tools · keys on") : t("6 个工具 · 胶囊优先", "6 tools · capsules first")}
                </span>
              </div>
            </div>

            <div className="card grow">
              <div className="card-head">
                <h2>{t("最近上屏", "Recent typing")}</h2>
                {state.history.length > 0 && (
                  <button className="link" onClick={clearHistory}>
                    {t("清空", "Clear")}
                  </button>
                )}
              </div>
              {state.history.length === 0 ? (
                <p className="empty">{t("还没有文字。在手机上按住说话，这里会出现。", "Nothing yet. Hold to talk on your phone and it will show up here.")}</p>
              ) : (
                <ul className="list">
                  {state.history.map((entry) => (
                    <li key={entry.id}>
                      <div className="text">{entry.text}</div>
                      <div className="meta">
                        <span>{clock(entry.at)}</span>
                        <span>·</span>
                        <span>{modeLabel(entry.mode)}</span>
                        <span className={entry.mode === "failed" ? "tag fail" : "tag ok"}>
                          {entry.mode === "failed" ? t("未上屏", "Not sent") : t("已上屏", "Typed")}
                        </span>
                        <button className="link small" onClick={() => copy(entry.text, entry.id)}>
                          {copied === entry.id ? t("已复制", "Copied") : t("复制", "Copy")}
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="quick-row">
              <button className="quick primary" onClick={() => setPage("connect")}>
                {t("显示配对码", "Show pairing code")}
              </button>
              <button className="quick" onClick={() => setPage("capsules")}>
                {t("打开胶囊", "Open capsules")}
              </button>
              <button
                className="quick"
                onClick={() => copy(`${state.address}:${state.port}`, "addr")}
              >
                {copied === "addr" ? t("地址已复制", "Address copied") : t("复制地址", "Copy address")}
              </button>
            </div>
          </section>
        )}

        {page === "connect" && (
          <section className="page">
            <header className="page-head">
              <h1>{t("连接手机", "Connect phone")}</h1>
              <p>{t("扫码，或手动输入六位码", "Scan the code, or enter the 6-digit code")}</p>
            </header>

            <div className="hero">
              <div className="pair">
                <span className="label">{t("配对码", "Pairing code")}</span>
                <div className="code">{state.code}</div>
                <div className="address">
                  <span>{state.address}</span>
                  <span className="port">:{state.port}</span>
                </div>
                <p className="hint">{t("新设备用这个码，已配对的手机会自动重连", "Use this for a new device. Paired phones reconnect automatically.")}</p>
              </div>
              {state.qr_svg ? (
                <div className="qr" dangerouslySetInnerHTML={{ __html: state.qr_svg }} />
              ) : (
                <div className="qr placeholder">{t("没有局域网地址", "No LAN address")}</div>
              )}
            </div>

            <div className="card">
              <div className="step">
                <span className="badge">1</span>
                <div>
                  <b>{t("手机和电脑连同一个 Wi-Fi", "Phone and computer on the same Wi-Fi")}</b>
                  <span>{t("跨网段时手输 IP，mDNS 不跨路由", "Across subnets, enter the IP manually; mDNS doesn't cross routers")}</span>
                </div>
              </div>
              <div className="step">
                <span className="badge">2</span>
                <div>
                  <b>{t("用手机相机扫上面的码", "Scan the code with your phone camera")}</b>
                  <span>{t("也可以在 App 里手输地址和六位码", "Or enter the address and 6-digit code in the app")}</span>
                </div>
              </div>
              <div className="step">
                <span className="badge">3</span>
                <div>
                  <b>{t("之后自动重连", "Reconnects automatically")}</b>
                  <span>{t("手机换 Wi-Fi 或电脑重启，都会自己接回来", "Survives Wi-Fi changes and restarts")}</span>
                </div>
              </div>
            </div>

            <div className="quick-row">
              <button className="quick" onClick={regenerate}>
                {t("换一个配对码", "New code")}
              </button>
              <button
                className="quick"
                onClick={() => copy(`${state.address}:${state.port}`, "addr")}
              >
                {copied === "addr" ? t("已复制", "Copied") : t("复制地址", "Copy address")}
              </button>
            </div>
          </section>
        )}

        {page === "devices" && (
          <section className="page">
            <header className="page-head">
              <h1>{t("设备", "Devices")}</h1>
              <p>{t("已配对的手机", "Paired phones")}</p>
            </header>

            <div className="card">
              {state.devices.length === 0 ? (
                <p className="empty">{t("还没有配对过的手机。去「连接」页拿配对码。", "No paired phones yet. Get a code on the Connect page.")}</p>
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
                      <b>{device.name || t("未命名设备", "Unnamed device")}</b>
                      <span>
                        {device.platform === "android" ? "Android" : device.platform || t("手机", "Phone")}
                        {" · "}
                        {device.online ? t("已连接", "Connected") : ago(device.last_seen)}
                      </span>
                    </div>
                    <button className="link danger" onClick={() => revokeOne(device.id)}>
                      {t("移除", "Remove")}
                    </button>
                  </div>
                ))
              )}
            </div>

            <p className="hint">{t("移除后，那台手机需要重新扫码或输入配对码。", "That phone will need to scan or enter a code again.")}</p>
            <button className={confirmRevoke ? "danger-btn on" : "danger-btn"} onClick={revokeAll}>
              {confirmRevoke ? t("再点一次，确认移除全部", "Tap again to remove all") : t("移除全部设备", "Remove all devices")}
            </button>
          </section>
        )}

        {page === "capsules" && (
          <section className="page">
            <header className="page-head">
              <h1>{t("胶囊", "Capsules")}</h1>
              <p>
                {t("手机攒下来的灵感", "Thoughts collected on your phone")}
                {capsules.length > 0 ? (getLang() === "en" ? `, ${capsules.length} total` : `，${capsules.length} 条`) : ""}
              </p>
            </header>

            {capsules.length === 0 ? (
              <div className="card grow center">
                <div className="empty-capsule">＋</div>
                <b>{t("还没有胶囊", "No capsules yet")}</b>
                <p className="empty">
                  {t("在手机上按住胶囊键说一句话，它就会出现在这里。", "Hold the Capsule key on your phone and speak; it will show up here.")}
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
                      <span className="head">
                        <span className="t">{day(item.at)}</span>
                        <span className={item.status === "used" ? "tag ok" : "tag wait"}>
                          {item.status === "used" ? t("已整理", "Processed") : t("未整理", "New")}
                        </span>
                      </span>
                      <span className="x">{item.text}</span>
                    </button>
                  ))}
                </div>
                {capsule && (
                  <div className="inbox-detail">
                    <span className="t">
                      {day(capsule.at)} · {duration(capsule.duration_ms)} ·{" "}
                      {capsule.status === "used" ? t("已整理", "Processed") : t("未整理", "New")}
                    </span>
                    <div className="x">{capsule.text}</div>
                    {flash && <span className="flash">{flash}</span>}
                    <div className="actions">
                      <button className="pill-btn" onClick={() => copy(capsule.text, capsule.id)}>
                        {copied === capsule.id ? t("已复制", "Copied") : t("复制", "Copy")}
                      </button>
                      <button className="pill-btn primary" onClick={() => typeCapsule(capsule.text)}>{t("打到光标", "Type at cursor")}</button>
                      <button
                        className="pill-btn danger"
                        onClick={() => removeCapsule(capsule.id)}
                      >{t("删除", "Delete")}</button>
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
                <p>{t("让 Agent 帮你消化闪念胶囊", "Let an agent digest your capsules")}</p>
              </div>
              <span className="wpill">
                <i className="dot blue" />
                {t("按需启动 · stdio", "Runs on demand · stdio")}
              </span>
            </header>

            <div className="card">
              <div className="card-head">
                <div>
                  <h2>{t("接入客户端", "Connect a client")}</h2>
                  <p className="sub">{t("选一个客户端，照着命令或配置加一次", "Pick a client and add it once with the command or config")}</p>
                </div>
              </div>

              <div className="client-grid">
                {clients.map((item) => (
                  <button
                    key={item.id}
                    className={client.id === item.id ? "client on" : "client"}
                    onClick={() => setClientId(item.id)}
                  >
                    {item.label}
                  </button>
                ))}
              </div>

              <p className="how">{client.how}</p>

              {client.cli && (
                <div className="cmd">
                  <code>{client.cli}</code>
                  <button
                    className="link"
                    onClick={() => copy(client.cli!, `${client.id}-cli`)}
                  >
                    {copied === `${client.id}-cli` ? t("已复制", "Copied") : t("复制命令", "Copy command")}
                  </button>
                </div>
              )}

              <div className="path-row">
                <span className="k">{t("配置文件", "Config file")}</span>
                <span className="v">{client.path}</span>
                <button
                  className="link"
                  onClick={() => copy(client.snippet, `${client.id}-snip`)}
                >
                  {copied === `${client.id}-snip` ? t("已复制", "Copied") : t("复制配置", "Copy config")}
                </button>
              </div>

              <pre className="codeblock">{client.snippet}</pre>

              <p className="hint">
                {t("客户端用自己的权限启动 VibeType，上屏前请确认「辅助功能」已授权；工具名形如 ", "The client starts VibeType with its own permissions. Make sure Accessibility is granted before typing. Tool names look like ")}
                <code>mcp__vibetype__capsules_list</code>{t("。", ".")}
              </p>
            </div>

            <div className="card">
              <div className="card-head">
                <div>
                  <h2>{t("配套 Skill", "Companion Skill")}</h2>
                  <p className="sub">
                    {t("告诉 Agent 胶囊是什么、为什么这样设计、该按什么顺序消化", "Tells the agent what capsules are, why they work this way, and how to process them")}
                  </p>
                </div>
              </div>
              <p className="how">
                {t("不装也能用：MCP 握手时会带一段说明，", "Optional: the MCP handshake includes a short brief and ")}
                <code>vibetype://skill</code>
                {t(" 这个资源里是同一份全文。装成 Skill 后，客户端会在合适的时机自己想起来。", " holds the full text. Installed as a Skill, clients recall it at the right moment.")}
              </p>
              <div className="skill-row">
                <button className="quick" onClick={() => installSkill("claude", "Claude Code")}>
                  {t("装到 Claude Code", "Install to Claude Code")}
                </button>
                <button className="quick" onClick={() => installSkill("zcode", "ZCode")}>
                  {t("装到 ZCode", "Install to ZCode")}
                </button>
                <button className="quick" onClick={() => installSkill("opencode", "OpenCode")}>
                  {t("装到 OpenCode", "Install to OpenCode")}
                </button>
                <button className="quick" onClick={() => installSkill("codex", "Codex")}>
                  {t("装到 Codex", "Install to Codex")}
                </button>
              </div>
              <button
                className="cmd"
                onClick={() => copy(skillPrompt, "skill-prompt")}
                style={{ width: "100%" }}
              >
                <code>{skillPrompt}</code>
                <span className="link">{copied === "skill-prompt" ? t("已复制", "Copied") : t("复制提示词", "Copy prompt")}</span>
              </button>
            </div>

            <div className="card">
              <div className="card-head">
                <div>
                  <h2>{t("工具", "Tools")}</h2>
                  <p className="sub">
                    {state.mcp_keys ? t("7 个工具 · 含按键", "7 tools · keys on") : t("6 个工具 · 以胶囊为中心", "6 tools · capsules first")}
                  </p>
                </div>
              </div>

              <div className="tool">
                <div>
                  <b>{t("列出胶囊", "List capsules")}</b>
                  <span>{t("默认只给还没消化的，按时间新到旧", "Newest first; only unprocessed by default")}</span>
                </div>
                <span className="tog on" />
              </div>
              <div className="tool">
                <div>
                  <b>{t("读取 / 标记", "Read / mark")}</b>
                  <span>{t("看全文；用户确认后标成已整理，收件箱才会空", "Read the full text; mark processed after the user confirms")}</span>
                </div>
                <span className="tog on" />
              </div>
              <div className="tool">
                <div>
                  <b>{t("新建胶囊", "Create capsule")}</b>
                  <span>{t("用户说「记一下」时才写，不自动囤", "Only when the user says 'note this'; never stockpile")}</span>
                </div>
                <span className="tog on" />
              </div>
              <div className="tool">
                <div>
                  <b>{t("打字到光标", "Type at cursor")}</b>
                  <span>{t("把整理结果放回用户正在写的地方", "Put the result back where the user is writing")}</span>
                </div>
                <span className="tog on" />
              </div>
              <div className="tool">
                <div>
                  <b className={state.mcp_keys ? "coral" : ""}>{t("按键控制", "Key control")}</b>
                  <span>{t("回车 / 删除 / Esc / 切换窗口，默认关闭", "Enter / Delete / Esc / switch windows; off by default")}</span>
                </div>
                <button
                  className={state.mcp_keys ? "toggle on" : "toggle"}
                  onClick={toggleMcpKeys}
                  aria-label={t("允许 MCP 按键", "Allow MCP keys")}
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
              <h1>{t("设置", "Settings")}</h1>
              <p>{t("上屏方式、启动项与权限", "Typing, startup and permissions")}</p>
            </header>

            <div className="settings-grid">
            <div className="card">
              <div className="row-item">
                <div>
                  <b>{t("上屏方式", "Typing method")}</b>
                  <span>{t("直接打字最快，粘贴对中文输入法更稳", "Typing is fastest; paste is safer with Chinese IMEs")}</span>
                </div>
                <div className="seg">
                  <button
                    className={state.injection === "type" ? "on" : ""}
                    onClick={() => setMode("type")}
                  >{t("直接打字", "Type")}</button>
                  <button
                    className={state.injection === "paste" ? "on" : ""}
                    onClick={() => setMode("paste")}
                  >
                    {t("粘贴", "Paste")}
                  </button>
                </div>
              </div>

              <div className="row-item">
                <div>
                  <b>{t("开机自启", "Launch at login")}</b>
                  <span>{t("开机后安静待在托盘里", "Stay quietly in the tray")}</span>
                </div>
                <button
                  className={autostart ? "toggle on" : "toggle"}
                  onClick={toggleAutostart}
                  aria-label={t("开机自启", "Launch at login")}
                >
                  <span className="knob" />
                </button>
              </div>

              {state.platform === "macos" && (
                <div className="row-item">
                  <div>
                    <b className={state.permission ? "" : "coral"}>{t("辅助功能权限", "Accessibility permission")}</b>
                    <span>
                      {state.permission ? t("已授权，可以打进别的 App", "Granted; can type into other apps") : t("没授权，文字发不出去", "Not granted; text can't be sent")}
                    </span>
                  </div>
                  <button className="quick" onClick={() => invoke("open_permission_settings")}>
                    {state.permission ? t("打开系统设置", "Open System Settings") : t("去授权", "Grant")}
                  </button>
                </div>
              )}

              <div className="row-item">
                <div>
                  <b>{t("配对码", "Pairing code")}</b>
                  <span>{t("每 3 分钟自动换，已配对设备不受影响", "Rotates every 3 minutes; paired devices keep working")}</span>
                </div>
                <button className="quick" onClick={regenerate}>
                  {t("换一个", "New code")}
                </button>
              </div>
            </div>

            <div className="card">
              <div className="card-head">
                <div>
                  <h2>{t("手机按键", "Phone keys")}</h2>
                  <p className="sub">
                    {t("手机只发动作，这里决定按什么键；保存后立即推到手机", "The phone sends actions; choose the key here. Saving pushes it immediately.")}
                  </p>
                </div>
                <button className="link" onClick={resetKeymap}>
                  {t("恢复默认", "Reset")}
                </button>
              </div>

              <div className="keys-editor">
                {keyDraft.map((slot, index) => (
                  <div className="key-row" key={slot.id}>
                    <input
                      className="key-label"
                      value={slot.label}
                      maxLength={12}
                      onChange={(event) => patchKey(index, { label: event.target.value })}
                    />
                    <select
                      className="key-kind"
                      value={slot.action}
                      onChange={(event) =>
                        patchKey(index, { action: event.target.value })
                      }
                    >
                      <option value="key">{t("按键", "Key")}</option>
                      <option value="app">{t("应用", "App")}</option>
                    </select>

                    {slot.action === "app" ? (
                      <input
                        className="key-app"
                        list="vibetype-apps"
                        placeholder={t("选一个应用，或输入名字", "Pick an app or type a name")}
                        value={slot.target}
                        onChange={(event) =>
                          patchKey(index, { target: event.target.value })
                        }
                      />
                    ) : (
                      <>
                        <select
                          className="key-select"
                          value={slot.key}
                          onChange={(event) =>
                            patchKey(index, { key: event.target.value })
                          }
                        >
                          {KEY_OPTIONS.map(([value, label]) => (
                            <option key={value} value={value}>
                              {label}
                            </option>
                          ))}
                        </select>
                        <div className="mods">
                          {modifiersFor(state.platform).map(([id, symbol]) => (
                            <button
                              key={id}
                              className={slot.modifiers.includes(id) ? "mod on" : "mod"}
                              onClick={() => toggleModifier(index, id)}
                            >
                              {symbol}
                            </button>
                          ))}
                        </div>
                      </>
                    )}
                  </div>
                ))}
              </div>

              <datalist id="vibetype-apps">
                {appList.map((app) => (
                  <option key={app.path} value={app.name} />
                ))}
              </datalist>

              <button
                className={keyDirty ? "quick primary save-keys" : "quick save-keys"}
                disabled={!keyDirty}
                onClick={saveKeymap}
              >
                {keyDirty ? t("保存键位", "Save keys") : t("键位已保存", "Keys saved")}
              </button>
              <p className="hint">
                {t("「切换」默认 ", "Switch defaults to ")}
                {state.platform === "windows" ? "Alt+Tab" : "⌘Tab"}
                {t("，「自定义」默认 ", ". Custom defaults to ")}
                {state.platform === "windows" ? "Win+Tab" : "⌃↑"}
                {t("。选「应用」后，没开就启动、开着就切到最前；手机上显示的是标签。", ". With App, launches it if closed and brings it forward if open; the phone shows the label.")}
              </p>
            </div>
            </div>

            <div className="card">
              <div className="row-item">
                <div>
                  <b>{t("语言", "Language")}</b>
                  <span>{t("界面文字，立即生效", "Interface language, applied immediately")}</span>
                </div>
                <div className="seg">
                  <button
                    className={getLang() === "zh" ? "on" : ""}
                    onClick={() => setLang("zh")}
                  >
                    中文
                  </button>
                  <button
                    className={getLang() === "en" ? "on" : ""}
                    onClick={() => setLang("en")}
                  >
                    English
                  </button>
                </div>
              </div>
              <div className="row-item">
                <div>
                  <b>{t("外观", "Appearance")}</b>
                  <span>{t("跟随系统，或手动指定", "Follow the system, or pick one")}</span>
                </div>
                <div className="seg">
                  <button className={themeMode === "auto" ? "on" : ""} onClick={() => setTheme("auto")}>
                    {t("自动", "Auto")}
                  </button>
                  <button className={themeMode === "light" ? "on" : ""} onClick={() => setTheme("light")}>
                    {t("浅色", "Light")}
                  </button>
                  <button className={themeMode === "dark" ? "on" : ""} onClick={() => setTheme("dark")}>
                    {t("深色", "Dark")}
                  </button>
                </div>
              </div>
            </div>

            <p className="hint">
              {t("VibeType 0.1.0 · 手机本地转写（X-ASR）· 这里只收文字和胶囊。", "VibeType 0.1.0 · On-device transcription (X-ASR) · Only text and capsules arrive here.")}
            </p>
          </section>
        )}

        {flash && page !== "capsules" && <div className="toast">{flash}</div>}
      </main>
    </div>
  );
}

const KEY_OPTIONS: [string, string][] = [
  ["enter", t("回车 Enter", "Enter")],
  ["tab", "Tab"],
  ["space", t("空格", "Space")],
  ["escape", "Esc"],
  ["backspace", t("退格 Backspace", "Backspace")],
  ["delete", t("向前删除 Delete", "Delete forward")],
  ["up", t("↑ 上", "↑ Up")],
  ["down", t("↓ 下", "↓ Down")],
  ["left", t("← 左", "← Left")],
  ["right", t("→ 右", "→ Right")],
  ["pageup", "PageUp"],
  ["pagedown", "PageDown"],
  ["home", "Home"],
  ["end", "End"],
];

function modifiersFor(platform: string): [string, string][] {
  return platform === "windows"
    ? [
        ["ctrl", "Ctrl"],
        ["alt", "Alt"],
        ["shift", "Shift"],
        ["meta", "Win"],
      ]
    : [
        ["cmd", "⌘"],
        ["ctrl", "⌃"],
        ["alt", "⌥"],
        ["shift", "⇧"],
      ];
}

const skillPrompt =
  t("用 vibetype 的 MCP 帮我整理闪念胶囊：先列 status=new 的，按主题归纳，给我最多三个下一步；我确认后你再标成已整理。", "Use VibeType's MCP to sort my thought capsules: list status=new, group by theme, and give me at most three next steps. Only mark them processed after I confirm.");
