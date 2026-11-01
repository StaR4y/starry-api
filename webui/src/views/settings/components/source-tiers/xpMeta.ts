import { t } from '@/i18n';
import type { Account, AccountAllocationPolicy, ManagedModel } from '@/types/api';
import type {
  SourceTierCacheAdvice,
  SourceTierCriterion,
  SourceTierEntryEndpoint,
  SourceTierEvidence,
  SourceTierGate,
  SourceTierGateState,
  SourceTierMember,
  SourceTierMemberModel,
  SourceTierModelVerification,
  SourceTierPairStatusName,
  SourceTierProbeResult,
  SourceTierRuntime,
  SourceTierUpstreamModel,
  SourceTierXpFigures,
} from '@/api/sourceTiers';
import { unsupportedMessage } from './sourceTiersMeta';
import { vendorOfProvider } from './tierStatusMeta';

/**
 * 跨协议成员（中转）、映射和门控的草稿模型与纯函数。
 *
 * 中转是一把 OpenAI 兼容 Key，被登记为别的厂商某个号池的成员：把该厂商的模型（目录模型）映射到中转自己的模型名，
 * 验证过的映射才会被请求用到。门控按（入口，厂商）对决定哪些 Key 的请求可以派给中转。与界面无关的计算都放在这里，
 * 便于单独测试；后端仍是最终裁判。
 */

/** 中转目前只有 OpenAI Chat 一种协议 */
export const RELAY_PROTOCOL = 'OPENAI_CHAT';

/** 能登记中转的厂商：其余厂商本来就把兼容 Key 当原生账号用（设计 7.3） */
export const RELAY_VENDORS = ['anthropic', 'openai', 'google'];

export function supportsRelays(vendor: string): boolean {
  return RELAY_VENDORS.includes(vendor);
}

export const ENTRY_ENDPOINTS: SourceTierEntryEndpoint[] = [
  'CHAT',
  'MESSAGES',
  'RESPONSES',
  'GEMINI',
];

/** 与后端 SourceTierValidator 一致的取值范围 */
export const MIN_DURATION_SECONDS = 30;
export const MAX_DURATION_SECONDS = 900;
export const DEFAULT_DURATION_SECONDS = 540;
export const DEFAULT_IMAGE_TOKEN_CAP = 8192;
export const MAX_IMAGE_TOKEN_CAP = 1_000_000;
export const MAX_OUTPUT_TOKENS = 1_000_000;
/** 映射没写最大输出时，客户端没写输出上限的 Responses 请求发给中转的输出上限 */
export const FALLBACK_OUTPUT_TOKENS = 4096;
export const MAX_GATE_KEYS = 50;
/** 一次验证最多探测的映射数 */
export const MAX_VERIFIED_MAPPINGS = 8;
/** 网关等待一次不流式的应答的读超时（STARRY_REQUEST_TIMEOUT 的默认值）；单次时限超过它，不流式的中转会先被它切断 */
export const DEFAULT_READ_TIMEOUT_SECONDS = 300;

/** 中转的模型名原样发给中转：与后端校验同一个规则，没有通配 */
const UPSTREAM_MODEL = /^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,254}$/;

let keySeed = 0;

export function newMappingKey(): string {
  keySeed += 1;
  return `map-row-${keySeed}`;
}

/* ---------- 草稿 ---------- */

export interface MappingDraft {
  /** 仅用于列表渲染：模型可以改，不能当 v-for 的键 */
  key: string;
  modelId: string;
  upstreamModel: string;
  /** 必填；还没有填时为 null */
  maxContextTokens: number | null;
  imageTokenCap: number;
  /** 可选：中转的上游给这个模型写的最大输出；没填为 null */
  maxOutputTokens: number | null;
  enabled: boolean;
  /** 保存的配置里这一行现在是否算已验证（只读，来自后端） */
  verified: boolean;
  verifiedAt: string | null;
}

export interface RelayDraft {
  accountId: string;
  tierId: string;
  exclusive: boolean;
  vision: boolean;
  jsonSchema: boolean;
  priorityTier: boolean;
  cacheControlPassthrough: boolean;
  upstreamStream: boolean;
  reasoningMode: 'NONE' | 'EFFORT';
  maxDurationSeconds: number;
  /** 中转的 prompt_tokens 是否已含缓存读 / 写；「含」是安全的缺省，配成「不含」要缓存探针的证据 */
  usageCacheReadInPrompt: boolean;
  usageCacheWriteInPrompt: boolean;
  mappings: MappingDraft[];
}

export function newMapping(modelId = '', upstreamModel = ''): MappingDraft {
  return {
    key: newMappingKey(),
    modelId,
    upstreamModel,
    maxContextTokens: null,
    imageTokenCap: DEFAULT_IMAGE_TOKEN_CAP,
    maxOutputTokens: null,
    enabled: true,
    verified: false,
    verifiedAt: null,
  };
}

/** 新登记的中转：专用、上游流式、「含缓存」，没有任何特性开关 */
export function newRelayDraft(accountId = '', tierId = ''): RelayDraft {
  return {
    accountId,
    tierId,
    exclusive: true,
    vision: false,
    jsonSchema: false,
    priorityTier: false,
    cacheControlPassthrough: false,
    upstreamStream: true,
    reasoningMode: 'NONE',
    maxDurationSeconds: DEFAULT_DURATION_SECONDS,
    usageCacheReadInPrompt: true,
    usageCacheWriteInPrompt: true,
    mappings: [],
  };
}

