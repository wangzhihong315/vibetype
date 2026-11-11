import { useEffect, useState } from "react";

export type Lang = "zh" | "en";

const KEY = "vibetype.lang";
const listeners = new Set<() => void>();

function initial(): Lang {
  const saved = localStorage.getItem(KEY);
  if (saved === "zh" || saved === "en") return saved;
  return navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en";
}

let current: Lang = initial();

function syncDocument() {
  document.documentElement.lang = current === "zh" ? "zh-CN" : "en";
}

syncDocument();

export function getLang(): Lang {
  return current;
}

export function setLang(lang: Lang) {
  current = lang;
  localStorage.setItem(KEY, lang);
  syncDocument();
  listeners.forEach((fn) => fn());
}

/** 订阅语言变化；App 根部调用一次即可触发整页重渲染。 */
export function useLang(): Lang {
  const [lang, force] = useState(current);
  useEffect(() => {
    const notify = () => force(current);
    listeners.add(notify);
    return () => {
      listeners.delete(notify);
    };
  }, []);
  return lang;
}

export function t(zh: string, en: string): string {
  return current === "en" ? en : zh;
}

/** 相对时间、条数这类带变量的文案。 */
export function count(n: number, zhUnit = "条", enUnit = "total"): string {
  return current === "en" ? `${n} ${enUnit}` : `${n} ${zhUnit}`;
}

export function ago(ms: number): string {
  if (!ms) return t("还没连过", "Never connected");
  const diff = Date.now() - ms;
  if (diff < 90_000) return t("刚刚在线", "Just now");
  if (diff < 3_600_000) {
    const n = Math.round(diff / 60_000);
    return current === "en" ? `${n} min ago` : `${n} 分钟前`;
  }
  if (diff < 86_400_000) {
    const n = Math.round(diff / 3_600_000);
    return current === "en" ? `${n} hr ago` : `${n} 小时前`;
  }
  const n = Math.round(diff / 86_400_000);
  return current === "en" ? `${n} d ago` : `${n} 天前`;
}

export function clock(ms: number): string {
  const at = new Date(ms);
  const hh = String(at.getHours()).padStart(2, "0");
  const mm = String(at.getMinutes()).padStart(2, "0");
  return `${hh}:${mm}`;
}

export function day(ms: number): string {
  const at = new Date(ms);
  const now = new Date();
  const sameDay =
    at.getFullYear() === now.getFullYear() &&
    at.getMonth() === now.getMonth() &&
    at.getDate() === now.getDate();
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const isYesterday =
    at.getFullYear() === yesterday.getFullYear() &&
    at.getMonth() === yesterday.getMonth() &&
    at.getDate() === yesterday.getDate();

  if (sameDay) return current === "en" ? `Today ${clock(ms)}` : `今天 ${clock(ms)}`;
  if (isYesterday) {
    return current === "en" ? `Yesterday ${clock(ms)}` : `昨天 ${clock(ms)}`;
  }
  if (current === "en") {
    return `${at.getMonth() + 1}/${at.getDate()} ${clock(ms)}`;
  }
  return `${at.getMonth() + 1} 月 ${at.getDate()} 日 ${clock(ms)}`;
}
