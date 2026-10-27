import http, { unwrap } from '@/utils/http';
import type { ApiResponse, ModelPublicPricing } from '@/types/api';
import type { AllocationCategory } from '@/api/allocationDefaults';

/**
 * 号源分级：把一个厂商的账号分成有序的号池（级别，1 最好），每个号池有名称和价格系数。
 * 对应后端 /admin/source-tiers，类型与 SourceTierDtos 一一对应；GET 读到的 Tier / Member / Gate
 * 原样用于 PUT，可选字段缺省（或 null）表示取默认值。后端不输出值为 null 的字段（Jackson 的
 * default-property-inclusion 是 non_null），所以可空字段读到时可能是缺省，写成 `?: T | null`。
 */

/** 跨协议号源（OpenAI 格式中转）的协议；本版本的后端只实现 OPENAI_CHAT */
export type SourceTierProtocol = 'OPENAI_CHAT' | 'OPENAI_RESPONSES' | 'ANTHROPIC_MESSAGES';
export type SourceTierEntryEndpoint = 'CHAT' | 'MESSAGES' | 'RESPONSES' | 'GEMINI';
export type SourceTierGateState = 'OFF' | 'KEYS' | 'ON';
/** NOT_OFFERED 未提供；EXPERIMENTAL 只能给白名单 Key；VERIFIED 可以对所有 Key 打开 */
export type SourceTierPairStatusName = 'NOT_OFFERED' | 'EXPERIMENTAL' | 'VERIFIED';

export interface SourceTierPairStatus {
  vendor: string;
  sourceProtocol: SourceTierProtocol;
  entryEndpoint: SourceTierEntryEndpoint;
  status: SourceTierPairStatusName;
}

/**
 * 当前版本能运行什么。需要某项能力才能生效的设置（enabled 需要 nativeTiers，unifyEndpoints 需要同名能力，
 * 跨协议成员行和非 OFF 的门控需要 xpMembers）在能力为假时被拒绝（409 SOURCE_TIER_RUNTIME_UNSUPPORTED），
 * 不会出现先存后静默生效的配置。
 */
export interface SourceTierRuntime {
  nativeTiers: boolean;
  unifyEndpoints: boolean;
  xpMembers: boolean;
  pairs: SourceTierPairStatus[];
  /** 客户端能否指定号池（`模型@标识` 写法）；不影响任何设置能否保存，旧版本的响应里没有这一项 */
  selectors?: boolean;
}

export interface SourceTier {
  /** 小写字母、数字和连字符（最多 32 位）；不能是 auto、L2 或纯数字，它们是选号写法 */
  tierId: string;
  /** 1 到 9，1 最好 */
  level: number;
  name: string;
  /** 大于 0、至多 1000、至多 6 位小数 */
  priceFactor: number;
  memberCategories: AllocationCategory[];
  userSelectable: boolean;
  enabled: boolean;
  description?: string | null;
}

export interface SourceTierMemberModel {
  modelId: string;
  upstreamModel: string;
  maxContextTokens: number;
  imageTokenCap?: number | null;
  enabled?: boolean | null;
  /** 只读：映射验证通过的时间；设置、账号提供方或 base URL 变化后失效 */
  verifiedAt?: string | null;
  verified?: boolean | null;
}

/**
 * 手动放进某个号池的账号。没有 protocol 时只把原生账号固定到 tierId（其余字段不适用，读出为 null）；
 * 有 protocol 时登记一个 OpenAI 兼容 Key 作为别的厂商模型的中转。
 */
export interface SourceTierMember {
  accountId: string;
  tierId: string;
  protocol?: SourceTierProtocol | null;
  exclusive?: boolean | null;
  vision?: boolean | null;
  jsonSchema?: boolean | null;
  priorityTier?: boolean | null;
  cacheControlPassthrough?: boolean | null;
  upstreamStream?: boolean | null;
  reasoningMode?: 'NONE' | 'EFFORT' | null;
  maxDurationSeconds?: number | null;
  usageCacheReadInPrompt?: boolean | null;
  usageCacheWriteInPrompt?: boolean | null;
  models?: SourceTierMemberModel[] | null;
}

