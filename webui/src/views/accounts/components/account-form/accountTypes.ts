/**
 * 账号类型：新建时由管理员选择，编辑时按已有账号的 provider / authMethod 推断。
 *
 * 类型决定表单显示哪些凭证字段、默认的 provider / authMethod / Base URL，
 * 以及说明文案。路由与限制的默认值不在这里：它们由管理员按账号类别配置，
 * 表单通过 allocationDefaultsApi.resolve 取得。
 */
import { t } from '@/i18n';
import {
  isCustomOpenAiCompatibleProvider,
  isLegacyOpenAiCompatibleProvider,
  normalized,
} from '../../utils';

export type AccountTypeKey =
  | 'kiro'
  | 'openai'
  | 'compatible'
  | 'anthropic'
  | 'deepseek'
  | 'codex'
  | 'other';

/** 新建时可选的类型，按选择器中的顺序排列；'other' 只会出现在编辑旧账号时 */
export const CREATE_ACCOUNT_TYPES: Exclude<AccountTypeKey, 'other'>[] = [
  'kiro',
  'openai',
  'compatible',
  'anthropic',
  'deepseek',
  'codex',
];

export const OPENAI_COMPATIBLE_PROVIDER = 'OpenAI-compatible';
export const ANTHROPIC_DEFAULT_BASE_URL = 'https://api.anthropic.com/v1';
export const DEEPSEEK_DEFAULT_BASE_URL = 'https://api.deepseek.com/v1';
/** 切换类型时，这些地址视为「类型自带的默认值」，可以被新类型的默认值替换 */
export const KNOWN_DEFAULT_BASE_URLS = [ANTHROPIC_DEFAULT_BASE_URL, DEEPSEEK_DEFAULT_BASE_URL];

export function isAnthropicProvider(provider: string | null | undefined) {
  const value = normalized(provider);
  return value === 'anthropic' || value === 'claude';
}

export function isDeepSeekProvider(provider: string | null | undefined) {
  return normalized(provider) === 'deepseek';
}

export function isApiKeyAuth(authMethod: string | null | undefined) {
  const value = normalized(authMethod);
  return value === 'api-key' || value === 'apikey' || value === 'openai-api-key';
}

function isKiroLikeProvider(provider: string | null | undefined) {
  const value = normalized(provider);
  return ['builder', 'kiro', 'enterprise', 'sso', 'idc'].some((part) => value.includes(part));
}

/** 按已保存的 provider / authMethod 推断账号类型（与后端的账号类别分类保持同一套判断顺序） */
export function accountTypeOf(
  provider: string | null | undefined,
  authMethod: string | null | undefined,
): AccountTypeKey {
  if (
    isCustomOpenAiCompatibleProvider(provider, authMethod) ||
    isLegacyOpenAiCompatibleProvider(provider)
  ) {
    return 'compatible';
  }
  const value = normalized(provider);
  if (value === 'codex' || normalized(authMethod) === 'codex') return 'codex';
  if (value === 'openai') return 'openai';
  if (isAnthropicProvider(provider)) return 'anthropic';
  if (isDeepSeekProvider(provider)) return 'deepseek';
  if (isKiroLikeProvider(provider)) return 'kiro';
  return 'other';
}

export interface TypeDefaults {
  provider: string;
  authMethod: string;
  region: string;
  baseUrl: string;
}

/** 新建账号选中某类型后写入表单的身份字段；Codex 与官方 OpenAI 同属 OpenAI，靠 authMethod 区分 */
export function typeDefaults(type: AccountTypeKey): TypeDefaults {
  switch (type) {
    case 'kiro':
      return { provider: 'BuilderID', authMethod: 'idc', region: 'us-east-1', baseUrl: '' };
    case 'openai':
      return { provider: 'OpenAI', authMethod: 'api-key', region: '', baseUrl: '' };
    case 'compatible':
      return {
        provider: OPENAI_COMPATIBLE_PROVIDER,
        authMethod: 'api-key',
        region: '',
        baseUrl: '',
      };
    case 'anthropic':
      return {
        provider: 'Anthropic',
        authMethod: 'api-key',
        region: '',
        baseUrl: ANTHROPIC_DEFAULT_BASE_URL,
      };
    case 'deepseek':
      return {
        provider: 'DeepSeek',
        authMethod: 'api-key',
        region: '',
        baseUrl: DEEPSEEK_DEFAULT_BASE_URL,
      };
    case 'codex':
      return { provider: 'OpenAI', authMethod: 'codex', region: '', baseUrl: '' };
    default:
      return { provider: '', authMethod: '', region: '', baseUrl: '' };
  }
}

export function accountTypeLabel(type: AccountTypeKey): string {
  switch (type) {
    case 'kiro':
      return 'BuilderID / Kiro';
    case 'openai':
      return t('OpenAI 官方 API Key');
    case 'compatible':
      return t('第三方 OpenAI 兼容 API Key');
    case 'anthropic':
      return 'Anthropic / Claude API Key';
    case 'deepseek':
      return 'DeepSeek API Key';
    case 'codex':
      return t('Codex（ChatGPT 账号）');
    default:
      return t('其他类型');
  }
}

