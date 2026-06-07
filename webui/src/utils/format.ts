/**
 * 通用格式化工具。所有视图共用，避免在 template 中写复杂表达式。
 */

/** 安全格式化 ISO 时间，缺省返回占位符 */
export function formatDateTime(value: string | null | undefined, fallback = '—'): string {
  if (!value) return fallback;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return fallback;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 仅展示数字，null 用占位符 */
export function formatNumber(value: number | null | undefined, fallback = '—'): string {
  if (value === null || value === undefined) return fallback;
  return value.toLocaleString();
}

/** 用于 mono 字段的截断显示 */
export function shorten(value: string | null | undefined, head = 8, tail = 4): string {
  if (!value) return '—';
  if (value.length <= head + tail + 3) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}
