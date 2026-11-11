import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

type Entry = { id: string; text: string; at: number; mode: string };

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
};

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

export default function App() {
  const [state, setState] = useState<UiState | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [autostart, setAutostart] = useState(false);
  const [confirmRevoke, setConfirmRevoke] = useState(false);

  useEffect(() => {
    invoke<UiState>("ui_state").then(setState).catch(console.error);
    invoke<boolean>("autostart_enabled").then(setAutostart).catch(() => {});
    const unlisten = listen<UiState>("state", (event) => setState(event.payload));
    return () => {
      unlisten.then((fn) => fn()).catch(() => {});
    };
  }, []);

  async function toggleAutostart() {
    try {
      setAutostart(await invoke<boolean>("set_autostart", { enabled: !autostart }));
    } catch (error) {
      console.error(error);
    }
  }

  async function revokeDevices() {
    if (!confirmRevoke) {
      setConfirmRevoke(true);
      window.setTimeout(() => setConfirmRevoke(false), 3000);
      return;
    }
    setConfirmRevoke(false);
    setState(await invoke<UiState>("revoke_devices"));
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

  async function copy(text: string, id: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(id);
      window.setTimeout(() => setCopied(null), 1200);
    } catch {
      /* 剪贴板不可用就算了 */
    }
  }

  if (!state) {
    return <div className="loading">正在启动…</div>;
  }

  return (
    <div className="shell">
      <header className="top">
        <div className="brand">
          <div className="mark" aria-hidden>
            <span style={{ height: 10 }} />
            <span style={{ height: 18 }} />
            <span style={{ height: 26 }} />
            <span style={{ height: 16 }} />
            <span style={{ height: 9 }} />
          </div>
          <div>
            <h1>VibeType</h1>
            <p>手机说话，电脑上屏</p>
          </div>
        </div>
        <div className={state.connected > 0 ? "pill on" : "pill"}>
          <i />
          <span className="pill-text">
            {state.connected > 0
              ? state.device
                ? `${state.device} 已连接`
                : "手机已连接"
              : "等待连接"}
          </span>
        </div>
      </header>

      {state.platform === "macos" && !state.permission && (
        <div className="warn">
          <div>
            <strong>需要「辅助功能」权限</strong>
            <p>没有它，VibeType 没法把文字打进别的 App。</p>
          </div>
          <button onClick={() => invoke("open_permission_settings")}>去授权</button>
        </div>
      )}

      <section className="hero">
        <div className="pair">
          <span className="label">配对码</span>
          <div className="code">{state.code}</div>
          <div className="address">
            <span>{state.address}</span>
            <span className="port">:{state.port}</span>
          </div>
          <p className="hint">在手机上填这个地址和六位数字</p>
        </div>
        {state.qr_svg ? (
          <div className="qr" dangerouslySetInnerHTML={{ __html: state.qr_svg }} />
        ) : (
          <div className="qr placeholder">没有局域网地址</div>
        )}
      </section>

      <section className="card">
        <div className="card-head">
          <h2>最近上屏</h2>
          {state.history.length > 0 && (
            <button className="ghost" onClick={clearHistory}>
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
                  <button
                    className="ghost small"
                    onClick={() => copy(entry.text, entry.id)}
                  >
                    {copied === entry.id ? "已复制" : "复制"}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <div className="settings">
        <button
          className={autostart ? "toggle on" : "toggle"}
          onClick={toggleAutostart}
          aria-pressed={autostart}
          aria-label="开机自启"
        >
          <span className="knob" />
        </button>
        <span className="setting-label">开机自启</span>
        <span className="spacer" />
        <button
          className={confirmRevoke ? "ghost danger" : "ghost"}
          onClick={revokeDevices}
        >
          {confirmRevoke ? "再点一次确认移除" : "移除已配对手机"}
        </button>
      </div>

      <footer>
        <div className="seg" role="group" aria-label="上屏方式">
          <button
            className={state.injection === "type" ? "active" : ""}
            onClick={() => setMode("type")}
          >
            直接打字
          </button>
          <button
            className={state.injection === "paste" ? "active" : ""}
            onClick={() => setMode("paste")}
          >
            粘贴
          </button>
        </div>
        <button className="ghost" onClick={regenerate}>
          换配对码
        </button>
      </footer>
    </div>
  );
}
