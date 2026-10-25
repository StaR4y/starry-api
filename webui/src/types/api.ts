/**
 * 后端 API 类型定义。
 *
 * 与 `service-api/` 模块的 Java DTO 一一对应，命名保留 camelCase 与后端字段一致，
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
  affiliateCode: string | null;
  invitedByUserId: string | null;
  inviteSourceCode: string | null;
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
  emailVerificationToken?: string;
  turnstileToken?: string;
  affiliateCode?: string;
}

export interface SendVerifyCodeRequest {
  email: string;
  turnstileToken?: string;
  affiliateCode?: string;
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
  /** Only send when the balance was actually edited; the server rejects a stale value (409). */
  balance?: number;
  /** The balance the form was opened with. Required together with `balance`. */
  expectedBalance?: number;
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

export type SmtpVerifyStatus = 'UNCONFIGURED' | 'PENDING' | 'VERIFIED' | 'SKIPPED' | 'FAILED';

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
  turnstileEnabled?: boolean | null;
  turnstileSiteKey?: string | null;
  turnstileSecretConfigured?: boolean | null;
  smtpVerifyEnabled?: boolean | null;
  smtpVerifyStatus?: SmtpVerifyStatus | null;
  smtpVerifySentTo?: string | null;
  smtpVerifyExpiresAt?: string | null;
  smtpVerifyLastError?: string | null;
}

export type SmtpSecurityMode = 'auto' | 'ssl' | 'starttls' | 'none';

export interface UpdateRegistrationSettingsRequest {
  registrationEnabled?: boolean;
  emailVerifyEnabled?: boolean;
  inviteCodeEnabled?: boolean;
  registrationEmailSuffixWhitelist?: string[];
  smtpHost?: string;
  smtpPort?: number;
  smtpUsername?: string;
  smtpPassword?: string;
  smtpFromEmail?: string;
  smtpFromName?: string;
  smtpUseTls?: boolean;
  smtpSecurity?: SmtpSecurityMode;
  turnstileEnabled?: boolean;
  turnstileSiteKey?: string;
  turnstileSecretKey?: string;
  smtpVerifyEnabled?: boolean;
  smtpVerifySendNow?: boolean;
}

export interface SmtpVerifyRequest {
  token: string;
}

export interface SmtpVerifyResult {
  verified: boolean;
  status: SmtpVerifyStatus | string;
  message: string;
}

