/**
 * 账号「路由与限制」的取值与比较。
 *
 * 新账号的起始值由管理员按账号类别配置（allocationDefaultsApi），这里不含任何默认数字：
 * 表单要么展示后端 resolve 返回的值，要么展示账号已保存的值。
 */
import { locale, t } from '@/i18n';
import type { AccountAllocationPolicy, UpdateAccountAllocationPolicyRequest } from '@/types/api';
import type { AllocationCategory, AllocationPolicy } from '@/api/allocationDefaults';

export type PolicyKey = keyof AllocationPolicy;

/** 只对 Anthropic 账号生效的字段 */
export const ANTHROPIC_ONLY_KEYS: PolicyKey[] = ['maxSessions', 'sessionIdleSeconds', 'tlsProfile'];

const POLICY_KEYS: PolicyKey[] = [
  'allocationPriority',
  'safeConcurrency',
  'safeRpm',
  'burstCapacity',
  'maxSessions',
  'sessionIdleSeconds',
  'maxInflightTokens',
  'cooldownSeconds',
  'tlsProfile',
];

export function clonePolicy(policy: AllocationPolicy): AllocationPolicy {
  return { ...policy };
}

/** 账号已保存的策略（含运行状态）里与可编辑部分对应的字段 */
export function policyOfAccount(policy: AccountAllocationPolicy): AllocationPolicy {
  return {
    allocationPriority: policy.allocationPriority,
    safeConcurrency: policy.safeConcurrency,
    safeRpm: policy.safeRpm,
    burstCapacity: policy.burstCapacity,
    maxSessions: policy.maxSessions,
    sessionIdleSeconds: policy.sessionIdleSeconds,
    maxInflightTokens: policy.maxInflightTokens,
    cooldownSeconds: policy.cooldownSeconds ?? 0,
    tlsProfile: policy.tlsProfile ?? '',
  };
}

/** 与基线相比被修改的字段；非 Anthropic 账号不提交只属于 Anthropic 的字段 */
export function diffPolicy(
  baseline: AllocationPolicy,
  current: AllocationPolicy,
  anthropic: boolean,
): UpdateAccountAllocationPolicyRequest {
  const changes: Record<string, unknown> = {};
  for (const key of POLICY_KEYS) {
    if (!anthropic && ANTHROPIC_ONLY_KEYS.includes(key)) continue;
    if (current[key] !== baseline[key]) changes[key] = current[key];
  }
  return changes as UpdateAccountAllocationPolicyRequest;
}

/** 新建时自定义的完整策略 */
export function fullPolicy(
  current: AllocationPolicy,
  anthropic: boolean,
): UpdateAccountAllocationPolicyRequest {
  const values: Record<string, unknown> = {};
  for (const key of POLICY_KEYS) {
    if (!anthropic && ANTHROPIC_ONLY_KEYS.includes(key)) continue;
    values[key] = current[key];
  }
  return values as UpdateAccountAllocationPolicyRequest;
}

/** 突发容量与每分钟请求数的约束：当且仅当 safeRpm 为 0 时 burstCapacity 为 0 */
export function burstProblem(policy: AllocationPolicy): string | null {
  if (policy.safeRpm === 0 && policy.burstCapacity !== 0) {
    return t('未限制每分钟请求数时，突发容量必须为 0。');
  }
  if (policy.safeRpm > 0 && policy.burstCapacity < 1) {
    return t('限制每分钟请求数时，突发容量至少为 1。');
  }
  return null;
}

export function categoryLabel(category: AllocationCategory | string): string {
  switch (category) {
    case 'OFFICIAL_API_KEY':
      return t('官方 API Key');
    case 'COMPATIBLE_API_KEY':
      return t('第三方 / 兼容 API Key');
    case 'CODEX':
      return 'Codex';
    case 'CLAUDE_OAUTH':
      return t('Claude OAuth / Setup Token');
    case 'KIRO':
      return t('Kiro 及其他非 API Key');
    default:
      return category;
  }
}

function compactNumber(value: number) {
  return new Intl.NumberFormat(locale.value, {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

/** 只读展示一组策略：字段名与已格式化的取值 */
export function describePolicy(
  policy: AllocationPolicy,
  anthropic: boolean,
): { key: PolicyKey; label: string; value: string }[] {
  const unlimited = t('不限');
  const rows: { key: PolicyKey; label: string; value: string }[] = [
    { key: 'allocationPriority', label: t('分配优先级'), value: String(policy.allocationPriority) },
    {
      key: 'safeConcurrency',
      label: t('并发上限'),
      value: policy.safeConcurrency === 0 ? unlimited : String(policy.safeConcurrency),
    },
    {
      key: 'safeRpm',
      label: t('平均每分钟请求数'),
      value: policy.safeRpm === 0 ? unlimited : String(policy.safeRpm),
    },
    {
      key: 'burstCapacity',
      label: t('突发容量'),
      value: policy.safeRpm === 0 ? '-' : String(policy.burstCapacity),
    },
    {
      key: 'cooldownSeconds',
      label: t('冷却时间'),
      value:
        policy.cooldownSeconds === 0 ? t('自动') : t('{p0} 秒', { p0: policy.cooldownSeconds }),
    },
    {
      key: 'maxInflightTokens',
      label: t('最大在途令牌数'),
      value: compactNumber(policy.maxInflightTokens),
    },
  ];
  if (anthropic) {
    rows.push(
      {
        key: 'maxSessions',
        label: t('窗口内不同会话上限'),
        value: policy.maxSessions === 0 ? unlimited : String(policy.maxSessions),
      },
      {
        key: 'sessionIdleSeconds',
        label: t('会话窗口'),
        value: t('{p0} 秒', { p0: policy.sessionIdleSeconds }),
      },
      { key: 'tlsProfile', label: t('TLS 指纹'), value: policy.tlsProfile || t('关闭') },
    );
  }
  return rows;
}