export interface SourceTierGate {
  sourceProtocol?: SourceTierProtocol | null;
  entryEndpoint: SourceTierEntryEndpoint;
  state: SourceTierGateState;
  allowKeyIds?: string[] | null;
}

/** 已保存配置里允许、但多半不是本意的情况；code 决定控制台怎么措辞，message 是后端的英文原文 */
export interface SourceTierWarning {
  code:
    | 'LOWEST_TIER_COLLECTS_OFFICIAL_KEYS'
    | 'MEMBER_NOT_SERVING_VENDOR'
    | 'PIN_ON_EXCLUSIVE_RELAY'
    | string;
  tierId?: string | null;
  count: number;
  accountIds: string[];
  message: string;
}

export interface SourceTierVendor {
  vendor: string;
  label: string;
  /** false：这个厂商从未配置过（version 为 0，开关取默认值，没有号池） */
  configured: boolean;
  /** 保存时作为 expectedVersion 带回；期间被别人改过则返回 409 SOURCE_TIER_VERSION_CONFLICT */
  version: number;
  enabled: boolean;
  unifyEndpoints: boolean;
  publicListing: boolean;
  stickySessions: boolean;
  refusalCap: number;
  crossTierReservedSlot: boolean;
  tiers: SourceTier[];
  members: SourceTierMember[];
  gates: SourceTierGate[];
  warnings: SourceTierWarning[];
  /** 已保存配置里每个号池（按 tierId）收下的启用账号数，含指定、按类别和落入最低级的；没有账号的号池缺省 */
  tierAccounts?: Record<string, number> | null;
}

export interface SourceTierOverview {
  /** 所有厂商的配置共用的版本，各节点据此刷新 */
  configVersion: number;
  runtime: SourceTierRuntime;
  vendors: SourceTierVendor[];
}

/** 整体替换一个厂商的配置：请求里没有的号池、成员、门控都会被删除 */
export interface ReplaceSourceTierVendor {
  expectedVersion: number;
  enabled: boolean;
  unifyEndpoints?: boolean;
  publicListing?: boolean;
  stickySessions?: boolean;
  /** 4 到 64，缺省 16 */
  refusalCap?: number;
  crossTierReservedSlot?: boolean;
  tiers: SourceTier[];
  members: SourceTierMember[];
  gates: SourceTierGate[];
}

/** 不重发整份配置，只增改、移除成员行（账号列表批量设置号池、中转对话框保存用） */
export interface UpsertSourceTierMembers {
  expectedVersion: number;
  members?: SourceTierMember[];
  removeAccountIds?: string[];
}

/**
 * 运行状态（GET /admin/source-tiers/status）：应答节点此刻的账号状态，加上最近 windowMinutes 分钟里请求怎么被服务的。
 * 账号数是这个节点的（账号缓存最多晚 30 秒），服务 / 失败次数取自请求日志，覆盖所有节点。
 */
export interface SourceTierStatus {
  at: string;
  configVersion: number;
  windowMinutes: number;
  /** 只含有号池的厂商 */
  vendors: SourceTierVendorStatus[];
}

export interface SourceTierVendorStatus {
  vendor: string;
  label: string;
  enabled: boolean;
  /** 厂商已启用、当前版本能运行分级、且至少有一个号池启用：请求才会按号池服务 */
  serving: boolean;
  /** 窗口里由网关自己选号池服务的请求数 */
  autoServed: number;
  /** 其中由低于「已启用的最好一级」服务的请求数 */
  degraded: number;
  tiers: SourceTierTierStatus[];
  /** 这个厂商登记了中转（跨协议成员）时才有：中转的层、对的门控与指标、本节点跳过中转的原因 */
  xp?: SourceTierXpStatus | null;
}

export interface SourceTierTierStatus {
  tierId: string;
  level: number;
  name: string;
  enabled: boolean;
  /** 这个节点现在会从该号池服务：厂商在服务、号池已启用、至少一个账号就绪 */
  serves: boolean;
  accounts: SourceTierAccountLayers;
  /** 窗口里该号池服务成功的尝试数，其中 servedPinned 是客户端指定号池的 */
  served: number;
  servedPinned: number;
  /** 窗口里派发到该号池并失败的尝试数 */
  failed: number;
}