function mappingDraft(model: SourceTierMemberModel): MappingDraft {
  return {
    key: newMappingKey(),
    modelId: model.modelId,
    upstreamModel: model.upstreamModel,
    maxContextTokens: model.maxContextTokens,
    imageTokenCap: model.imageTokenCap ?? DEFAULT_IMAGE_TOKEN_CAP,
    maxOutputTokens: model.maxOutputTokens ?? null,
    enabled: model.enabled !== false,
    verified: !!model.verified,
    verifiedAt: model.verifiedAt ?? null,
  };
}

/** 已保存的中转成员对应的草稿；后端不输出为 null 的字段，缺省取与后端相同的默认值 */
export function relayDraftOf(member: SourceTierMember): RelayDraft {
  return {
    accountId: member.accountId,
    tierId: member.tierId,
    exclusive: member.exclusive !== false,
    vision: !!member.vision,
    jsonSchema: !!member.jsonSchema,
    priorityTier: !!member.priorityTier,
    cacheControlPassthrough: !!member.cacheControlPassthrough,
    upstreamStream: member.upstreamStream !== false,
    reasoningMode: member.reasoningMode === 'EFFORT' ? 'EFFORT' : 'NONE',
    maxDurationSeconds: member.maxDurationSeconds ?? DEFAULT_DURATION_SECONDS,
    // 两个开关后端要求都有；没有就当作「含」，那是不会多收的一边
    usageCacheReadInPrompt: member.usageCacheReadInPrompt !== false,
    usageCacheWriteInPrompt: member.usageCacheWriteInPrompt !== false,
    mappings: (member.models ?? [])
      .slice()
      .sort((a, b) => a.modelId.localeCompare(b.modelId))
      .map(mappingDraft),
  };
}

/** 草稿对应的成员行（POST …/members 的一项）；验证状态是只读的，不发 */
export function relayMemberOf(draft: RelayDraft): SourceTierMember {
  return {
    accountId: draft.accountId,
    tierId: draft.tierId,
    protocol: RELAY_PROTOCOL,
    exclusive: draft.exclusive,
    vision: draft.vision,
    jsonSchema: draft.jsonSchema,
    priorityTier: draft.priorityTier,
    cacheControlPassthrough: draft.cacheControlPassthrough,
    upstreamStream: draft.upstreamStream,
    reasoningMode: draft.reasoningMode,
    maxDurationSeconds: draft.maxDurationSeconds,
    usageCacheReadInPrompt: draft.usageCacheReadInPrompt,
    usageCacheWriteInPrompt: draft.usageCacheWriteInPrompt,
    models: draft.mappings.map((mapping) => ({
      modelId: mapping.modelId.trim(),
      upstreamModel: mapping.upstreamModel,
      maxContextTokens: mapping.maxContextTokens ?? 0,
      imageTokenCap: mapping.imageTokenCap,
      maxOutputTokens: mapping.maxOutputTokens,
      enabled: mapping.enabled,
    })),
  };
}

/** 判断草稿有没有改动：与打开时的签名比较（验证状态不算改动） */
export function relaySignature(draft: RelayDraft): string {
  return JSON.stringify(relayMemberOf(draft));
}

/* ---------- 校验 ---------- */

export interface MappingProblems {
  model?: string;
  upstream?: string;
  context?: string;
  image?: string;
  output?: string;
}

/** 每一行映射（按 key）当前的问题，与后端 SourceTierValidator.model 的规则一致 */
export function mappingProblems(mappings: MappingDraft[]): Record<string, MappingProblems> {
  const result: Record<string, MappingProblems> = {};
  const seen = new Map<string, number>();
  for (const mapping of mappings) {
    const id = mapping.modelId.trim().toLowerCase();
    seen.set(id, (seen.get(id) ?? 0) + 1);
  }
  for (const mapping of mappings) {
    const problems: MappingProblems = {};
    const id = mapping.modelId.trim();
    if (!id) problems.model = t('选择一个目录模型');
    else if (id.length > 256) problems.model = t('模型最多 256 个字符');
    else if ((seen.get(id.toLowerCase()) ?? 0) > 1) problems.model = t('这个模型映射了不止一次');
    if (!UPSTREAM_MODEL.test(mapping.upstreamModel)) {
      problems.upstream = mapping.upstreamModel
        ? t(
            '中转的模型名只能用字母、数字和 . _ : / @ + -，以字母或数字开头，最多 255 个字符，不能有通配',
          )
        : t('填写中转给这个模型起的名字');
    }
    if (
      typeof mapping.maxContextTokens !== 'number' ||
      !Number.isInteger(mapping.maxContextTokens) ||
      mapping.maxContextTokens < 1
    ) {
      problems.context = t('上下文上限必填，且要大于 0');
    }
    if (
      !Number.isInteger(mapping.imageTokenCap) ||
      mapping.imageTokenCap < 1 ||
      mapping.imageTokenCap > MAX_IMAGE_TOKEN_CAP
    ) {
      problems.image = t('图片上限要在 1 到 {p0} 之间', { p0: MAX_IMAGE_TOKEN_CAP });
    }
    if (
      mapping.maxOutputTokens !== null &&
      (!Number.isInteger(mapping.maxOutputTokens) ||
        mapping.maxOutputTokens < 1 ||
        mapping.maxOutputTokens > MAX_OUTPUT_TOKENS)
    ) {
      problems.output = t('最大输出要在 1 到 {p0} 之间，或者不填', { p0: MAX_OUTPUT_TOKENS });
    }
    if (
      problems.model ||
      problems.upstream ||
      problems.context ||
      problems.image ||
      problems.output
    ) {
      result[mapping.key] = problems;
    }
  }
  return result;
}