/** 选择卡片上的一句话说明 */
export function accountTypeCaption(type: AccountTypeKey): string {
  switch (type) {
    case 'kiro':
      return t('Kiro / AWS BuilderID 账号，使用 Access Token');
    case 'openai':
      return t('Base URL 为 api.openai.com 的官方 Key');
    case 'compatible':
      return t('OpenRouter、中转站等，需要填写 Base URL');
    case 'anthropic':
      return t('Claude 官方 Key，也可接 Anthropic 兼容地址');
    case 'deepseek':
      return t('DeepSeek 官方 Key，已内置接口地址');
    case 'codex':
      return t('ChatGPT 账号，浏览器授权或手动填写令牌');
    default:
      return '';
  }
}

/** 类型说明（沿用旧表单里各类型的提示文案） */
export function accountTypeNotice(type: AccountTypeKey): string {
  switch (type) {
    case 'compatible':
      return t(
        '请先选择你在模型管理中自行添加、且使用 OpenAI 兼容协议的厂商。账号类型会显示该厂商当时配置的图标、名称和配色，测试时只能选择该厂商已配置的模型 ID。填写站点根地址时会自动使用 /v1。',
      );
    case 'deepseek':
      return t(
        'DeepSeek API Key 账号使用官方 OpenAI 兼容接口；系统已配置 https://api.deepseek.com/v1，无需手动填写 Base URL。可直接使用 deepseek-v4-flash 或 deepseek-v4-pro 模型 ID。',
      );
    case 'anthropic':
      return t(
        'Anthropic API Key 账号会按原生 Messages API 调用 Base URL；模型映射目标建议写成 anthropic/真实模型ID，例如 anthropic/claude-sonnet-4.5。',
      );
    case 'codex':
      return t('Codex 账号使用 ChatGPT 订阅额度。推荐用浏览器授权登录，无需手动复制令牌。');
    default:
      return '';
  }
}

/** 某类型需要填写哪些凭证相关字段 */
export interface CredentialSpec {
  /** 凭证是 API Key（否则是 Access Token） */
  apiKey: boolean;
  refreshToken: boolean;
  idToken: boolean;
  profileArn: boolean;
  upstreamAccountId: boolean;
  region: boolean;
  authMethod: boolean;
  baseUrl: boolean;
  machineId: boolean;
}

export function credentialSpec(type: AccountTypeKey, authMethod: string): CredentialSpec {
  switch (type) {
    case 'kiro':
      return {
        apiKey: false,
        refreshToken: true,
        idToken: false,
        profileArn: true,
        upstreamAccountId: false,
        region: true,
        authMethod: true,
        baseUrl: false,
        machineId: true,
      };
    case 'codex':
      return {
        apiKey: false,
        refreshToken: true,
        idToken: true,
        profileArn: false,
        upstreamAccountId: true,
        region: false,
        authMethod: false,
        baseUrl: true,
        machineId: false,
      };
    case 'openai':
    case 'compatible':
    case 'anthropic':
    case 'deepseek': {
      // 空的 authMethod 按 API Key 处理（后端对这些类型也是如此）
      const apiKey = type === 'compatible' || isApiKeyAuth(authMethod) || !normalized(authMethod);
      return {
        apiKey,
        refreshToken: !apiKey,
        idToken: false,
        profileArn: false,
        upstreamAccountId: false,
        region: false,
        authMethod: false,
        baseUrl: true,
        machineId: false,
      };
    }
    default:
      // 旧账号里无法归类的类型：保留旧表单的全部字段
      return {
        apiKey: isApiKeyAuth(authMethod),
        refreshToken: true,
        idToken: true,
        profileArn: true,
        upstreamAccountId: true,
        region: true,
        authMethod: true,
        baseUrl: true,
        machineId: true,
      };
  }
}

export function baseUrlLabel(type: AccountTypeKey): string {
  if (type === 'deepseek') return 'DeepSeek API Base URL';
  if (type === 'compatible') return t('第三方 Base URL');
  if (type === 'anthropic') return 'Anthropic Base URL';
  return 'Base URL';
}

export function baseUrlPlaceholder(type: AccountTypeKey): string {
  if (type === 'deepseek') return t('默认 {p0}', { p0: DEEPSEEK_DEFAULT_BASE_URL });
  if (type === 'compatible') {
    return t('例如 https://joystar.cc、https://api.deepseek.com 或 https://openrouter.ai/api/v1');
  }
  if (type === 'anthropic') {
    return t('默认 https://api.anthropic.com/v1，也可填第三方 Anthropic 兼容地址');
  }
  if (type === 'codex') return t('默认 {p0}', { p0: 'https://chatgpt.com/backend-api/codex' });
  return t('默认 https://api.openai.com/v1');
}

export function baseUrlHint(type: AccountTypeKey): string {
  if (type === 'compatible') {
    return t(
      '填到兼容 OpenAI 的 API 根路径，通常以 /v1 结尾；服务会自动追加 /chat/completions 或 /responses。',
    );
  }
  if (type === 'deepseek') {
    return t('已自动使用 DeepSeek 官方 API 地址；仅在接入兼容网关时需要修改。');
  }
  if (type === 'anthropic') {
    return t('填到 Anthropic Messages API 根路径，通常以 /v1 结尾；服务会自动追加 /messages。');
  }
  return '';
}

export function accessTokenPlaceholder(type: AccountTypeKey, apiKey: boolean): string {
  if (type === 'deepseek') return t('输入 DeepSeek API Key，如 sk-...');
  if (type === 'anthropic' && apiKey) return t('输入 Anthropic / Claude API Key，如 sk-ant-...');
  if (apiKey) return t('输入上游网站 API Key，如 sk-...');
  return t('输入 Access Token');
}
