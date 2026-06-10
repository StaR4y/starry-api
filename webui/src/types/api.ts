/**
 * 后端 API 类型定义。
 *
 * 与 `api/` 模块的 Java DTO 一一对应，命名保留 camelCase 与后端字段一致，
 * 便于以后由 OpenAPI 自动生成时无缝替换。
 */

/** 后端统一响应包络 */
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  error?: { code: string; message: string };
  timestamp: string;
}

/* ================== 鉴权 / 管理员 ================== */

export type AdminUserRole = 'ADMIN' | 'USER';

export interface AdminUser {
  userId: string;
  username: string;
  email: string | null;
  displayName: string | null;
  role: AdminUserRole;
  balance: number | null;
  enabled: boolean;
  firstLogin: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  tokenType: string;
  accessToken: string;
  expiresAt: string;
  user: AdminUser;
}

export interface RegisterRequest {
  username: string;
  email: string;
  displayName?: string;
  password: string;
  verifyCode?: string;
  inviteCode?: string;
}

export interface SendVerifyCodeRequest {
  email: string;
}

export interface SendVerifyCodeResult {
  message: string;
  countdown: number;
}

export interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export interface CreateAdminUserRequest {
  username: string;
  email?: string;
  displayName?: string;
  role?: AdminUserRole;
  balance?: number;
  password?: string;
}

export interface UpdateAdminUserRequest {
  email?: string;
  displayName?: string;
  role?: AdminUserRole;
  balance?: number;
  enabled?: boolean;
}

export interface AdminUserCreated {
  user: AdminUser;
  password: string | null;
}

export interface ResetPasswordResult {
  userId: string;
  username: string;
  password: string;
}

export interface RegistrationSettings {
  registrationEnabled: boolean;
  emailVerifyEnabled: boolean;
  inviteCodeEnabled: boolean;
  registrationEmailSuffixWhitelist: string[];
  smtpHost?: string | null;
  smtpPort?: number | null;
  smtpUsername?: string | null;
  smtpPasswordConfigured?: boolean | null;
  smtpFromEmail?: string | null;
  smtpFromName?: string | null;
  smtpUseTls?: boolean | null;
  smtpSecurity?: SmtpSecurityMode | string | null;
}

export type SmtpSecurityMode = 'auto' | 'ssl' | 'starttls' | 'none';

export interface UpdateRegistrationSettingsRequest {
  registrationEnabled: boolean;
  emailVerifyEnabled: boolean;
  inviteCodeEnabled: boolean;
  registrationEmailSuffixWhitelist: string[];
  smtpHost?: string;
  smtpPort?: number;
  smtpUsername?: string;
  smtpPassword?: string;
  smtpFromEmail?: string;
  smtpFromName?: string;
  smtpUseTls?: boolean;
  smtpSecurity?: SmtpSecurityMode;
}

export interface CreateInviteCodeRequest {
  expiresAt?: string | null;
}