/** 号池收下的每个账号只计入第一个让它不能服务的层 */
export interface SourceTierAccountLayers {
  disabled: number;
  unavailable: number;
  resting: number;
  busy: number;
  ready: number;
  /** 只有中转有这一层：没有任何入口的门控对它开放，或没有已验证的映射；号池里没有中转时缺省 */
  unopened?: number | null;
}

/** 中转所在的层：同 SourceTierAccountLayers 的字段名 */
export type SourceTierRelayLayer =
  | 'disabled'
  | 'unopened'
  | 'unavailable'
  | 'resting'
  | 'busy'
  | 'ready';

/**
 * 一个厂商的中转（跨协议成员）在状态里的部分。指标取自请求日志（覆盖所有节点）；skips 是应答节点自己的计数，
 * 自 skipsSince 起，数的是被跳过的中转次数而不是请求数（被跳过的中转在日志里没有痕迹）。
 */
export interface SourceTierXpStatus {
  skipsSince: string;
  relays: SourceTierXpRelay[];
  pairs: SourceTierXpPair[];
  skips: SourceTierXpSkipCount[];
}

export interface SourceTierXpRelay {
  tierId: string;
  accountId: string;
  layer: SourceTierRelayLayer;
  /** 已启用的映射数，和其中已验证的 */
  mappings: number;
  verified: number;
  figures: SourceTierXpFigures;
}

export interface SourceTierXpPair {
  sourceProtocol: SourceTierProtocol;
  entryEndpoint: SourceTierEntryEndpoint;
  status: SourceTierPairStatusName;
  gate: SourceTierGateState;
  allowedKeys: number;
  figures: SourceTierXpFigures;
  /** 窗口里被服务的尝试丢掉的请求特性，从多到少 */
  degradedFeatures: SourceTierFeatureCount[];
}

/**
 * 发给中转的尝试，取自请求日志。interrupted 是中转可能已经生成、没人为它付费的中断（设计里的 UNBILLED，由运营者承担），
 * conversionRejected 是中转拒绝了转换后的请求，keyRefused 是中转拒绝了账号自己的 Key（请求去了别处，账号歇一会儿），
 * withheld 是中转答了、网关把应答扣下的（Responses 入口上被过滤或在网关自己的输出上限处被截断的应答：没人为它付费，
 * 不是中转的故障，所以不算在 interrupted 里）；failed 里其余的是别的失败。estimated、clamped 是用量不是中转报的那样的
 * 已服务尝试，degraded 是丢掉了请求特性的已服务尝试。p95Ms 是已服务尝试延迟的第 95 百分位，没有已服务的尝试时缺省。
 */
export interface SourceTierXpFigures {
  attempts: number;
  served: number;
  failed: number;
  interrupted: number;
  conversionRejected: number;
  contextExceeded: number;
  modelNotServed: number;
  keyRefused: number;
  withheld: number;
  estimated: number;
  clamped: number;
  degraded: number;
  p95Ms?: number | null;
}

export interface SourceTierFeatureCount {
  feature: string;
  count: number;
}

/** reason 取 GATE、NOT_A_RELAY、NO_MAPPING、UNVERIFIED、FEATURE、CONVERSION、CONTEXT、INTERRUPTED */
export interface SourceTierXpSkipCount {
  tierId: string;
  reason: string;
  count: number;
}

/**
 * 升级一个对为 VERIFIED 的证据（GET /admin/source-tiers/evidence）：每个这个版本提供的对在最近 days 天请求日志里的
 * 数字，和标准是否达到。lintSkips 只有应答的节点自己数（自 skipsSince 起）。
 */
export interface SourceTierEvidence {
  at: string;
  since: string;
  days: number;
  configVersion: number;
  skipsSince: string;
  pairs: SourceTierPairEvidence[];
}

export interface SourceTierPairEvidence {
  vendor: string;
  label: string;
  sourceProtocol: SourceTierProtocol;
  entryEndpoint: SourceTierEntryEndpoint;
  status: SourceTierPairStatusName;
  gate: SourceTierGateState;
  allowedKeys: number;
  figures: SourceTierXpFigures;
  lintSkips: number;
  criteria: SourceTierCriterion[];
  meetsStandard: boolean;
  degradedFeatures: SourceTierFeatureCount[];
  members: SourceTierMemberEvidence[];
}