/** 草稿里第一个会被后端拒绝的问题；没有则返回 null */
export function relayProblem(draft: RelayDraft): string | null {
  if (!draft.accountId) return t('选择要登记为中转的账号');
  if (!draft.tierId) return t('选择中转所在的号池');
  if (
    !Number.isInteger(draft.maxDurationSeconds) ||
    draft.maxDurationSeconds < MIN_DURATION_SECONDS ||
    draft.maxDurationSeconds > MAX_DURATION_SECONDS
  ) {
    return t('单次时限要在 {p0} 到 {p1} 秒之间', {
      p0: MIN_DURATION_SECONDS,
      p1: MAX_DURATION_SECONDS,
    });
  }
  if (!draft.mappings.length) return t('至少需要一条映射');
  const problems = mappingProblems(draft.mappings);
  for (const [index, mapping] of draft.mappings.entries()) {
    const found = problems[mapping.key];
    const message =
      found?.model ?? found?.upstream ?? found?.context ?? found?.image ?? found?.output;
    if (message) return t('第 {p0} 条映射：{p1}', { p0: index + 1, p1: message });
  }
  return null;
}

/* ---------- 验证状态 ---------- */

/**
 * 验证作废的设置：映射的验证只对验证时的设置、目录模型和中转给它的名字有效（账号的 provider 和 base URL 也算，
 * 但那在账号页改）。保存会让这些设置变了的映射变回未验证；返回会失效的映射的目录模型，没有则为空。
 */
export function invalidatedMappings(
  saved: SourceTierMember | undefined,
  draft: RelayDraft,
): string[] {
  if (!saved) return [];
  const before = relayDraftOf(saved);
  const settings = (value: RelayDraft) =>
    JSON.stringify([
      value.vision,
      value.jsonSchema,
      value.priorityTier,
      value.cacheControlPassthrough,
      value.upstreamStream,
      value.reasoningMode,
      value.usageCacheReadInPrompt,
      value.usageCacheWriteInPrompt,
    ]);
  const settingsChanged = settings(before) !== settings(draft);
  const wireNames = new Map(
    before.mappings.map((mapping) => [mapping.modelId.toLowerCase(), mapping.upstreamModel]),
  );
  return draft.mappings
    .filter((mapping) => {
      const earlier = wireNames.get(mapping.modelId.trim().toLowerCase());
      return earlier !== undefined && (settingsChanged || earlier !== mapping.upstreamModel);
    })
    .map((mapping) => mapping.modelId.trim());
}

export type MappingStatus = 'verified' | 'unverified' | 'off' | 'new';

/** 一行映射现在的验证状态：未保存的是 new；关闭的不进计划；其余看后端说它是否已验证 */
export function mappingStatus(mapping: MappingDraft, saved: RelayDraft | null): MappingStatus {
  const before = saved?.mappings.find(
    (candidate) => candidate.modelId.toLowerCase() === mapping.modelId.trim().toLowerCase(),
  );
  if (!before) return 'new';
  if (!mapping.enabled) return 'off';
  return before.verified ? 'verified' : 'unverified';
}

export function mappingStatusLabel(status: MappingStatus): string {
  switch (status) {
    case 'verified':
      return t('已验证');
    case 'unverified':
      return t('未验证');
    case 'off':
      // The mapping is switched off in its row; "已关闭" reads "Closed" and "关闭" "Close" in English.
      return t('已停用');
    default:
      return t('未保存');
  }
}

export function mappingStatusTone(status: MappingStatus): 'success' | 'warning' | 'info' {
  if (status === 'verified') return 'success';
  return status === 'unverified' ? 'warning' : 'info';
}

/** 验证一个中转前的限制：至多 8 个映射一起验证 */
export function verifyTargets(draft: RelayDraft): string[] {
  return draft.mappings
    .filter((mapping) => mapping.enabled)
    .map((mapping) => mapping.modelId.trim());
}

/* ---------- 缓存口径与探针 ---------- */

/** 「不含」的设置需要缓存探针的证据才能保存：返回需要证据的是读、写还是两者 */
export function unprovenCache(draft: RelayDraft): 'read' | 'write' | 'both' | null {
  const read = !draft.usageCacheReadInPrompt;
  const write = !draft.usageCacheWriteInPrompt;
  if (read && write) return 'both';
  if (read) return 'read';
  return write ? 'write' : null;
}

export function probeLabel(name: string): string {
  switch (name) {
    case 'basic':
      return t('基本问答');
    case 'tool_call':
      return t('函数调用');
    case 'tool_loop':
      return t('两轮工具循环');
    case 'stream':
      return t('流式');
    case 'vision':
      return t('图片');
    case 'cache_control':
      return t('缓存标记');
    case 'cache':
      return t('缓存探针');
    case 'cache_evidence':
      return t('缓存口径证据');
    default:
      return name;
  }
}

export function probeTone(probe: SourceTierProbeResult): 'success' | 'danger' | 'info' {
  if (probe.status === 'PASSED') return 'success';
  return probe.status === 'FAILED' ? 'danger' : 'info';
}

export function probeStatusLabel(probe: SourceTierProbeResult): string {
  if (probe.status === 'PASSED') return t('通过');
  return probe.status === 'FAILED' ? t('失败') : t('未发出');
}

/** 一次验证结果里某个映射（按目录模型，不分大小写）的探针结果 */
export function verificationOf(
  models: SourceTierModelVerification[] | null | undefined,
  modelId: string,
): SourceTierModelVerification | undefined {
  const id = modelId.trim().toLowerCase();
  return models?.find((model) => model.modelId.toLowerCase() === id);
}

