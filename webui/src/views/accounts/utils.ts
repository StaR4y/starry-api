import { t } from '@/i18n';
/**
 * 账号管理页共享小函数。
 *
 * 阶段 4 样式清理时从 AccountListView.vue 与 components/ 各组件中
 * 收敛的重复定义，实现与原逐字一致，仅供 accounts 模块内部复用。
 */
import { ElMessage } from 'element-plus';
import type { Account, ManagedModel, TestAccountItem } from '@/types/api';

export interface CompatibleVendorOption {
  name: string;
  icon: string | null;
  color: string | null;
  modelCount: number;
}

export function customOpenAiCompatibleVendors(models: ManagedModel[]): CompatibleVendorOption[] {
  const grouped = new Map<string, ManagedModel[]>();
  models.forEach((model) => {
    if (model.builtIn) return;
    if (model.requestFormat && model.requestFormat !== 'OPENAI_CHAT_COMPLETIONS') return;
    const name = model.provider?.trim();
    if (!name || isFirstClassAccountProvider(name)) return;
    grouped.set(name, [...(grouped.get(name) || []), model]);
  });
  return Array.from(grouped, ([name, vendorModels]) => ({
    name,
    icon:
      vendorModels.find((model) => model.enabled && model.icon)?.icon ||
      vendorModels.find((model) => model.icon)?.icon ||
      null,
    color:
      vendorModels.find((model) => model.enabled && model.badgeColor)?.badgeColor ||
      vendorModels.find((model) => model.badgeColor)?.badgeColor ||
      null,
    modelCount: vendorModels.length,
  })).sort((left, right) => left.name.localeCompare(right.name));
}

export function normalized(value: string | null | undefined) {
  return (value ?? '').trim().toLowerCase();
}

export function normalizedPoolTier(value: string | null | undefined) {
  return normalized(value) === 'secondary' ? 'secondary' : 'primary';
}

export function poolTierLabel(value: string | null | undefined) {
  return normalizedPoolTier(value) === 'secondary' ? t('二级号池') : t('一级号池');
}

export function poolTierTagType(value: string | null | undefined) {
  return normalizedPoolTier(value) === 'secondary' ? 'warning' : 'success';
}

export function statusOf(account: Account) {
  if (!account.enabled)
    return {
      get label() {
        return t('已停用');
      },
      tone: 'info' as const,
    };
  if (account.suspendedAt)
    return {
      get label() {
        return t('挂起');
      },
      tone: 'danger' as const,
    };
  if (account.quotaExhaustedAt)
    return {
      get label() {
        return t('配额用尽');
      },
      tone: 'warning' as const,
    };
  return {
    get label() {
      return t('可用');
    },
    tone: 'success' as const,
  };
}

export function isCodexAccount(row: Account) {
  const provider = (row.provider || '').trim().toLowerCase();
  const authMethod = (row.authMethod || '').trim().toLowerCase();
  return provider === 'codex' || authMethod === 'codex';
}

const FIRST_CLASS_PROVIDER_KEYS = new Set([
  'openai',
  'openai-compatible',
  'openaicompatibility',
  'google',
  'gemini',
  'anthropic',
  'claude',
  'deepseek',
  'codex',
  'builderid',
  'builder_id',
  'kiro',
]);

export function isFirstClassAccountProvider(provider: string | null | undefined) {
  const value = normalized(provider);
  if (!value || FIRST_CLASS_PROVIDER_KEYS.has(value)) return true;
  return (
    value.includes('builder') ||
    value.includes('kiro') ||
    value.includes('enterprise') ||
    value.includes('sso') ||
    value.includes('idc')
  );
}

export function isLegacyOpenAiCompatibleProvider(provider: string | null | undefined) {
  const value = normalized(provider);
  return value === 'openai-compatible' || value === 'openaicompatibility';
}

export function isCustomOpenAiCompatibleProvider(
  provider: string | null | undefined,
  authMethod?: string | null,
) {
  if (isFirstClassAccountProvider(provider)) return false;
  const auth = normalized(authMethod);
  return !auth || auth === 'api-key' || auth === 'apikey' || auth === 'openai-api-key';
}

export function getProviderDisplayName(provider: string | null | undefined): string {
  if (!provider) return 'Unknown';
  if (isLegacyOpenAiCompatibleProvider(provider)) return 'OpenAI-API';
  return provider;
}

export function badgeColorFromName(name: string | null | undefined) {
  const text = (name ?? '').trim() || 'vendor';
  let hash = 0;
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash * 31 + text.charCodeAt(index)) >>> 0;
  }
  const hues = [210, 262, 187, 24, 340, 152, 18, 286];
  return hslToHex(hues[hash % hues.length], 58, 46);
}

function hslToHex(h: number, s: number, l: number) {
  const saturation = s / 100;
  const lightness = l / 100;
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
  const hp = h / 60;
  const x = chroma * (1 - Math.abs((hp % 2) - 1));
  let r = 0;
  let g = 0;
  let b = 0;
  if (hp < 1) [r, g, b] = [chroma, x, 0];
  else if (hp < 2) [r, g, b] = [x, chroma, 0];
  else if (hp < 3) [r, g, b] = [0, chroma, x];
  else if (hp < 4) [r, g, b] = [0, x, chroma];
  else if (hp < 5) [r, g, b] = [x, 0, chroma];
  else [r, g, b] = [chroma, 0, x];
  const match = lightness - chroma / 2;
  const toHex = (value: number) =>
    Math.round((value + match) * 255)
      .toString(16)
      .padStart(2, '0');
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

export function colorBadgeStyle(hex: string | null | undefined) {
  const color = hex && /^#[0-9A-Fa-f]{6}$/.test(hex) ? hex : '#54607a';
  const r = Number.parseInt(color.slice(1, 3), 16);
  const g = Number.parseInt(color.slice(3, 5), 16);
  const b = Number.parseInt(color.slice(5, 7), 16);
  return {
    background: `rgba(${r}, ${g}, ${b}, 0.10)`,
    color,
    border: `1px solid rgba(${r}, ${g}, ${b}, 0.22)`,
  };
}

export function providerInitials(provider: string | null | undefined) {
  const parts = (provider ?? '')
    .trim()
    .split(/[\s_-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase());
  return parts.join('') || 'V';
}

export function testStatusType(status: TestAccountItem['status']) {
  if (status === 'SUCCESS') return 'success';
  if (status === 'SKIPPED') return 'info';
  return 'danger';
}

export async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(t('已复制'));
  } catch {
    ElMessage.warning(t('请手动复制'));
  }
}
