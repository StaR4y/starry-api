/** Beijing peak/off-peak helpers, aligned with dsh-usage-plugin. */

export const WEEKEND_FLAT_AT = Date.parse('2026-08-23T00:00:00+08:00');

export type UsagePreset = 'today' | '7d' | '30d' | 'all' | 'custom';

export function pad2(value: number) {
  return String(value).padStart(2, '0');
}

export function shanghaiParts(value: Date | string | number) {
  const date = new Date(value);
  const map: Record<string, string> = {};
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    weekday: 'short',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
  for (const part of formatter.formatToParts(date)) {
    if (part.type !== 'literal') map[part.type] = part.value;
  }
  return map;
}

export function shanghaiDay(value: Date | string | number) {
  const parts = shanghaiParts(value);
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function shanghaiDateTime(value: Date | string | number) {
  const parts = shanghaiParts(value);
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}

export function isPeakAt(value: Date | string | number) {
  const timestamp = new Date(value).getTime();
  if (!Number.isFinite(timestamp)) return false;
  const parts = shanghaiParts(timestamp);
  if (timestamp >= WEEKEND_FLAT_AT && (parts.weekday === 'Sat' || parts.weekday === 'Sun')) {
    return false;
  }
  const minutes = Number(parts.hour) * 60 + Number(parts.minute);
  return (minutes >= 9 * 60 && minutes < 12 * 60) || (minutes >= 14 * 60 && minutes < 18 * 60);
}

export function currentPeriodLabel() {
  return isPeakAt(Date.now()) ? '当前 · 高峰时段' : '当前 · 空闲时段';
}

export function rangeForPreset(preset: UsagePreset, from = '', to = '') {
  if (preset === 'all') return { from: undefined as string | undefined, to: undefined as string | undefined };
  const today = shanghaiDay(Date.now());
  if (preset === 'today') return { from: today, to: today };
  if (preset === '7d' || preset === '30d') {
    const span = preset === '7d' ? 6 : 29;
    const start = new Date(Date.parse(`${today}T00:00:00+08:00`) - span * 86400000);
    return { from: shanghaiDay(start), to: today };
  }
  return { from: from || undefined, to: to || undefined };
}

export function monthLength(year: number, monthIndex: number) {
  return new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
}

export function monthMondayOffset(year: number, monthIndex: number) {
  const weekday = shanghaiParts(`${year}-${pad2(monthIndex + 1)}-01T12:00:00+08:00`).weekday;
  const mondayFirst: Record<string, number> = {
    Mon: 0,
    Tue: 1,
    Wed: 2,
    Thu: 3,
    Fri: 4,
    Sat: 5,
    Sun: 6,
  };
  return mondayFirst[weekday] ?? 0;
}