/** 缓存探针对读或写的结论文字：true「已含在 prompt 内」、false「不含」、null「判断不了」 */
export function cacheVerdict(value: boolean | null | undefined): string {
  if (value === true) return t('已含在 prompt_tokens 内');
  if (value === false) return t('不含在 prompt_tokens 内');
  return t('判断不了');
}

export interface CacheSuggestion {
  read: boolean | null;
  write: boolean | null;
}

/** 缓存探针给的建议：只有它明确判断了的一项才有值，判断不了的保持原设置 */
export function cacheSuggestion(advice: SourceTierCacheAdvice | null | undefined): CacheSuggestion {
  return { read: advice?.readInPrompt ?? null, write: advice?.writeInPrompt ?? null };
}

/** 采用建议后的草稿：只改建议明确的开关，其余原样；建议与现状一样时返回原草稿 */
export function applyCacheSuggestion(draft: RelayDraft, suggestion: CacheSuggestion): RelayDraft {
  const read = suggestion.read ?? draft.usageCacheReadInPrompt;
  const write = suggestion.write ?? draft.usageCacheWriteInPrompt;
  if (read === draft.usageCacheReadInPrompt && write === draft.usageCacheWriteInPrompt)
    return draft;
  return { ...draft, usageCacheReadInPrompt: read, usageCacheWriteInPrompt: write };
}

/* ---------- 提示 ---------- */

/**
 * Claude Code 的缓存成本警告（设计 7.15）：成员没有打开 cache_control 时，网关不把 Claude Code 的缓存标记发给中转（system
 * 和内容是字符串），中转只有自己按前缀自动缓存的本事才用得上缓存，没有的话每轮按全价付输入，而 Kiro 计缓存读；同系数下
 * 中转级可能每轮贵数倍。打开 cache_control 后，Claude Code 的标记（system 的块、最近几轮的文本、工具结果）原样发给中转，
 * 不再警告。请求头不挡中转自己的前缀缓存（D7），中转报告的缓存命中按缓存读价计费。只对 Anthropic 厂商说。
 *
 * 但 cache_control 和「映射为 reasoning_effort」一起打开时另有一条（设计 7.6）：reasoning_effort 只在用户提示的轮次发、
 * 工具结果之后的轮次不发（那一轮没有可回放的签名思考块），中转若把它映射成 Claude 的思考开关，同一场对话里思考来回切换，
 * 每次切换都让整段消息缓存失效，整个历史按缓存写价而不是读价计费。
 */
export function cacheCostWarning(
  vendor: string,
  cacheControlPassthrough: boolean,
  reasoningMode: 'NONE' | 'EFFORT' = 'NONE',
): string | null {
  if (vendor !== 'anthropic') return null;
  if (cacheControlPassthrough && reasoningMode === 'EFFORT') {
    return t(
      '这把中转同时打开了 cache_control 和「映射为 reasoning_effort」：reasoning_effort 只在用户提示的轮次发，工具结果之后的轮次不发（那一轮没有可回放的签名思考块）。中转若把它映射成 Claude 的思考开关，同一场对话里思考会来回切换，每次切换都让整段消息缓存失效，整个历史按缓存写价而不是读价计费（Sonnet 上贵 12.5 倍），每个用户提示至少两次。要稳定地用上缓存，请把「思考映射」设为「不发送」。',
    );
  }
  if (cacheControlPassthrough) return null;
  return t(
    '这把中转没有打开 cache_control，网关不会把 Claude Code 的缓存标记发给它：Claude Code 每轮重发整个历史，除非中转自己按前缀自动缓存，否则每一轮都按全价计输入，而 Kiro 会计缓存读。号池系数相同时，中转所在的级别可能比 Kiro 每轮贵出数倍；请在中转认得 cache_control 时打开它，或把中转所在号池的系数定得能覆盖这一点。',
  );
}

/** 专用开关的警告：关闭后这把 Key 继续服务 GLM 等未分级的兼容模型，只服务 Claude 的中转会因 404 失败 */
export function exclusiveWarning(draft: RelayDraft): string | null {
  if (draft.exclusive) return null;
  return t(
    '关闭「专用」后，这把 Key 同时还服务 GLM 等未分级的兼容模型请求。只服务 Claude 的中转会对这些请求回 404（不重试），并且它的自动测试和定时复测会用第一条映射的模型名，由结果决定它在 GLM 池里的去留。',
  );
}

/** 并发和每分钟请求数都不限时的提示：繁忙（并发、限速）几乎不会触发，降级主要靠派发后的 429 */
export function limitsNote(
  policy: Pick<AccountAllocationPolicy, 'safeConcurrency' | 'safeRpm'> | null,
): string | null {
  if (!policy || policy.safeConcurrency > 0 || policy.safeRpm > 0) return null;
  return t(
    '这把 Key 没有设并发和每分钟请求数上限（兼容 Key 默认不限）。「繁忙算不可用」几乎不会触发，请求主要靠中转返回 429 之后才会改用下一级。要让网关在中转繁忙前就改用下一级，请在账号的分配设置里设上限。',
  );
}

/** 不流式的中转，单次时限超过网关的读超时没有意义：读超时先到 */
export function durationNote(draft: RelayDraft): string | null {
  if (draft.upstreamStream || draft.maxDurationSeconds <= DEFAULT_READ_TIMEOUT_SECONDS) return null;
  return t(
    '不使用上游流式时，中转要在网关的读超时（默认 {p0} 秒）内返回第一个字节，单次时限超过它不起作用。需要更长的生成时间请打开「上游流式」。',
    { p0: DEFAULT_READ_TIMEOUT_SECONDS },
  );
}