/**
 * name 取 requests、lintFailures、conversionRejected、estimated、unbilled；value 是个数或百分比，没有可除的数时缺省，
 * lintFailures 在应答节点没有运行满窗口（重启或升级发生在窗口里）、数不全时也缺省，并算未达标
 */
export interface SourceTierCriterion {
  name: string;
  rule: string;
  value?: number | null;
  met: boolean;
}

export interface SourceTierMemberEvidence {
  tierId: string;
  accountId: string;
  figures: SourceTierXpFigures;
}

/** 每个账号所在的号池（GET /admin/source-tiers/placements），供账号列表的「号源级别」列使用；不在任何号池的账号不出现 */
export interface SourceTierPlacements {
  configVersion: number;
  accounts: SourceTierAccountPlacement[];
}

export interface SourceTierAccountPlacement {
  accountId: string;
  tiers: SourceTierPlaced[];
}

/** rule：MEMBER_ROW 手动指定；CATEGORY 按类别；LOWEST_TIER 没有号池列出它的类别，落入最低级 */
export type SourceTierPlacementRule = 'MEMBER_ROW' | 'CATEGORY' | 'LOWEST_TIER';

export interface SourceTierPlaced {
  vendor: string;
  vendorLabel: string;
  tierId: string;
  level: number;
  name: string;
  rule: SourceTierPlacementRule;
  /** 登记为别的协议的中转 */
  relay: boolean;
  /** 现在是否按这个号池服务（厂商已启用、号池已启用、版本能运行） */
  active: boolean;
}

/**
 * 用户给 API Key 选号源时能选的号池（GET /admin/source-tiers/me?provider=）：只有名称、级别、系数和价格，
 * 不含账号、类别或协议。tiers 为空表示这个厂商没有启用分级，或管理员没有公开号池名称（管理员本人总能看到）。
 * pricing 是 referenceModel（这个厂商模型列表里第一个有价格的模型）乘上各号池系数后的价格，同 /v1/models 的写法；
 * 两者都可能缺省。
 */
export interface SourceTierChoices {
  provider: string;
  referenceModel?: string | null;
  tiers: SourceTierChoice[];
}

export interface SourceTierChoice {
  /** Key 偏好、请求头和模型后缀都用它指号池 */
  id: string;
  level: number;
  name: string;
  priceFactor: number;
  pricing?: ModelPublicPricing | null;
}

/** 验证一个中转的映射（POST …/members/{accountId}/verify）；整个请求体可省略 */
export interface SourceTierVerifyRequest {
  /** 要验证的目录模型，至多 8 个；缺省时验证已启用的映射（它们须不多于 8 个） */
  modelIds?: string[];
  /** 额外跑缓存探针：每个映射问两次约 1,200 令牌的提示，费用由运营者承担 */
  cacheProbe?: boolean;
}

export interface SourceTierVerifyResult {
  vendor: string;
  accountId: string;
  at: string;
  /** 这次验证的每个映射现在都算已验证 */
  verified: boolean;
  models: SourceTierModelVerification[];
}

/** stale：验证期间映射被改过，探针说的不是现在保存的配置，什么都没写 */
export interface SourceTierModelVerification {
  modelId: string;
  upstreamModel: string;
  verified: boolean;
  stale: boolean;
  probes: SourceTierProbeResult[];
  cache?: SourceTierCacheAdvice | null;
}

/** name 取 basic、tool_call、tool_loop、stream、vision、cache、cache_evidence；message 只有网关自己的话 */
export interface SourceTierProbeResult {
  name: string;
  status: 'PASSED' | 'FAILED' | 'SKIPPED' | string;
  message?: string | null;
  latencyMs: number;
  promptTokens?: number | null;
  completionTokens?: number | null;
}

/**
 * 缓存探针对中转缓存的判断：readInPrompt / writeInPrompt 说中转的 prompt_tokens 是否已含缓存读 / 写，
 * 数字不足以判断时为 null（message 说明原因）。「不含」的设置只有这里是 false 才能保存。
 */
export interface SourceTierCacheAdvice {
  readInPrompt?: boolean | null;
  writeInPrompt?: boolean | null;
  readEvidence?: string | null;
  writeEvidence?: string | null;
  message?: string | null;
  calls: SourceTierCacheCall[];
}

