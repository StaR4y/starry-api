import type { RequestLog } from '@/types/api';

/** 请求日志的号源列：只有按号源分级服务的请求的行才有，没有分级的行与以前一样 */

/** 列表里有没有任何一行带号源；没有就不显示这一列 */
export function hasSourceTiers(logs: RequestLog[]): boolean {
  return logs.some((log) => !!log.sourceTierRef || log.sourceTierLevel != null);
}

/** 「L2 kiro」；没有号源的行为空字符串 */
export function sourceTierLabel(log: RequestLog): string {
  if (log.sourceTierLevel == null && !log.sourceTierName) return '';
  const name = log.sourceTierName ?? log.sourceTierRef ?? '';
  return log.sourceTierLevel != null ? `L${log.sourceTierLevel} ${name}`.trim() : name;
}

/** 价格系数，去掉末尾的 0：0.600000 → ×0.6；没有系数为空字符串 */
export function priceFactorLabel(log: RequestLog): string {
  const factor = log.priceFactor;
  if (factor == null || !Number.isFinite(Number(factor))) return '';
  return `×${Number(factor)}`;
}