/** 第三方会看到完整的提示词、代码和工具输出（设计 7.12） */
export function privacyNote(): string {
  return t(
    '登记后，这把 Key 对应的中转会收到客户端请求里的完整提示词、代码和工具输出，请只登记你信任的中转。网关不会转发客户端的请求头、metadata 和会话标识。',
  );
}

/* ---------- 账号 ---------- */

function key(value: string | null | undefined): string {
  return (value ?? '').trim().toLowerCase();
}

function hostOf(url: string | null | undefined): string | null {
  const cleaned = (url ?? '').trim();
  if (!cleaned) return null;
  try {
    return new URL(cleaned).hostname.toLowerCase() || null;
  } catch {
    return null;
  }
}

/**
 * 是不是 OpenAI 兼容 Key（官方 OpenAI Key、Codex、DeepSeek 以外的 OpenAI 格式 API Key），与后端
 * AccountPlatforms.isOpenAiCompatibleApiKey 一致：只有这样的账号能当中转。
 */
export function isOpenAiCompatibleKey(
  account: Pick<Account, 'provider' | 'authMethod' | 'baseUrl'>,
): boolean {
  const provider = key(account.provider);
  const auth = key(account.authMethod);
  if (['google', 'gemini', 'anthropic', 'claude', 'deepseek'].includes(provider)) return false;
  const legacy = provider === 'openai-compatible' || provider === 'openaicompatibility';
  const openAi =
    provider === 'openai' ||
    provider === 'codex' ||
    legacy ||
    ['api-key', 'apikey', 'openai-api-key', 'codex'].includes(auth);
  if (!openAi) return false;
  if (provider === 'codex' || auth === 'codex') return false;
  // 用官方 DeepSeek 地址的旧版兼容账号是 DeepSeek 账号
  if (legacy && hostOf(account.baseUrl) === 'api.deepseek.com') return false;
  // 官方 OpenAI Key：provider 为空或 openai，地址缺省或是 api.openai.com
  const official =
    (provider === '' || provider === 'openai') && isOfficialOpenAiBaseUrl(account.baseUrl);
  return !official;
}

function isOfficialOpenAiBaseUrl(baseUrl: string | null | undefined): boolean {
  if (!(baseUrl ?? '').trim()) return true;
  return hostOf(baseUrl) === 'api.openai.com';
}

/**
 * 「添加中转」里可选的账号：是兼容 Key、在这个厂商里还没有成员行（一个账号在一个厂商里只有一行）；
 * 在别的厂商登记为专用中转的账号也可以选（同一把 Key 可以给另一个厂商再登记一行）。输入的文字按邮箱或账号 ID 过滤。
 */
export function relayCandidates(
  accounts: Account[],
  takenAccountIds: string[],
  query: string,
): Account[] {
  const taken = new Set(takenAccountIds);
  const text = query.trim().toLowerCase();
  return accounts.filter(
    (account) =>
      !taken.has(account.accountId) &&
      isOpenAiCompatibleKey(account) &&
      (!text ||
        account.accountId.toLowerCase().includes(text) ||
        (account.email ?? '').toLowerCase().includes(text)),
  );
}

/* ---------- 目录模型与别名 ---------- */

/** 目录里不是模型的条目：路由预设和 CodeWhisperer 的内部 ID，不能映射 */
const NON_MODEL_FAMILIES = new Set(['Routing Preset', 'CodeWhisperer Internal']);

export interface CatalogModel {
  id: string;
  name: string;
  contextWindow: number;
  /** 别名指向的目录模型（自己就是模型时与 id 相同或为空） */
  targetModelId: string;
  enabled: boolean;
}

/** 一个厂商能映射的目录模型：同厂商、不是路由预设或内部 ID，按 ID 排序 */
export function catalogModelsOf(vendor: string, models: ManagedModel[]): CatalogModel[] {
  return models
    .filter(
      (model) =>
        !NON_MODEL_FAMILIES.has(model.family) && vendorOfProvider(model.provider) === vendor,
    )
    .map((model) => ({
      id: model.id,
      name: model.name,
      contextWindow: model.contextWindow,
      targetModelId: model.targetModelId ?? '',
      enabled: model.enabled,
    }))
    .sort((a, b) => a.id.localeCompare(b.id));
}

/**
 * 一条映射同时服务的别名（设计 7.4）：同厂商、指向这个目录模型、自己没有映射行的目录模型（`sonnet` 与
 * `claude-sonnet-4.5` 共用一行）。自己有行的别名保留自己的 wire 名。
 */
export function coveredAliases(
  modelId: string,
  catalog: CatalogModel[],
  mappedIds: string[],
): string[] {
  const id = key(modelId);
  if (!id) return [];
  const own = new Set(mappedIds.map(key));
  return catalog
    .filter(
      (model) =>
        model.enabled &&
        key(model.id) !== id &&
        key(model.targetModelId) === id &&
        !own.has(key(model.id)),
    )
    .map((model) => model.id);
}

/** 目录里这个模型的窗口；找不到或没写时缺省 */
export function catalogWindow(modelId: string, catalog: CatalogModel[]): number | null {
  const found = catalog.find((model) => key(model.id) === key(modelId));
  return found && found.contextWindow > 0 ? found.contextWindow : null;
}

/**
 * 选了目录模型之后预填上下文上限：行里还没有填时取中转自己写的窗口（它清楚自己能装多少），没有就取目录窗口。
 * 已经填了的不动。
 */
export function prefillContext(
  mapping: MappingDraft,
  catalog: CatalogModel[],
  upstream: SourceTierUpstreamModel[],
): MappingDraft {
  if (typeof mapping.maxContextTokens === 'number') return mapping;
  const stated = upstream.find((model) => model.id === mapping.upstreamModel)?.contextLength;
  const window = stated && stated > 0 ? stated : catalogWindow(mapping.modelId, catalog);
  return window ? { ...mapping, maxContextTokens: window } : mapping;
}