export interface SourceTierCacheCall {
  promptTokens?: number | null;
  cacheReadTokens: number;
  cacheWriteTokens: number;
}

/** 中转自己列出的模型（GET …/upstream-models），contextLength 是它写的窗口，没写时缺省 */
export interface SourceTierUpstreamModels {
  models: SourceTierUpstreamModel[];
  truncated: boolean;
}

export interface SourceTierUpstreamModel {
  id: string;
  contextLength?: number | null;
}

/** 后端的业务错误码：控制台对它们有专门的提示 */
export const SOURCE_TIER_VERSION_CONFLICT = 'SOURCE_TIER_VERSION_CONFLICT';
export const SOURCE_TIER_RUNTIME_UNSUPPORTED = 'SOURCE_TIER_RUNTIME_UNSUPPORTED';
export const SOURCE_TIER_CACHE_EVIDENCE_REQUIRED = 'SOURCE_TIER_CACHE_EVIDENCE_REQUIRED';
export const SOURCE_VERIFY_RATE_LIMITED = 'SOURCE_VERIFY_RATE_LIMITED';
export const SOURCE_TIER_MEMBER_NOT_FOUND = 'SOURCE_TIER_MEMBER_NOT_FOUND';
export const SOURCE_UPSTREAM_MODELS_FAILED = 'SOURCE_UPSTREAM_MODELS_FAILED';

/** 一次验证最长 4 分钟，请求的等待要比它长 */
const VERIFY_TIMEOUT_MS = 270_000;

export const sourceTiersApi = {
  overview: () =>
    unwrap<SourceTierOverview>(http.get<ApiResponse<SourceTierOverview>>('/admin/source-tiers')),
  replace: (vendor: string, payload: ReplaceSourceTierVendor) =>
    unwrap<SourceTierVendor>(
      http.put<ApiResponse<SourceTierVendor>>(
        `/admin/source-tiers/${encodeURIComponent(vendor)}`,
        payload,
      ),
    ),
  status: () =>
    unwrap<SourceTierStatus>(http.get<ApiResponse<SourceTierStatus>>('/admin/source-tiers/status')),
  /** 给 API Key 选号源时的可选号池；读取失败由调用方自己处理（这只是附加选项） */
  choices: (provider: string) =>
    unwrap<SourceTierChoices>(
      http.get<ApiResponse<SourceTierChoices>>('/admin/source-tiers/me', { params: { provider } }),
    ),
  placements: () =>
    unwrap<SourceTierPlacements>(
      http.get<ApiResponse<SourceTierPlacements>>('/admin/source-tiers/placements'),
    ),
  upsertMembers: (vendor: string, payload: UpsertSourceTierMembers) =>
    unwrap<SourceTierVendor>(
      http.post<ApiResponse<SourceTierVendor>>(
        `/admin/source-tiers/${encodeURIComponent(vendor)}/members`,
        payload,
      ),
    ),
  /** 验证一个中转的映射：探针由运营者付费，同一个账号 30 秒只能验证一次，整体最长 4 分钟 */
  verify: (vendor: string, accountId: string, payload: SourceTierVerifyRequest = {}) =>
    unwrap<SourceTierVerifyResult>(
      http.post<ApiResponse<SourceTierVerifyResult>>(
        `/admin/source-tiers/${encodeURIComponent(vendor)}/members/${encodeURIComponent(accountId)}/verify`,
        payload,
        { timeout: VERIFY_TIMEOUT_MS },
      ),
    ),
  /** 经账号的出口向中转取它列出的模型，给映射表预填；账号不必已经是成员 */
  upstreamModels: (vendor: string, accountId: string) =>
    unwrap<SourceTierUpstreamModels>(
      http.get<ApiResponse<SourceTierUpstreamModels>>(
        `/admin/source-tiers/${encodeURIComponent(vendor)}/members/${encodeURIComponent(accountId)}/upstream-models`,
      ),
    ),
  /** 升级对为 VERIFIED 的证据：最近 days 天（1 到 90） */
  evidence: (days: number) =>
    unwrap<SourceTierEvidence>(
      http.get<ApiResponse<SourceTierEvidence>>('/admin/source-tiers/evidence', {
        params: { days },
      }),
    ),
};
