/**
 * 账号表单的数据模型与提交载荷。
 *
 * 字段与后端 DTO 一致；可选字段用空字符串占位，提交时再清洗。
 * 路由与限制不在这里：它们只在管理员明确自定义（新建）或修改（编辑）时才进入载荷。
 */
import type {
  CreateAccountRequest,
  UpdateAccountAllocationPolicyRequest,
  UpdateAccountRequest,
} from '@/types/api';
import {
  KNOWN_DEFAULT_BASE_URLS,
  typeDefaults,
  type AccountTypeKey,
  OPENAI_COMPATIBLE_PROVIDER,
} from './accountTypes';

export interface AccountFormModel {
  accountId: string;
  email: string;
  accessToken: string;
  refreshToken: string;
  clientId: string;
  clientSecret: string;
  region: string;
  authMethod: string;
  provider: string;
  baseUrl: string;
  upstreamAccountId: string;
  idToken: string;
  profileArn: string;
  machineId: string;
  proxyUrl: string;
  proxyChannelId: string;
  poolTier: string;
  poolWeight: number;
  quotaLimit: number | undefined;
  enabled: boolean;
  /** 第三方 OpenAI 兼容厂商在模型管理中的名称；提交时写入 provider */
  catalogVendor: string;
}

export function createFormModel(): AccountFormModel {
  return {
    accountId: '',
    email: '',
    accessToken: '',
    refreshToken: '',
    clientId: '',
    clientSecret: '',
    ...typeDefaults('kiro'),
    upstreamAccountId: '',
    idToken: '',
    profileArn: '',
    machineId: '',
    proxyUrl: '',
    proxyChannelId: '',
    poolTier: 'primary',
    poolWeight: 100,
    quotaLimit: undefined,
    enabled: true,
    catalogVendor: '',
  };
}

/** 新建时切换账号类型：写入类型的身份字段，并清掉只属于其他类型的凭证 */
export function applyTypeToForm(form: AccountFormModel, type: AccountTypeKey) {
  const defaults = typeDefaults(type);
  const currentBaseUrl = form.baseUrl.trim();
  Object.assign(form, {
    provider: defaults.provider,
    authMethod: defaults.authMethod,
    region: defaults.region,
    catalogVendor: '',
    refreshToken: '',
    idToken: '',
    profileArn: '',
    upstreamAccountId: '',
    machineId: '',
  });
  // 只替换空值或上一个类型自带的默认地址，不覆盖管理员手填的地址
  if (!currentBaseUrl || KNOWN_DEFAULT_BASE_URLS.includes(currentBaseUrl)) {
    form.baseUrl = defaults.baseUrl;
  }
}

/** 选择 / 更换兼容厂商：厂商名就是账号的 provider */
export function applyVendorToForm(form: AccountFormModel, vendor: string) {
  form.catalogVendor = vendor;
  form.provider = vendor || OPENAI_COMPATIBLE_PROVIDER;
  form.authMethod = 'api-key';
}

/** 把空串字段从 payload 中剔除，避免覆盖后端为空 */
export function compact<T extends Record<string, unknown>>(obj: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(obj).filter(([, v]) => v !== '' && v !== undefined && v !== null),
  ) as Partial<T>;
}

export function buildCreatePayload(
  form: AccountFormModel,
  allocationPolicy: UpdateAccountAllocationPolicyRequest | undefined,
): CreateAccountRequest {
  const { catalogVendor: _catalogVendor, ...createFields } = form;
  return compact({ ...createFields, allocationPolicy }) as CreateAccountRequest;
}

export function buildUpdatePayload(
  form: AccountFormModel,
  allocationPolicy: UpdateAccountAllocationPolicyRequest | undefined,
): UpdateAccountRequest {
  const {
    accessToken,
    refreshToken,
    clientId,
    clientSecret,
    idToken,
    catalogVendor: _catalogVendor,
    ...rest
  } = form;
  // 编辑模式下令牌类字段仅在有值时提交
  const payload: UpdateAccountRequest = compact({
    ...rest,
    clearProxyChannel: !form.proxyChannelId,
    allocationPolicy,
    accessToken: accessToken || undefined,
    refreshToken: refreshToken || undefined,
    clientId: clientId || undefined,
    clientSecret: clientSecret || undefined,
    idToken: idToken || undefined,
  });
  // 空串也要提交：清除旧的代理 URL（选择节点或改为直连时）
  payload.proxyUrl = form.proxyUrl;
  return payload;
}