function squash(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '');
}

/**
 * 中转的模型名里和这个目录模型对得上的那个：忽略大小写和标点后相同（`claude-sonnet-4.5` 与 `claude-sonnet-4-5`），
 * 或只有一个名字包含它（`anthropic/claude-sonnet-4.5`）。对得上的不止一个，就不替管理员选。
 */
export function suggestWireName(
  modelId: string,
  upstream: SourceTierUpstreamModel[],
): string | null {
  const wanted = squash(modelId);
  if (!wanted) return null;
  const same = upstream.filter((model) => squash(model.id) === wanted);
  if (same.length) return same.length === 1 ? same[0].id : null;
  const containing = upstream.filter((model) => squash(model.id).includes(wanted));
  return containing.length === 1 ? containing[0].id : null;
}

/** 给映射里空着上下文上限的行补上预填值 */
export function prefillContexts(
  mappings: MappingDraft[],
  catalog: CatalogModel[],
  upstream: SourceTierUpstreamModel[],
): MappingDraft[] {
  return mappings.map((mapping) => prefillContext(mapping, catalog, upstream));
}

/* ---------- 门控 ---------- */

export interface EntryInfo {
  entry: SourceTierEntryEndpoint;
  title: string;
  /** 走这个入口的典型客户端 */
  clients: string;
}

export function entryInfo(entry: SourceTierEntryEndpoint): EntryInfo {
  switch (entry) {
    case 'CHAT':
      return {
        entry,
        title: t('Chat Completions 入口'),
        clients: t('OpenCode 等 OpenAI 格式的客户端，/v1/chat/completions'),
      };
    case 'MESSAGES':
      return {
        entry,
        title: t('Messages 入口'),
        clients: t('Claude Code 等 Anthropic 格式的客户端，/v1/messages'),
      };
    case 'RESPONSES':
      return {
        entry,
        title: t('Responses 入口'),
        clients: t('Codex，/v1/responses'),
      };
    default:
      return {
        entry,
        title: t('Gemini 入口'),
        clients: t('Gemini 格式的客户端，:generateContent'),
      };
  }
}

export function pairStatusOf(
  runtime: SourceTierRuntime | null | undefined,
  vendor: string,
  entry: SourceTierEntryEndpoint,
): SourceTierPairStatusName {
  const found = runtime?.pairs.find(
    (pair) =>
      pair.vendor === vendor &&
      pair.sourceProtocol === RELAY_PROTOCOL &&
      pair.entryEndpoint === entry,
  );
  return found?.status ?? 'NOT_OFFERED';
}

export function pairStatusLabel(status: SourceTierPairStatusName): string {
  switch (status) {
    case 'VERIFIED':
      return t('已验证');
    case 'EXPERIMENTAL':
      return t('试用中');
    default:
      return t('未提供');
  }
}

export function pairStatusHint(status: SourceTierPairStatusName): string {
  switch (status) {
    case 'VERIFIED':
      return t('这个对已经升级：门控可以对所有 Key 打开。');
    case 'EXPERIMENTAL':
      return t(
        '试用中：门控只能对名单里的 Key 打开。累计达到升级标准（见「导出升级证据」）后，由升级任务把它改为已验证，才能对所有 Key 打开。',
      );
    default:
      return t('这个版本还没有提供这个对，门控打不开。');
  }
}

export function gateStateLabel(state: SourceTierGateState): string {
  switch (state) {
    case 'KEYS':
      return t('名单里的 Key');
    case 'ON':
      return t('所有 Key');
    default:
      // "关闭" is the close button of a dialog and reads "Close" in English; this is the state of a gate.
      return t('不开放');
  }
}

/** 这个状态的对允许的门控状态：未提供只能关；试用中不能对所有 Key 打开 */
export function allowedGateStates(status: SourceTierPairStatusName): SourceTierGateState[] {
  if (status === 'NOT_OFFERED') return ['OFF'];
  return status === 'EXPERIMENTAL' ? ['OFF', 'KEYS'] : ['OFF', 'KEYS', 'ON'];
}

/** 这个入口的门控行；没有行就是关闭 */
export function gateOf(
  gates: SourceTierGate[],
  entry: SourceTierEntryEndpoint,
): SourceTierGate | undefined {
  return gates.find(
    (gate) =>
      (gate.sourceProtocol ?? RELAY_PROTOCOL) === RELAY_PROTOCOL && gate.entryEndpoint === entry,
  );
}

export function gateState(
  gates: SourceTierGate[],
  entry: SourceTierEntryEndpoint,
): SourceTierGateState {
  return gateOf(gates, entry)?.state ?? 'OFF';
}

export function gateKeys(gates: SourceTierGate[], entry: SourceTierEntryEndpoint): string[] {
  return [...(gateOf(gates, entry)?.allowKeyIds ?? [])];
}

/** 改一个入口的门控：关闭且名单为空的行不保留（没有行就是关闭） */
export function setGate(
  gates: SourceTierGate[],
  entry: SourceTierEntryEndpoint,
  change: { state?: SourceTierGateState; allowKeyIds?: string[] },
): SourceTierGate[] {
  const current = gateOf(gates, entry);
  const next: SourceTierGate = {
    sourceProtocol: RELAY_PROTOCOL,
    entryEndpoint: entry,
    state: change.state ?? current?.state ?? 'OFF',
    allowKeyIds: change.allowKeyIds ?? current?.allowKeyIds ?? [],
  };
  const others = gates.filter((gate) => gate !== current);
  return next.state === 'OFF' && !next.allowKeyIds?.length ? others : [...others, next];
}

