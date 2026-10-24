import { t } from '@/i18n';
import type { AllocationCategory, AllocationPolicy } from '@/api/allocationDefaults';
import { ApiError } from '@/utils/http';
import { formatNumber } from '@/utils/format';

/** 账号类别的名称与说明（每次调用时翻译，语言切换后即时生效） */
export function categoryMeta(category: AllocationCategory): { title: string; description: string } {
  switch (category) {
    case 'OFFICIAL_API_KEY':
      return { title: t('官方 API Key'), description: t('OpenAI / Anthropic 官方地址') };
    case 'COMPATIBLE_API_KEY':
      return { title: t('兼容 API Key'), description: t('第三方网关、DeepSeek 等') };
    case 'CODEX':
      return { title: 'Codex', description: t('ChatGPT 登录') };
    case 'CLAUDE_OAUTH':
      return { title: 'Claude OAuth', description: t('Claude 账号 / setup token') };
    default:
      return { title: t('Kiro / 其他账号'), description: t('Kiro 及未归入以上类型的账号') };
  }
}

/** 与后端一致的取值范围 [最小, 最大] */
export const POLICY_RANGES = {
  safeConcurrency: [0, 10000],
  safeRpm: [0, 1_000_000],
  burstCapacity: [0, 100000],
  maxSessions: [0, 100000],
  sessionIdleSeconds: [60, 86400],
  cooldownSeconds: [0, 86400],
  maxInflightTokens: [1, 1_000_000_000],
  allocationPriority: [1, 10000],
} as const;

export type NumericPolicyKey = keyof typeof POLICY_RANGES;

export function policyFieldLabel(key: string): string {
  switch (key) {
    case 'safeConcurrency':
      return t('并发上限');
    case 'safeRpm':
      return t('平均每分钟请求数');
    case 'burstCapacity':
      return t('突发容量');
    case 'maxSessions':
      return t('窗口内不同会话上限');
    case 'sessionIdleSeconds':
      return t('会话窗口（秒）');
    case 'cooldownSeconds':
      return t('冷却时间');
    case 'maxInflightTokens':
      return t('最大在途令牌数');
    case 'allocationPriority':
      return t('分配优先级');
    case 'tlsProfile':
      return t('TLS 指纹');
    default:
      return key;
  }
}

export interface PolicyCell {
  key: string;
  label: string;
  value: string;
  /** 与内置值不同 */
  changed: boolean;
}

const unlimited = (value: number, text: (value: number) => string) =>
  value === 0 ? t('不限') : text(value);

/** 把一份策略压缩成几个「名称 / 取值」小格；changed 标出与内置值不同的项 */
export function policyCells(
  policy: AllocationPolicy,
  builtIn: AllocationPolicy,
  anthropic: boolean,
): PolicyCell[] {
  const differs = (...keys: (keyof AllocationPolicy)[]) =>
    keys.some((key) => policy[key] !== builtIn[key]);
  const cells: PolicyCell[] = [
    {
      key: 'safeConcurrency',
      label: t('并发上限'),
      value: unlimited(policy.safeConcurrency, String),
      changed: differs('safeConcurrency'),
    },
    {
      key: 'safeRpm',
      label: t('每分钟请求数'),
      value: unlimited(policy.safeRpm, (rpm) =>
        t('{p0}（突发 {p1}）', { p0: rpm, p1: policy.burstCapacity }),
      ),
      changed: differs('safeRpm', 'burstCapacity'),
    },
  ];
  if (anthropic) {
    cells.push({
      key: 'maxSessions',
      label: t('会话上限'),
      value: unlimited(policy.maxSessions, (sessions) =>
        t('{p0}（窗口 {p1} 秒）', { p0: sessions, p1: policy.sessionIdleSeconds }),
      ),
      changed: differs('maxSessions', 'sessionIdleSeconds'),
    });
  }
  cells.push(
    {
      key: 'cooldownSeconds',
      label: t('冷却时间'),
      value:
        policy.cooldownSeconds === 0 ? t('自动') : t('{p0} 秒', { p0: policy.cooldownSeconds }),
      changed: differs('cooldownSeconds'),
    },
    {
      key: 'allocationPriority',
      label: t('分配优先级'),
      value: String(policy.allocationPriority),
      changed: differs('allocationPriority'),
    },
    {
      key: 'maxInflightTokens',
      label: t('最大在途令牌数'),
      value: formatNumber(policy.maxInflightTokens),
      changed: differs('maxInflightTokens'),
    },
  );
  if (anthropic) {
    cells.push({
      key: 'tlsProfile',
      label: t('TLS 指纹'),
      value: policy.tlsProfile || t('不使用'),
      changed: differs('tlsProfile'),
    });
  }
  return cells;
}

/** 后端校验失败（VALIDATION_ERROR / ALLOCATION_DEFAULTS_INVALID）的中文说明 */
export function allocationErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return t('服务暂时不可用');
  const raw = err.message;
  if (err.code === 'VALIDATION_ERROR') return t('提交的内容无效：{p0}', { p0: raw });
  // 形如 "CODEX: safeRpm must be between 0 and 1000000"
  const match = /^([A-Z_]+): (.*)$/s.exec(raw);
  const category = match && match[1];
  const detail = match ? match[2] : raw;
  const prefix = category ? `${categoryMeta(category as AllocationCategory).title}: ` : '';
  return prefix + translateProblem(detail);
}

function translateProblem(detail: string): string {
  const range = /^(\w+) must be between (\d+) and (\d+)$/.exec(detail);
  if (range) {
    return t('{p0} 必须在 {p1} 到 {p2} 之间。', {
      p0: policyFieldLabel(range[1]),
      p1: range[2],
      p2: range[3],
    });
  }
  if (detail === 'burstCapacity must be 0 when safeRpm is 0') {
    return t('平均每分钟请求数为 0 时，突发容量必须为 0。');
  }
  if (detail === 'burstCapacity must be positive when safeRpm is set') {
    return t('设置了平均每分钟请求数后，突发容量必须大于 0。');
  }
  if (detail.startsWith('tlsProfile must be')) {
    return t('TLS 指纹只能为空、chrome136 或 safari18_4。');
  }
  if (detail.startsWith('maxSessions and tlsProfile only apply')) {
    return t('窗口内不同会话上限和 TLS 指纹只适用于 Anthropic 账号，该类别不支持。');
  }
  if (detail.startsWith('Unknown account category')) return t('未知的账号类别。');
  return detail;
}