export interface InviteCode {
  codeId: string;
  code: string;
  enabled: boolean;
  used: boolean;
  usedByUserId: string | null;
  usedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/* ================== 账号 ================== */

export interface Account {
  accountId: string;
  email: string | null;
  region: string | null;
  authMethod: string | null;
  provider: string | null;
  baseUrl: string | null;
  upstreamAccountId: string | null;
  tokenExpiresAt: string | null;
  profileArn: string | null;
  machineId: string | null;
  proxyUrl: string | null;
  enabled: boolean;
  requestCount: number | null;
  errorCount: number | null;
  quotaUsed: number | null;
  quotaLimit: number | null;
  quotaExhaustedAt: string | null;
  quotaResetAt: string | null;
  quota5hUsed: number | null;
  quota5hLimit: number | null;
  quota5hResetAt: string | null;
  quota7dUsed: number | null;
  quota7dLimit: number | null;
  quota7dResetAt: string | null;
  suspendedAt: string | null;
  suspendReason: string | null;
  suspendMessage: string | null;
  lastUsedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAccountRequest {
  accountId?: string;
  id?: string;
  email?: string;
  accessToken?: string;
  refreshToken?: string;
  clientId?: string;
  clientSecret?: string;
  region?: string;
  authMethod?: string;
  provider?: string;
  idp?: string;
  baseUrl?: string;
  upstreamAccountId?: string;
  idToken?: string;
  tokenExpiresAt?: string;
  profileArn?: string;
  machineId?: string;
  proxyUrl?: string;
  quotaLimit?: number;
  quota5hLimit?: number;
  quota7dLimit?: number;
  credentials?: {
    accessToken?: string;
    csrfToken?: string;
    refreshToken?: string;
    clientId?: string;
    clientSecret?: string;
    region?: string;
    authMethod?: string;
    provider?: string;
    baseUrl?: string;
    upstreamAccountId?: string;
    idToken?: string;
    tokenExpiresAt?: string;
  };
  usage?: {
    limit?: number;
  };
}

export type UpdateAccountRequest = Partial<
  Omit<CreateAccountRequest, 'accountId'> & {
    enabled: boolean;
    quotaUsed: number;
    quotaResetAt: string;
    quota5hUsed: number;
    quota5hLimit: number;
    quota5hResetAt: string;
    quota7dUsed: number;
    quota7dLimit: number;
    quota7dResetAt: string;
  }
>;

export interface AccountSettings {
  gptFastModeEnabled: boolean;
}

export interface UpdateAccountSettingsRequest {
  gptFastModeEnabled?: boolean;
}

export interface SuspendAccountRequest {
  reason: string;
  message?: string;
}

export interface TestAccountsRequest {
  accountIds?: string[];
  onlyEnabled?: boolean;
  model?: string;
  prompt?: string;
  maxConcurrency?: number;
  updateStatus?: boolean;
}

export interface TestModelOption {
  label: string;
  value: string;
}

export interface TestModelOptionsResult {
  kiro: TestModelOption[];
  openAi: TestModelOption[];
  google: TestModelOption[];
  anthropic: TestModelOption[];
  codex: TestModelOption[];
}

export interface TestAccountsResult {
  total: number;
  success: number;
  failed: number;
  skipped: number;
  startedAt: string;
  finishedAt: string;
  items: TestAccountItem[];
}

export interface TestAccountItem {
  accountId: string;
  email: string | null;
  status: 'SUCCESS' | 'FAILED' | 'SKIPPED';
  message: string;
  detail: string | null;
  statusCode: number | null;
  latencyMs: number | null;
  testedAt: string;
  model: string | null;
}

export interface ImportAccountsRequest {
  accounts: CreateAccountRequest[];
  upsert?: boolean;
}

export interface ImportAccountItem {
  index: number;
  accountId: string | null;
  email: string | null;
  status: 'CREATED' | 'UPDATED' | 'SKIPPED' | 'FAILED';
  message: string | null;
  account: Account | null;
}

export interface ImportAccountsResult {
  total: number;
  created: number;
  updated: number;
  skipped: number;
  failed: number;
  items: ImportAccountItem[];
}

export interface ClearAccountsResult {
  deleted: number;
}

/* ================== API Key ================== */

export interface ApiKey {
  keyId: string;
  userId: string | null;
  name: string;
  key: string | null;
  keyPrefix: string;
  enabled: boolean;
  creditsLimit: number | null;
  totalRequests: number | null;
  totalCredits: number | null;
  totalInputTokens: number | null;
  totalOutputTokens: number | null;
  lastUsedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateApiKeyRequest {
  name: string;
  creditsLimit?: number;
}

export interface UpdateApiKeyRequest {
  name?: string;
  enabled?: boolean;
  creditsLimit?: number;
}

export interface CreatedApiKey {
  keyId: string;
  userId: string | null;
  name: string;
  /** 完整 sk-... 密钥 */
  key: string;
  keyPrefix: string;
  enabled: boolean;
  creditsLimit: number | null;
  createdAt: string;
}

/* ================== 模型映射 ================== */

export type MappingType = 'replace' | 'alias' | 'loadbalance';

export interface ModelMapping {
  mappingId: string;
  name: string;
  enabled: boolean;
  mappingType: MappingType;
  sourceModel: string;
  targetModels: string[];
  weights: number[] | null;
  priority: number;
  apiKeyIds: string[] | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateModelMappingRequest {
  name: string;
  mappingType: MappingType;
  sourceModel: string;
  targetModels: string[];
  weights?: number[];
  priority?: number;
  apiKeyIds?: string[];
}

export interface UpdateModelMappingRequest {
  name?: string;
  enabled?: boolean;
  mappingType?: MappingType;
  sourceModel?: string;
  targetModels?: string[];
  weights?: number[];
  priority?: number;
  apiKeyIds?: string[];
}

export interface ModelPricingRule {
  ruleId: string;
  enabled: boolean;
  modelPattern: string;
  inputMultiplier: number | null;
  outputMultiplier: number | null;
  totalMultiplier: number | null;
  inputPricePerMillionTokens: number | null;
  outputPricePerMillionTokens: number | null;
  totalPricePerMillionTokens: number | null;
  priority: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ModelPricingSettings {
  rules: ModelPricingRule[];
}

export interface UpdateModelPricingRuleRequest {
  ruleId?: string;
  enabled?: boolean;
  modelPattern: string;
  inputMultiplier?: number | null;
  outputMultiplier?: number | null;
  totalMultiplier?: number | null;
  inputPricePerMillionTokens?: number | null;
  outputPricePerMillionTokens?: number | null;
  totalPricePerMillionTokens?: number | null;
  priority?: number;
}

export interface UpdateModelPricingRequest {
  rules: UpdateModelPricingRuleRequest[];
}

/* ================== 日志 / 模型清单 ================== */

export interface RequestLog {
  requestId: string;
  apiKeyId: string | null;
  accountId: string | null;
  path: string;
  model: string | null;
  statusCode: number | null;
  success: boolean;
  inputTokens: number | null;
  outputTokens: number | null;
  credits: number | null;
  latencyMs: number | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface PerformanceMetric {
  systemCpuLoadPercent: number | null;
  processCpuLoadPercent: number | null;
  heapUsedBytes: number | null;
  heapCommittedBytes: number | null;
  heapMaxBytes: number | null;
  nonHeapUsedBytes: number | null;
  systemMemoryTotalBytes: number | null;
  systemMemoryFreeBytes: number | null;
  systemMemoryUsedPercent: number | null;
  networkRxBytes: number | null;
  networkTxBytes: number | null;
  networkRxBytesPerSecond: number | null;
  networkTxBytesPerSecond: number | null;
  sampledAt: string;
}

export interface ModelInfo {
  id: string;
  object: string;
  owned_by: string;
  provider?: string;
  family?: string;
  targetModelId?: string;
  name?: string;
  modelName?: string;
  description?: string;
  supportedInputTypes?: string[];
  maxInputTokens?: number;
  maxOutputTokens?: number;
  context_length?: number;
  max_output_tokens?: number;
}

export interface HealthInfo {
  status: string;
  service: string;
  uptime_ms: number;
}

/* ================== 支付 ================== */

export type PaymentType = 'alipay' | 'wxpay';

export type PaymentOrderStatus = 'PENDING' | 'COMPLETED' | 'EXPIRED' | 'CANCELLED' | 'FAILED' | 'PAID';

export interface PaymentSettings {
  enabled: boolean;
  minAmount: number;
  maxAmount: number;
  dailyLimit: number;
  orderTimeoutMinutes: number;
  maxPendingOrders: number;
  enabledTypes: PaymentType[];
  balanceRechargeMultiplier: number;
  apiBillingMultiplier: number;
  rechargeFeeRate: number;
  productNamePrefix: string;
  productNameSuffix: string;
  helpImageUrl: string;
  helpText: string;
  easypayApiBase?: string | null;
  easypayPid?: string | null;
  easypayPkeyConfigured?: boolean | null;
  easypayPaymentMode?: 'popup' | 'qrcode' | string | null;
}

export interface UpdatePaymentSettingsRequest {
  enabled?: boolean;
  minAmount?: number;
  maxAmount?: number;
  dailyLimit?: number;
  orderTimeoutMinutes?: number;
  maxPendingOrders?: number;
  enabledTypes?: PaymentType[];
  balanceRechargeMultiplier?: number;
  apiBillingMultiplier?: number;
  rechargeFeeRate?: number;
  productNamePrefix?: string;
  productNameSuffix?: string;
  helpImageUrl?: string;
  helpText?: string;
  easypayApiBase?: string;
  easypayPid?: string;
  easypayPkey?: string;
  easypayPaymentMode?: 'popup' | 'qrcode';
}

export interface CreatePaymentOrderRequest {
  amount: number;
  paymentType: PaymentType;
  returnUrl?: string;
  mobile?: boolean;
}

export interface PaymentOrder {
  orderId: string;
  userId: string;
  username: string | null;
  email: string | null;
  amount: number;
  payAmount: number;
  feeRate: number;
  paymentType: PaymentType;
  status: PaymentOrderStatus;
  outTradeNo: string;
  paymentTradeNo: string | null;
  payUrl: string | null;
  qrCode: string | null;
  errorMessage: string | null;
  expiresAt: string;
  paidAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePaymentOrderResponse {
  order: PaymentOrder;
  payUrl: string | null;
  qrCode: string | null;
}