/** 门控的问题：名单超过上限，名单为空的「名单里的 Key」没有 Key 能用，状态超出这个对允许的 */
export function gateProblem(
  gates: SourceTierGate[],
  entry: SourceTierEntryEndpoint,
  status: SourceTierPairStatusName,
): string | null {
  const state = gateState(gates, entry);
  if (!allowedGateStates(status).includes(state)) {
    return t('这个对现在不能设为「{p0}」', { p0: gateStateLabel(state) });
  }
  const keys = gateKeys(gates, entry);
  if (keys.length > MAX_GATE_KEYS) return t('名单最多 {p0} 把 Key', { p0: MAX_GATE_KEYS });
  if (state === 'KEYS' && !keys.length) {
    return t('名单是空的：没有任何 Key 的请求会派给中转');
  }
  return null;
}

/** 保存前挡住的门控问题：名单为空只是提示，不挡；状态超出和名单过长会被后端拒绝 */
export function blockingGateProblem(
  gates: SourceTierGate[],
  runtime: SourceTierRuntime | null | undefined,
  vendor: string,
): string | null {
  for (const entry of ENTRY_ENDPOINTS) {
    const status = pairStatusOf(runtime, vendor, entry);
    if (!allowedGateStates(status).includes(gateState(gates, entry))) {
      return t('{p0}：这个对现在不能设为「{p1}」', {
        p0: entryInfo(entry).title,
        p1: gateStateLabel(gateState(gates, entry)),
      });
    }
    if (gateKeys(gates, entry).length > MAX_GATE_KEYS) {
      return t('{p0}：名单最多 {p1} 把 Key', { p0: entryInfo(entry).title, p1: MAX_GATE_KEYS });
    }
  }
  return null;
}

/* ---------- 指标 ---------- */

/** 0 到 100，保留一位小数；除数为 0 时为 null */
export function percentOf(count: number, of: number): number | null {
  if (!(of > 0)) return null;
  return Math.round((count / of) * 1000) / 10;
}

export function successPercent(figures: SourceTierXpFigures): number | null {
  return percentOf(figures.served, figures.attempts);
}

/** 用量是网关估算的占已服务尝试的比例；超过 5% 标红（设计 5.8） */
export function estimatedPercent(figures: SourceTierXpFigures): number | null {
  return percentOf(figures.estimated, figures.served);
}

export function clampedPercent(figures: SourceTierXpFigures): number | null {
  return percentOf(figures.clamped, figures.served);
}

/** 中转可能已经生成、没人付费的中断占全部尝试的比例（UNBILLED，运营者承担） */
export function unbilledPercent(figures: SourceTierXpFigures): number | null {
  return percentOf(figures.interrupted, figures.attempts);
}

export function conversionRejectedPercent(figures: SourceTierXpFigures): number | null {
  return percentOf(figures.conversionRejected, figures.attempts);
}

/** 网关把中转的应答扣下（内容过滤，或在网关自己的输出上限处丢了调用）的尝试占全部尝试的比例；不在中断里，也不是中转的故障 */
export function withheldPercent(figures: SourceTierXpFigures): number | null {
  return percentOf(figures.withheld, figures.attempts);
}

/**
 * 被扣下的应答里中转报告的令牌（运营者承担的成本，用户没被收费）；没有被扣下的应答，或中转没报告任何用量时是 null。
 * 旧节点的状态里没有这两个字段，按没有算。
 */
export function withheldTokens(
  figures: SourceTierXpFigures,
): { input: number; output: number } | null {
  const input = figures.withheldInputTokens ?? 0;
  const output = figures.withheldOutputTokens ?? 0;
  return input > 0 || output > 0 ? { input, output } : null;
}

export type FigureKind = 'success' | 'estimated' | 'unbilled' | 'conversionRejected' | 'withheld';

/** 指标的提示色：估算超过 5%、中断达到 2%、转换被拒达到 1% 标红（升级标准的界限），成功率低于 90% 标黄，有被扣下的应答标黄（没有升级标准） */
export function figureTone(
  kind: FigureKind,
  value: number | null,
): 'success' | 'warning' | 'danger' | 'info' {
  if (value === null) return 'info';
  switch (kind) {
    case 'estimated':
      return value > 5 ? 'danger' : 'success';
    case 'unbilled':
      return value >= 2 ? 'danger' : value > 0 ? 'warning' : 'success';
    case 'conversionRejected':
      return value >= 1 ? 'danger' : value > 0 ? 'warning' : 'success';
    case 'withheld':
      return value > 0 ? 'warning' : 'success';
    default:
      return value < 90 ? 'warning' : 'success';
  }
}

export function formatLatency(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  return ms < 1000 ? `${ms} ms` : `${Math.round(ms / 100) / 10} s`;
}

/** 本节点跳过中转的原因（XpSkip.Reason）：名称和该怎么办 */
export function skipReasonLabel(reason: string): string {
  switch (reason) {
    case 'GATE':
      return t('Key 不在门控名单里');
    case 'NOT_A_RELAY':
      return t('账号不再是兼容 Key');
    case 'NO_MAPPING':
      return t('没有这个模型的映射');
    case 'UNVERIFIED':
      return t('映射未验证');
    case 'FEATURE':
      return t('请求带了中转承载不了的特性');
    case 'CONVERSION':
      return t('转换后的请求没通过严格检查');
    case 'CONTEXT':
      return t('请求超过上下文上限');
    case 'INTERRUPTED':
      return t('刚中断过这个会话');
    default:
      return reason;
  }
}

