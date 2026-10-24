import { locale, t } from '@/i18n';
import type {
  AllocationCategory,
  AllocationCategoryDefaults,
  AllocationPolicy,
} from '@/api/allocationDefaults';
import type { AccountTypeKey } from '@/views/accounts/components/account-form/accountTypes';
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

/** 每个类别在列表里显示的账号类型标志 */
export const CATEGORY_LOGO: Record<AllocationCategory, AccountTypeKey> = {
  OFFICIAL_API_KEY: 'openai',
  COMPATIBLE_API_KEY: 'compatible',
  CODEX: 'codex',
  CLAUDE_OAUTH: 'anthropic',
  KIRO: 'kiro',
};

export type LimitColumnKey =
  | 'concurrency'
  | 'rate'
  | 'sessions'
  | 'cooldown'
  | 'priority'
  | 'inflight'
  | 'tls';

/** 对比表的列：名称与说明在使用时翻译 */
export const LIMIT_COLUMNS: { key: LimitColumnKey; label: () => string; tip: () => string }[] = [
  {
    key: 'concurrency',
    label: () => t('并发上限'),
    tip: () => t('同一账号同时处理的请求数上限；0 为不限。'),
  },
  {
    key: 'rate',
    label: () => t('每分钟请求'),
    tip: () =>
      t(
        '平均每分钟请求数按令牌桶持续补充，括号内是突发容量（短时间内允许超出的请求数）；0 为不限速。',
      ),
  },
  {
    key: 'sessions',
    label: () => t('会话上限'),
    tip: () => t('会话窗口内同一账号最多服务的不同会话数；仅对 Anthropic 账号生效。'),
  },
  {
    key: 'cooldown',
    label: () => t('冷却时间'),
    tip: () => t('账号出错后暂停分配的时间；自动表示按错误类型决定。'),
  },
  {
    key: 'priority',
    label: () => t('优先级'),
    tip: () => t('分配优先级，数值越小越优先。'),
  },
  {
    key: 'inflight',
    label: () => t('在途令牌'),
    tip: () => t('同一账号同时预留的输入与输出令牌总数上限。'),
  },
  {
    key: 'tls',
    label: () => t('TLS 指纹'),
    tip: () => t('上游 TLS 握手使用的浏览器指纹；需要启用 TLS 组件，仅对 Anthropic 账号生效。'),
  },
];

const unlimited = (value: number, text: (value: number) => string) =>
  value === 0 ? t('不限') : text(value);

/** 100万 / 1M：表格列窄，完整数字放在提示里 */
function compactNumber(value: number): string {
  return new Intl.NumberFormat(locale.value, {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

/** 主值，以及放在第二行的补充（突发容量、会话窗口） */
function formatColumn(
  key: LimitColumnKey,
  policy: AllocationPolicy,
): { value: string; detail?: string } {
  switch (key) {
    case 'concurrency':
      return { value: unlimited(policy.safeConcurrency, String) };
    case 'rate':
      return policy.safeRpm === 0
        ? { value: t('不限') }
        : { value: String(policy.safeRpm), detail: t('突发 {p0}', { p0: policy.burstCapacity }) };
    case 'sessions':
      return policy.maxSessions === 0
        ? { value: t('不限') }
        : {
            value: String(policy.maxSessions),
            detail: t('窗口 {p0} 秒', { p0: policy.sessionIdleSeconds }),
          };
    case 'cooldown':
      return {
        value:
          policy.cooldownSeconds === 0 ? t('自动') : t('{p0} 秒', { p0: policy.cooldownSeconds }),
      };
    case 'priority':
      return { value: String(policy.allocationPriority) };
    case 'inflight':
      return { value: compactNumber(policy.maxInflightTokens), detail: undefined };
    case 'tls':
      return { value: policy.tlsProfile || t('不使用') };
  }
}

function describe(key: LimitColumnKey, policy: AllocationPolicy): string {
  if (key === 'inflight') return formatNumber(policy.maxInflightTokens);
  const { value, detail } = formatColumn(key, policy);
  return detail ? `${value} · ${detail}` : value;
}

const COLUMN_FIELDS: Record<LimitColumnKey, (keyof AllocationPolicy)[]> = {
  concurrency: ['safeConcurrency'],
  rate: ['safeRpm', 'burstCapacity'],
  sessions: ['maxSessions', 'sessionIdleSeconds'],
  cooldown: ['cooldownSeconds'],
  priority: ['allocationPriority'],
  inflight: ['maxInflightTokens'],
  tls: ['tlsProfile'],
};

export interface LimitCell {
  /** 会话与 TLS 只对 Anthropic 账号生效 */
  applicable: boolean;
  value: string;
  detail?: string;
  /** 完整取值，用于提示 */
  full: string;
  builtIn: string;
  /** 与内置值不同 */
  changed: boolean;
}

export function limitCell(key: LimitColumnKey, item: AllocationCategoryDefaults): LimitCell {
  const applicable = item.anthropicSettings || (key !== 'sessions' && key !== 'tls');
  const { value, detail } = formatColumn(key, item.effective);
  return {
    applicable,
    value,
    detail,
    full: describe(key, item.effective),
    builtIn: describe(key, item.builtIn),
    changed: COLUMN_FIELDS[key].some((field) => item.effective[field] !== item.builtIn[field]),
  };
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
