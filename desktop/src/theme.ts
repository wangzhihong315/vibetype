import { useEffect, useState } from "react";

export type ThemeMode = "auto" | "light" | "dark";

const KEY = "vibetype.theme";
const listeners = new Set<() => void>();
const query = window.matchMedia("(prefers-color-scheme: dark)");

function read(): ThemeMode {
  const saved = localStorage.getItem(KEY);
  return saved === "light" || saved === "dark" ? saved : "auto";
}

let current: ThemeMode = read();

function darkFor(mode: ThemeMode): boolean {
  return mode === "dark" || (mode === "auto" && query.matches);
}

function apply() {
  const dark = darkFor(current);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  document.documentElement.style.colorScheme = dark ? "dark" : "light";
}

apply();

export function getTheme(): ThemeMode {
  return current;
}

export function setTheme(mode: ThemeMode) {
  current = mode;
  localStorage.setItem(KEY, mode);
  apply();
  listeners.forEach((fn) => fn());
}

/** 订阅外观变化；App 根部调用一次，系统切换深色时整页跟着变。 */
export function useTheme(): ThemeMode {
  const [mode, setMode] = useState<ThemeMode>(current);
  useEffect(() => {
    const notify = () => setMode(current);
    listeners.add(notify);
    const onSystem = () => {
      if (current === "auto") {
        apply();
        notify();
      }
    };
    query.addEventListener("change", onSystem);
    return () => {
      listeners.delete(notify);
      query.removeEventListener("change", onSystem);
    };
  }, []);
  return mode;
}