export interface CreateInviteCodeRequest {
  expiresAt?: string | null;
  count?: number;
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

export interface CreateInviteCodesResponse {
  total: number;
  codes: InviteCode[];
}

/* ================== 数据迁移 / 备份 ================== */

export interface DataTransferStatus {
  database: string;
  backupDir: string;
  maxImportBytes: number;
  backupCount: number;
  backupBytes: number;
}

export interface DataManagementSecurityStatus {
  initialized: boolean;
  locked: boolean;
  failedAttempts: number;
  maxFailedAttempts: number;
  unlockTtlSeconds: number;
  initializedAt: string | null;
  lastFailureAt: string | null;
  lockedAt: string | null;
  updatedAt: string | null;
}

export interface InitializeDataManagementSecurityResponse {
  status: DataManagementSecurityStatus;
  securityKey: string;
}

export type DataManagementAction =
  | 'VIEW_DATA_MANAGEMENT'
  | 'EXPORT_DATABASE'
  | 'EXPORT_BACKUP'
  | 'CREATE_BACKUP'
  | 'DOWNLOAD_BACKUP'
  | 'DELETE_BACKUP'
  | 'RESTORE_DATABASE';

export interface UnlockDataManagementResponse {
  tokenType: string;
  accessToken: string;
  expiresAt: string;
  action: DataManagementAction;
  target: string | null;
}

export interface BackupFile {
  fileName: string;
  sizeBytes: number;
  createdAt: string;
}

export interface BackupListResponse {
  backupDir: string;
  files: BackupFile[];
}

export interface BackupCreateResponse {
  file: BackupFile;
}

export interface DeleteBackupResponse {
  fileName: string;
  deleted: boolean;
}

export interface ImportDataResult {
  fileName: string;
  sourceType: 'DUMP' | 'ZIP' | string;
  preRestoreBackupName: string | null;
  importedAt: string;
}

/* ================== 公共系统状态 ================== */

export interface SystemStatus {
  status: string;
  maintenance: boolean;
  code: string | null;
  message: string | null;
  checkedAt: string;
}

export type ModelRankingBenchmark = 'programbench' | 'frontierbench';

export interface ModelRankingEntry {
  rank: number;
  model: string;
  organization: string;
  agent: string;
  score: number;
  secondaryScore?: number | null;
}

export interface ModelRankingSnapshot {
  benchmark: ModelRankingBenchmark;
  title: string;
  sourceUrl: string | null;
  fetchedAt: string | null;
  entries: ModelRankingEntry[];
}

export interface ModelRankingBenchmarkStatus {
  benchmark: ModelRankingBenchmark;
  title: string;
  sourceUrl: string | null;
  fetchedAt: string | null;
  entryCount: number;
  due: boolean;
}

export interface ModelRankingPublicConfig {
  enabled: boolean;
}

export interface ModelRankingSettings {
  enabled: boolean;
  remoteRefreshEnabled: boolean;
  refreshCron: string;
  maxEntries: number;
  programbenchUrl: string;
  frontierbenchUrl: string;
  lastError: string | null;
  benchmarks: ModelRankingBenchmarkStatus[];
}

export interface UpdateModelRankingSettingsRequest {
  enabled?: boolean;
  remoteRefreshEnabled?: boolean;
  maxEntries?: number;
  programbenchUrl?: string;
  frontierbenchUrl?: string;
  refreshNow?: boolean;
}

export type AppearancePreset = 'default' | 'custom';
export type AppearanceChrome = 'classic' | 'editorial';

export interface Appearance {
  siteName: string;
  logoUrl: string | null;
  faviconUrl: string | null;
  backgroundImageUrl: string | null;
  preset: AppearancePreset;
  primary: string;
  primaryMid: string;
  accent: string;
  success: string;
  warning: string;
  danger: string;
  header: string;
  text: string;
  textSecondary: string;
  textMuted: string;
  sidebarOpacity: number;
  surfaceOpacity: number;
  blurPx: number;
  backgroundBlurPx: number;
  chrome: AppearanceChrome;
}

export interface UpdateAppearanceRequest {
  siteName?: string;
  primary?: string;
  primaryMid?: string;
  accent?: string;
  success?: string;
  warning?: string;
  danger?: string;
  header?: string;
  text?: string;
  textSecondary?: string;
  textMuted?: string;
  sidebarOpacity?: number;
  surfaceOpacity?: number;
  blurPx?: number;
  backgroundBlurPx?: number;
  chrome?: AppearanceChrome;
  clearLogo?: boolean;
  clearFavicon?: boolean;
  clearBackground?: boolean;
  restoreDefault?: boolean;
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
  proxyChannelId?: string | null;
  poolTier: 'primary' | 'secondary' | string;
  poolWeight: number;
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
  badgeColor?: string | null;
}

export interface ChatGptOAuthStartRequest {
  accountId?: string;
  /** 登录过程（发起、轮询、换取令牌）经由该代理出口，生成的账号也绑定到它 */
  proxyChannelId?: string;
}

export interface ChatGptOAuthStartResult {
  loginId: string;
  verificationUrl: string;
  userCode: string;
  expiresAt: string;
  pollIntervalSeconds: number;
}

export type ChatGptOAuthStatus =
  | 'PENDING'
  | 'COMPLETING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'EXPIRED'
  | 'CANCELLED';

export interface ChatGptOAuthStatusResult {
  loginId: string;
  status: ChatGptOAuthStatus;
  message: string | null;
  retryAfterSeconds: number | null;
  account: Account | null;
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
  proxyChannelId?: string;
  allocationPolicy?: UpdateAccountAllocationPolicyRequest;
  poolTier?: 'primary' | 'secondary' | string;
  poolWeight?: number;
  quotaLimit?: number;
  quota5hLimit?: number;
  quota7dLimit?: number;
  badgeColor?: string | null;
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
    clearProxyChannel: boolean;
    enabled: boolean;
    quotaUsed: number;
    quotaResetAt: string;
    quota5hUsed: number;
    quota5hLimit: number;
    quota5hResetAt: string;
    quota7dUsed: number;
    quota7dLimit: number;
    quota7dResetAt: string;
    poolTier: 'primary' | 'secondary' | string;
    poolWeight: number;
  }
>;

export interface AccountAllocationPolicy {
  accountId: string;
  allocationPriority: number;
  safeConcurrency: number;
  safeRpm: number;
  burstCapacity: number;
  maxSessions: number;
  sessionIdleSeconds: number;
  maxInflightTokens: number;
  cooldownSeconds: number;
  tlsProfile: string;
  policyVersion: number;
  lifecycleState: string;
  stateVersion: number;
  warmupStage: number;
  cooldownUntil: string | null;
  recentErrorRate: number;
  recentLatencyMs: number;
  activeConcurrency: number;
  reservedInputTokens: number;
  reservedOutputTokens: number;
  updatedAt: string;
}

export type UpdateAccountAllocationPolicyRequest = Partial<
  Pick<
    AccountAllocationPolicy,
    | 'allocationPriority'
    | 'safeConcurrency'
    | 'safeRpm'
    | 'burstCapacity'
    | 'maxSessions'
    | 'sessionIdleSeconds'
    | 'maxInflightTokens'
    | 'cooldownSeconds'
    | 'tlsProfile'
  >
>;

export interface AccountSettings {
  gptFastModeEnabled: boolean;
  disabledAccountScheduledTest: DisabledAccountScheduledTestSettings;
  poolPolicy: AccountPoolPolicySettings;
  routingPolicy: AccountRoutingPolicySettings;
}

export interface UpdateAccountSettingsRequest {
  gptFastModeEnabled?: boolean;
  disabledAccountScheduledTest?: Partial<DisabledAccountScheduledTestSettings>;
  poolPolicy?: Partial<AccountPoolPolicySettings>;
  routingPolicy?: Partial<AccountRoutingPolicySettings>;
}

export interface AccountRoutingPolicySettings {
  isolateCodexPool: boolean;
  gptUsesCodexAccounts: boolean;
  bindSessionOnAdmission: boolean;
  bindSessionByApiKey: boolean;
  codexMaxRetryAccounts: number;
  openAiMaxRetryAccounts: number;
  deepSeekMaxRetryAccounts: number;
  anthropicMaxRetryAccounts: number;
  kiroMaxRetryAccounts: number;
}

export interface DisabledAccountScheduledTestSettings {
  enabled: boolean;
  scheduleMode: 'interval' | 'cron' | string;
  intervalMinutes: number;
  cronExpression: string;
  timezone: string;
  initialDelayMinutes: number;
  maxConcurrency: number;
  maxAccountsPerRun: number;
  model: string;
  prompt: string | null;
  updateStatus: boolean;
  autoRunWhenNoAvailableAccounts: boolean;
  includeDisabled: boolean;
  includeSuspended: boolean;
  includeQuotaExhausted: boolean;
  minAgeMinutes: number;
  providers: string[];
}

export interface AccountPoolPolicySettings {
  enabled: boolean;
  windowMinutes: number;
  minSampleSize: number;
  retestThresholdPercent: number;
  failoverThresholdPercent: number;
  recoveryThresholdPercent: number;
  retestIntervalMinutes: number;
  retestMaxConcurrency: number;
  autoWeightEnabled: boolean;
  circuitEpoch: string | null;
  activationStartedAt: string | null;
}

export interface AccountFailureRankingItem {
  accountId: string;
  email: string | null;
  provider: string | null;
  authMethod: string | null;
  poolTier: 'primary' | 'secondary' | string;
  requests: number;
  failures: number;
  failureRatePercent: number;
  errorCount: number;
  configuredWeight: number;
  effectiveWeight: number;
  lastFailureAt: string | null;
}

export interface AccountPoolStatus {
  enabled: boolean;
  accountCount: number;
  availableAccountCount: number;
  activeTier: 'primary' | 'secondary' | 'all' | string;
  primaryDisabled: boolean;
  primaryFailureRatePercent: number;
  primaryRequests: number;
  primaryFailures: number;
  windowStartedAt: string | null;
  lastEvaluatedAt: string | null;
  lastRetestAt: string | null;
  lastRetestFailureRatePercent: number;
  lastRetestRecoveryRatePercent: number;
  retestRunning: boolean;
  settings: AccountPoolPolicySettings;
  failureRanking: AccountFailureRankingItem[];
  modelPools: ModelPoolCircuitStatus[];
}

export type ModelPoolCircuitState = 'CLOSED' | 'OPEN' | 'HALF_OPEN';

/** 按模型供应商与模型族聚合的服务端原生熔断快照。 */
export interface ModelPoolCircuitStatus {
  key: string;
  provider: string;
  modelFamily: string;
  representativeModel: string | null;
  state: ModelPoolCircuitState;
  activeTier: 'primary' | 'secondary' | string;
  primaryRequests: number;
  primaryFailures: number;
  primaryFailureRatePercent: number;
  secondaryRequests: number;
  secondaryFailures: number;
  secondaryFailureRatePercent: number;
  primaryAccountCount: number;
  secondaryAccountCount: number;
  windowStartedAt: string | null;
  lastTransitionAt: string | null;
  openedAt: string | null;
  nextProbeAt: string | null;
  lastOutcomeAt: string | null;
  halfOpenInFlight: number;
  halfOpenCompleted: number;
  halfOpenSucceeded: number;
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

export interface CompatibleVendorModels {
  name: string;
  icon?: string | null;
  color?: string | null;
  models: TestModelOption[];
}

export interface TestModelOptionsResult {
  kiro: TestModelOption[];
  openAi: TestModelOption[];
  google: TestModelOption[];
  anthropic: TestModelOption[];
  codex: TestModelOption[];
  deepSeek: TestModelOption[];
  compatibleVendors?: CompatibleVendorModels[];
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
  billingSource: 'BALANCE' | 'CODING_PLAN';
  keyId: string;
  userId: string | null;
  name: string;
  key: string | null;
  keyPrefix: string;
  enabled: boolean;
  creditsLimit: number | null;
  modelProvider: string;
  totalRequests: number | null;
  totalCredits: number | null;
  totalInputTokens: number | null;
  totalOutputTokens: number | null;
  lastUsedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateApiKeyRequest {
  billingSource: 'BALANCE' | 'CODING_PLAN';
  name: string;
  creditsLimit?: number;
  modelProvider: string;
}

export interface UpdateApiKeyRequest {
  billingSource?: 'BALANCE' | 'CODING_PLAN';
  clearCreditsLimit?: boolean;
  name?: string;
  enabled?: boolean;
  creditsLimit?: number;
  modelProvider?: string;
}

export interface CreatedApiKey {
  billingSource: 'BALANCE' | 'CODING_PLAN';
  keyId: string;
  userId: string | null;
  name: string;
  /** 完整 sk-... 密钥 */
  key: string;
  keyPrefix: string;
  enabled: boolean;
  creditsLimit: number | null;
  modelProvider: string;
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
  scope?: 'model' | 'provider' | 'family';
  provider?: string | null;
  family?: string | null;
  enabled: boolean;
  modelPattern: string;
  priceMultiplier: number | null;
  inputMultiplier: number | null;
  outputMultiplier: number | null;
  cacheWriteMultiplier: number | null;
  cacheReadMultiplier: number | null;
  totalMultiplier: number | null;
  inputPricePerMillionTokens: number | null;
  outputPricePerMillionTokens: number | null;
  cacheWritePricePerMillionTokens: number | null;
  cacheReadPricePerMillionTokens: number | null;
  totalPricePerMillionTokens: number | null;
  priority: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ModelPricingSettings {
  rules: ModelPricingRule[];
  source?: ModelPricingSourceStatus | null;
}

export interface ModelPricingPreview {
  model: string;
  pricingModel: string;
  source: string;
  matchedPriceModel: string | null;
  multiplier: number | null;
  matchedRuleIds: string[];
  basePricing: ModelPublicPricing;
  pricing: ModelPublicPricing;
  /** 请求带了号池系数 factors 时才有，与 factors 同序：规则只命中一条，系数在它之后再乘 */
  factorPricing?: { factor: number; pricing: ModelPublicPricing }[] | null;
}

export interface ModelPricingSourceStatus {
  remoteRefreshEnabled: boolean;
  syncCatalogIds: boolean;
  sourceUrl: string;
  checksumUrl: string;
  refreshInterval?: string | null;
  catalogEntries: number;
  refreshedAt?: string | null;
  lastError?: string | null;
}

export interface UpdateModelPricingSourceRequest {
  remoteRefreshEnabled?: boolean;
  syncCatalogIds?: boolean;
  sourceUrl?: string;
  checksumUrl?: string;
  refreshNow?: boolean;
}

export interface UpdateModelPricingRuleRequest {
  ruleId?: string;
  scope?: 'model' | 'provider' | 'family';
  provider?: string | null;
  family?: string | null;
  enabled?: boolean;
  modelPattern: string;
  priceMultiplier?: number | null;
  inputMultiplier?: number | null;
  outputMultiplier?: number | null;
  cacheWriteMultiplier?: number | null;
  cacheReadMultiplier?: number | null;
  totalMultiplier?: number | null;
  inputPricePerMillionTokens?: number | null;
  outputPricePerMillionTokens?: number | null;
  cacheWritePricePerMillionTokens?: number | null;
  cacheReadPricePerMillionTokens?: number | null;
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
  /**
   * 号源列：只有按号源分级服务的请求的行才有（后端不输出为 null 的字段）。用户自己的日志只含级别、名称和系数，
   * 其余是管理员才看得到的号源细节。
   */
  sourceTierRef?: string | null;
  sourceTierLevel?: number | null;
  sourceTierName?: string | null;
  priceFactor?: number | null;
  /** AUTO 网关选号池；PINNED 客户端指定 */
  tierSelection?: string | null;
  /** 号源不是原生账号时，请求是怎么发给它的 */
  sourceDispatch?: string | null;
  wireModel?: string | null;
  usageSource?: string | null;
  stopReason?: string | null;
  degradedFeatures?: string | null;
}

export interface ActiveUserUsage {
  userId: string | null;
  username: string | null;
  displayName: string | null;
  email: string | null;
  requestCount: number;
  apiKeyCount: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  credits: number | null;
  lastActiveAt: string | null;
}

export interface ModelUsageSummary {
  model: string | null;
  requestCount: number;
  successfulRequests: number;
  failedRequests: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  credits: number | null;
  lastUsedAt: string | null;
}

export interface UsageSummary {
  totalRequests: number;
  successfulRequests: number;
  failedRequests: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  totalCredits: number | null;
  activeWindowStartedAt: string;
  activeUserCount: number;
  activeUsers: ActiveUserUsage[];
  models: ModelUsageSummary[];
}

export interface UsageDay {
  day: string;
  requestCount: number;
  successfulRequests: number;
  failedRequests: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  credits: number | null;
  peakRequests: number;
  offPeakRequests: number;
  peakCredits: number | null;
  offPeakCredits: number | null;
}

export interface UsageTracker {
  from: string;
  to: string;
  totalRequests: number;
  successfulRequests: number;
  failedRequests: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  totalCredits: number | null;
  peakRequests: number;
  offPeakRequests: number;
  peakCredits: number | null;
  offPeakCredits: number | null;
  models: ModelUsageSummary[];
  days: UsageDay[];
}

export interface RequestLogPage {
  items: RequestLog[];
  hasMore: boolean;
  nextBefore: string | null;
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
  billingMode?: 'total' | 'input_output' | string | null;
  tokensPer100Balance?: number | null;
  inputTokensPer100Balance?: number | null;
  outputTokensPer100Balance?: number | null;
  cacheWriteTokensPer100Balance?: number | null;
  cacheReadTokensPer100Balance?: number | null;
  totalPricePerMillionTokens?: number | null;
  inputPricePerMillionTokens?: number | null;
  outputPricePerMillionTokens?: number | null;
  cacheWritePricePerMillionTokens?: number | null;
  cacheReadPricePerMillionTokens?: number | null;
  icon?: string | null;
  requestFormat?: ModelRequestFormat;
  endpoint?: string;
}

export type ModelRequestFormat = 'OPENAI_CHAT_COMPLETIONS';

export interface ModelPublicPricing {
  billingMode: 'total' | 'input_output' | string;
  tokensPer100Balance?: number | null;
  inputTokensPer100Balance?: number | null;
  outputTokensPer100Balance?: number | null;
  cacheWriteTokensPer100Balance?: number | null;
  cacheReadTokensPer100Balance?: number | null;
  totalPricePerMillionTokens?: number | null;
  inputPricePerMillionTokens?: number | null;
  outputPricePerMillionTokens?: number | null;
  cacheWritePricePerMillionTokens?: number | null;
  cacheReadPricePerMillionTokens?: number | null;
}

export interface ManagedModel {
  id: string;
  name: string;
  icon?: string | null;
  provider: string;
  family: string;
  targetModelId: string;
  requestFormat: ModelRequestFormat;
  endpoint: string;
  contextWindow: number;
  description?: string | null;
  enabled: boolean;
  builtIn: boolean;
  badgeColor?: string | null;
  pricing?: ModelPublicPricing | null;
}

export interface UpsertManagedModelRequest {
  id: string;
  name: string;
  icon?: string | null;
  provider?: string;
  family?: string;
  targetModelId?: string;
  requestFormat: ModelRequestFormat;
  endpoint: string;
  contextWindow: number;
  description?: string | null;
  enabled: boolean;
  badgeColor?: string;
}

export interface ModelIconUploadResponse {
  url: string;
  fileName: string;
  sizeBytes: number;
}

export type ModelCatalogLayout = 'cards' | 'list' | 'compact';
export type ModelCatalogDensity = 'comfortable' | 'compact';
export type ModelCatalogGroupBy = 'provider' | 'none';
export type ModelCatalogCardStyle = 'default' | 'outline' | 'flat';

export interface ModelCatalogDisplaySettings {
  layout: ModelCatalogLayout;
  density: ModelCatalogDensity;
  columns: number;
  showIcon: boolean;
  showFamily: boolean;
  showContext: boolean;
  showOrigin: boolean;
  showDescription: boolean;
  groupBy: ModelCatalogGroupBy;
  cardStyle: ModelCatalogCardStyle;
}

export interface UpdateModelCatalogDisplayRequest extends Partial<ModelCatalogDisplaySettings> {
  restoreDefault?: boolean;
}

export interface ReorderManagedModelsRequest {
  modelIds?: string[];
  restoreDefault?: boolean;
}

export interface ModelCatalogViewSettings {
  display: ModelCatalogDisplaySettings;
  modelIds: string[];
}

export interface HealthInfo {
  status: string;
  service: string;
  uptime_ms: number;
}

/* ================== 支付 ================== */

export type PaymentType = 'alipay' | 'wxpay';

export type PaymentOrderStatus =
  | 'PENDING'
  | 'COMPLETED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'FAILED'
  | 'PAID';

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
  inviteRebateEnabled?: boolean | null;
  inviteRebateRate?: number | null;
  inviteRebateMaxOrders?: number | null;
  inviteRebateStartAt?: string | null;
  inviteRebateEndAt?: string | null;
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
  inviteRebateEnabled?: boolean;
  inviteRebateRate?: number;
  inviteRebateMaxOrders?: number;
  inviteRebateStartAt?: string | null;
  inviteRebateEndAt?: string | null;
}

export interface InviteRebateRecord {
  rebateId: string;
  affiliateCode: string;
  paymentOrderId: string;
  inviterUserId: string;
  invitedUserId: string;
  baseAmount: number;
  rebateRate: number;
  rebateAmount: number;
  rebateOrderNo: number;
  createdAt: string;
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
  creditedAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePaymentOrderResponse {
  order: PaymentOrder;
  payUrl: string | null;
  qrCode: string | null;
}

export interface CreateRedemptionCodeRequest {
  balance?: number | null;
  planId?: string | null;
  count?: number;
  expiresAt?: string | null;
}

export interface RedemptionCode {
  codeId: string;
  code: string;
  balance: number | null;
  planId: string | null;
  enabled: boolean;
  used: boolean;
  usedByUserId: string | null;
  usedByUsername: string | null;
  usedAt: string | null;
  expiresAt: string | null;
  createdByUserId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRedemptionCodesResponse {
  total: number;
  codes: RedemptionCode[];
}

export interface RedeemCodeRequest {
  code: string;
}

export interface RedeemCodeResponse {
  codeId: string;
  redeemedBalance: number | null;
  balance: number;
  subscriptionId: string | null;
  redeemedAt: string;
}

export interface CodingPlan {
  version: number;
  planId: string;
  name: string;
  description: string | null;
  priceCredits: string | number;
  durationDays: number;
  quotaCredits: string | number;
  allowedProviders: string[];
  sortOrder: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateCodingPlanRequest {
  name: string;
  description?: string | null;
  priceCredits: string | number;
  durationDays: number;
  quotaCredits: string | number;
  allowedProviders?: string[] | null;
  sortOrder?: number | null;
}

export interface UpdateCodingPlanRequest extends Partial<CreateCodingPlanRequest> {
  enabled?: boolean | null;
}

export interface CodingPlanSubscription {
  planVersion: number;
  reservedCredits: number;
  availableCredits: number;
  subscriptionId: string;
  userId: string;
  username: string | null;
  planId: string;
  planName: string;
  status: string;
  startsAt: string;
  endsAt: string;
  quotaCredits: number;
  usedCredits: number;
  remainingCredits: number;
  allowedProviders: string[];
  source: string;
  sourceRefId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PurchaseCodingPlanResponse {
  subscription: CodingPlanSubscription;
  balance: number;
}

export interface AdminGrantSubscriptionRequest {
  userId: string;
  planId: string;
}

export interface OpenAiQuotaWindowResponse {
  key: string;
  label: string;
  usedPercent: number;
  remainingPercent: number;
  resetAfterSeconds: number;
  resetAt: string;
  windowMinutes: number;
}

export interface OpenAiQuotaResponse {
  accountId: string;
  email: string;
  source: string;
  checkedAt: string;
  primaryOverSecondaryLimitPercent: number | null;
  primary: OpenAiQuotaWindowResponse | null;
  secondary: OpenAiQuotaWindowResponse | null;
  fiveHour: OpenAiQuotaWindowResponse | null;
  sevenDay: OpenAiQuotaWindowResponse | null;
}