export function skipReasonHint(reason: string): string {
  switch (reason) {
    case 'GATE':
      return t(
        '这个入口的门控只对名单里的 Key 开放，而这把 Key 不在名单里（这个厂商的门控不开放的入口不看它的中转，也就不计这一项）',
      );
    case 'NOT_A_RELAY':
      return t('账号被改成了官方 OpenAI Key、Codex 或别的类型，它不能再当中转');
    case 'NO_MAPPING':
      return t('请求的模型在这个中转上没有启用的映射（别名与它指向的目录模型共用一行）');
    case 'UNVERIFIED':
      return t('映射从没验证过，或验证之后设置、模型名、账号地址变了：在成员对话框里重新验证');
    case 'FEATURE':
      return t(
        '请求带了图片、json_schema、服务端工具等中转承载不了、而原生账号能承载的特性，这个请求只能由原生账号服务',
      );
    case 'CONVERSION':
      return t(
        '请求转换成中转的格式后没通过严格检查。这里是厂商各个入口的合计；升级证据按入口数，这一对的入口必须为 0',
      );
    case 'CONTEXT':
      return t('请求比映射的上下文上限，或中转拒绝过的长度更大，由窗口更大的原生级别服务');
    case 'INTERRUPTED':
      return t(
        '中转刚中断了这个会话的应答（它可能还在生成），或它的应答被网关扣下（内容过滤、在网关的上限处丢了调用），客户端的重试 2 分钟内不会再给它，免得为多次部分生成或被扣下的应答付费',
      );
    default:
      return reason;
  }
}

/* ---------- 升级证据 ---------- */

export function criterionLabel(name: string): string {
  switch (name) {
    case 'requests':
      return t('尝试数');
    case 'lintFailures':
      return t('严格检查失败');
    case 'conversionRejected':
      return t('转换被拒');
    case 'estimated':
      return t('用量估算');
    case 'unbilled':
      return t('中断（UNBILLED）');
    default:
      return name;
  }
}

/** 一条标准旁要说明的事：中断只是下限，客户端自己先断开的请求不进日志；其余没有 */
export function criterionNote(name: string): string {
  return name === 'unbilled' ? t('下限：客户端先断开的请求不进日志') : '';
}

/**
 * 严格检查失败是应答节点自己数的（自 skipsSince 起）。节点起得比窗口的起点晚，窗口前面那一段它没看到，
 * 这一项判不了，后端把它算作未达标
 */
export function lintCountPartial(evidence: Pick<SourceTierEvidence, 'since' | 'skipsSince'>): boolean {
  const counted = Date.parse(evidence.skipsSince);
  const window = Date.parse(evidence.since);
  return Number.isFinite(counted) && Number.isFinite(window) && counted > window;
}

/** 一条标准的取值文字：个数或百分比；没有可除的数时为「—」 */
export function criterionValue(criterion: SourceTierCriterion): string {
  if (criterion.value === null || criterion.value === undefined) return '—';
  return criterion.rule.includes('%') ? `${criterion.value}%` : String(criterion.value);
}

/** 未达标的标准名 */
export function unmetCriteria(criteria: SourceTierCriterion[]): string[] {
  return criteria.filter((criterion) => !criterion.met).map((criterion) => criterion.name);
}

export function evidenceFileName(evidence: Pick<SourceTierEvidence, 'at' | 'days'>): string {
  const day = evidence.at.slice(0, 10) || 'export';
  return `starry-pair-evidence-${evidence.days}d-${day}.json`;
}

/* ---------- 错误 ---------- */

interface CodedError {
  code: string;
  message: string;
  data?: unknown;
}

/**
 * 保存配置或中转、验证中转和取它的模型列表时后端的业务错误，换成控制台的说法；不认识的用后端的原文。
 * 网络错误只有在验证时才是「超时」：验证最长 4 分钟，服务器端可能仍在进行。
 */
export function relayErrorMessage(
  err: CodedError,
  action: 'save' | 'verify' | 'models' = 'save',
): string {
  switch (err.code) {
    case 'SOURCE_TIER_CACHE_EVIDENCE_REQUIRED':
      return t(
        '「不含缓存」的设置需要缓存探针的证据。先把对应的开关保存为「含」，在下面用「缓存探针」验证，再按探针的建议改设置。',
      );
    case 'SOURCE_VERIFY_RATE_LIMITED': {
      const seconds = (err.data as { retryAfterSeconds?: number } | undefined)?.retryAfterSeconds;
      return typeof seconds === 'number'
        ? t('探针由运营者付费，同一个账号 30 秒只能验证一次，请 {p0} 秒后再试。', { p0: seconds })
        : t('探针由运营者付费，同一个账号 30 秒只能验证一次，请稍后再试。');
    }
    case 'SOURCE_TIER_MEMBER_NOT_FOUND':
      return t('这个账号还不是这个厂商的中转，请先保存。');
    case 'SOURCE_UPSTREAM_MODELS_FAILED':
      return t('中转没有返回它的模型列表，请手动填写中转的模型名。');
    case 'SOURCE_TIER_RUNTIME_UNSUPPORTED':
      return unsupportedMessage(err.data) ?? err.message;
    case 'SOURCE_PAIR_NOT_OFFERED':
      return t('这个版本还没有提供这个入口的这个对，门控不能打开。');
    case 'SOURCE_PAIR_GATE_FORBIDDEN':
      return t('试用中的对只能对名单里的 Key 打开，不能设为「所有 Key」。');
    default:
      return err.code === 'NETWORK_ERROR' && action === 'verify'
        ? t('请求没有在限定时间内返回。验证可能仍在服务器上进行，稍后刷新看映射是否已验证。')
        : err.message;
  }
}
