import { t } from '@/i18n';
import type { AllocationCategory } from '@/api/allocationDefaults';
import type { Account } from '@/types/api';
import type {
  ReplaceSourceTierVendor,
  SourceTier,
  SourceTierGate,
  SourceTierMember,
  SourceTierVendor,
} from '@/api/sourceTiers';

/**
 * 号源分级页的草稿模型。
 *
 * 页面编辑的是一份草稿：保存时整体替换一个厂商的配置，所以草稿要能还原成后端接受的完整请求。
 * 本页不编辑的部分（跨协议成员行、门控）原样放在草稿里带回，保存不会把它们删掉。
 * 级别就是号池在列表里的位置（从 1 开始），拖拽排序即改变级别。
 */

/** 与后端 SourceTierValidator 一致的取值范围 */
export const MAX_TIERS = 9;
export const MAX_TIER_NAME = 32;
export const MAX_PRICE_FACTOR = 1000;
export const MIN_REFUSAL_CAP = 4;
export const MAX_REFUSAL_CAP = 64;
export const DEFAULT_REFUSAL_CAP = 16;

/** tierId 是 `模型@标识` 写法里的标识，也是成员行引用号池的键 */
const TIER_ID = /^[a-z0-9][a-z0-9-]{0,31}$/;
/** auto、L2、2 这些写法按级别选号，不能当标识 */
const SELECTOR_WORD = /^(auto|l?[0-9]+)$/;

export interface TierDraft {
  /** 仅用于列表渲染：标识可以编辑，不能当 v-for 的键 */
  key: string;
  tierId: string;
  name: string;
  priceFactor: number;
  memberCategories: AllocationCategory[];
  userSelectable: boolean;
  enabled: boolean;
  description: string | null;
}

/** 手动固定到某个号池的原生账号（没有 protocol 的成员行）；按号池行的 key 引用，标识改名后仍然有效 */
export interface PinDraft {
  accountId: string;
  tierKey: string;
}

export interface VendorDraft {
  enabled: boolean;
  unifyEndpoints: boolean;
  publicListing: boolean;
  stickySessions: boolean;
  refusalCap: number;
  crossTierReservedSlot: boolean;
  tiers: TierDraft[];
  pins: PinDraft[];
  /** 本页不编辑、保存时原样带回的部分 */
  relayMembers: SourceTierMember[];
  gates: SourceTierGate[];
}

let keySeed = 0;

export function newTierKey(): string {
  keySeed += 1;
  return `tier-row-${keySeed}`;
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function tierDraft(tier: SourceTier): TierDraft {
  return {
    key: newTierKey(),
    tierId: tier.tierId,
    name: tier.name,
    priceFactor: tier.priceFactor,
    memberCategories: [...tier.memberCategories],
    userSelectable: tier.userSelectable,
    enabled: tier.enabled,
    description: tier.description ?? null,
  };
}

/** 已保存配置对应的草稿（从未配置的厂商得到默认开关和空的号池列表） */
export function draftOf(vendor: SourceTierVendor): VendorDraft {
  const tiers = [...vendor.tiers].sort((a, b) => a.level - b.level).map(tierDraft);
  const keyOf = new Map(tiers.map((tier) => [tier.tierId, tier.key]));
  return {
    enabled: vendor.enabled,
    unifyEndpoints: vendor.unifyEndpoints,
    publicListing: vendor.publicListing,
    stickySessions: vendor.stickySessions,
    refusalCap: vendor.refusalCap,
    crossTierReservedSlot: vendor.crossTierReservedSlot,
    tiers,
    pins: vendor.members.flatMap((member) => {
      const tierKey = keyOf.get(member.tierId);
      return member.protocol || tierKey === undefined
        ? []
        : [{ accountId: member.accountId, tierKey }];
    }),
    relayMembers: clone(vendor.members.filter((member) => member.protocol)),
    gates: clone(vendor.gates),
  };
}

/** 数据库里系数有 6 位小数 */
export function roundFactor(value: number): number {
  return Math.round(value * 1_000_000) / 1_000_000;
}

/** 只把一个原生账号固定到号池的成员行：没有 protocol，其余字段不适用 */
export function nativeMember(accountId: string, tierId: string): SourceTierMember {
  return {
    accountId,
    tierId,
    protocol: null,
    exclusive: null,
    vision: null,
    jsonSchema: null,
    priorityTier: null,
    cacheControlPassthrough: null,
    upstreamStream: null,
    reasoningMode: null,
    maxDurationSeconds: null,
    usageCacheReadInPrompt: null,
    usageCacheWriteInPrompt: null,
    models: null,
  };
}

/** 草稿对应的整体替换请求；expectedVersion 是草稿所基于的已保存版本 */
export function replaceRequest(
  expectedVersion: number,
  draft: VendorDraft,
): ReplaceSourceTierVendor {
  return {
    expectedVersion,
    enabled: draft.enabled,
    unifyEndpoints: draft.unifyEndpoints,
    publicListing: draft.publicListing,
    stickySessions: draft.stickySessions,
    refusalCap: draft.refusalCap,
    crossTierReservedSlot: draft.crossTierReservedSlot,
    tiers: draft.tiers.map((tier, index) => ({
      tierId: tier.tierId,
      level: index + 1,
      name: tier.name.trim(),
      priceFactor: roundFactor(tier.priceFactor),
      memberCategories: [...tier.memberCategories].sort(),
      userSelectable: tier.userSelectable,
      enabled: tier.enabled,
      description: tier.description?.trim() || null,
    })),
    members: [
      ...draft.pins.flatMap((pin) => {
        const tier = draft.tiers.find((candidate) => candidate.key === pin.tierKey);
        return tier ? [nativeMember(pin.accountId, tier.tierId)] : [];
      }),
      ...clone(draft.relayMembers),
    ],
    gates: clone(draft.gates),
  };
}

/** 判断草稿有没有改动：与加载时的签名比较 */
export function draftSignature(draft: VendorDraft): string {
  return JSON.stringify(replaceRequest(0, draft));
}

/** 新号池的默认标识和名称：取第一个没被占用的序号 */
export function newTier(tiers: TierDraft[]): TierDraft {
  let n = tiers.length + 1;
  while (
    tiers.some(
      (tier) => tier.tierId === `pool-${n}` || tier.name.trim() === t('号池 {p0}', { p0: n }),
    )
  ) {
    n += 1;
  }
  return {
    key: newTierKey(),
    tierId: `pool-${n}`,
    name: t('号池 {p0}', { p0: n }),
    priceFactor: 1,
    memberCategories: [],
    userSelectable: true,
    enabled: true,
    description: null,
  };
}

/**
 * 每个草稿号池（按 key）在已保存配置里收下的启用账号数。数字按已保存的配置计算，所以标识在已保存配置里
 * 找不到的号池（新增的，或刚改了标识的）没有数字。
 */
export function placedCounts(loaded: SourceTierVendor, draft: VendorDraft): Record<string, number> {
  const saved = new Set(loaded.tiers.map((tier) => tier.tierId));
  const counts = loaded.tierAccounts ?? {};
  const result: Record<string, number> = {};
  for (const tier of draft.tiers) {
    if (saved.has(tier.tierId)) result[tier.key] = counts[tier.tierId] ?? 0;
  }
  return result;
}

/**
 * 在这些厂商里登记为专用中转（exclusive，缺省为是）的账号。它们不是任何厂商的原生成员，固定到别的厂商
 * 也不会生效（后端路由和状态页都把它们排除在外），所以不能再被固定。
 */
export function exclusiveRelayIds(vendors: Iterable<SourceTierVendor>): string[] {
  const ids = new Set<string>();
  for (const vendor of vendors) {
    for (const member of vendor.members) {
      if (member.protocol && member.exclusive !== false) ids.add(member.accountId);
    }
  }
  return [...ids];
}

/**
 * 「添加指定账号」里可选的账号：已经有成员行的不能再选（固定过的，以及登记为中转的——一个账号在一个厂商里
 * 只能有一行，否则保存会被后端拒绝；在别的厂商登记为专用中转的账号也不能选，固定了也不会生效，由调用方一并传入）；
 * 输入的文字按邮箱或账号 ID 过滤。
 */
export function pinChoices(
  accounts: Account[],
  pins: PinDraft[],
  relayAccountIds: string[],
  query: string,
): Account[] {
  const taken = new Set([...pins.map((pin) => pin.accountId), ...relayAccountIds]);
  const text = query.trim().toLowerCase();
  return accounts.filter(
    (account) =>
      !taken.has(account.accountId) &&
      (!text ||
        account.accountId.toLowerCase().includes(text) ||
        (account.email ?? '').toLowerCase().includes(text)),
  );
}

/** 最低的已启用号池：没有被成员行或类别收下的账号落到这里 */
export function lowestEnabledKey(tiers: TierDraft[]): string | null {
  for (let index = tiers.length - 1; index >= 0; index -= 1) {
    if (tiers[index].enabled) return tiers[index].key;
  }
  return null;
}

export function moveItem<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return items;
  const next = [...items];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return next;
}

/* ---------- 校验（后端仍是最终裁判） ---------- */

export interface TierProblems {
  id?: string;
  name?: string;
  factor?: string;
}

/** Java 的 Character.isISOControl：C0 与 C1 控制字符 */
function isControl(char: string): boolean {
  const code = char.codePointAt(0) ?? 0;
  return code <= 0x1f || (code >= 0x7f && code <= 0x9f);
}

/** 每个号池（按 key）当前的问题，与后端 SourceTierValidator 的规则一致 */
export function tierProblems(tiers: TierDraft[]): Record<string, TierProblems> {
  const result: Record<string, TierProblems> = {};
  const idCount = new Map<string, number>();
  const nameCount = new Map<string, number>();
  for (const tier of tiers) {
    idCount.set(tier.tierId, (idCount.get(tier.tierId) ?? 0) + 1);
    const name = tier.name.trim();
    nameCount.set(name, (nameCount.get(name) ?? 0) + 1);
  }
  for (const tier of tiers) {
    const problems: TierProblems = {};
    if (!TIER_ID.test(tier.tierId)) {
      problems.id = t('标识只能用小写字母、数字和连字符，以字母或数字开头，最多 32 位');
    } else if (SELECTOR_WORD.test(tier.tierId)) {
      problems.id = t('「{p0}」是选号写法的保留字（auto、L2、纯数字），请换一个标识', {
        p0: tier.tierId,
      });
    } else if ((idCount.get(tier.tierId) ?? 0) > 1) {
      problems.id = t('标识与其他号池重复');
    }
    const name = tier.name.trim();
    if (!name) {
      problems.name = t('名称不能为空');
    } else if (Array.from(name).length > MAX_TIER_NAME) {
      problems.name = t('名称最多 {p0} 个字符', { p0: MAX_TIER_NAME });
    } else if (Array.from(name).some(isControl)) {
      problems.name = t('名称不能包含控制字符');
    } else if ((nameCount.get(name) ?? 0) > 1) {
      problems.name = t('名称与其他号池重复');
    }
    if (
      typeof tier.priceFactor !== 'number' ||
      !Number.isFinite(tier.priceFactor) ||
      roundFactor(tier.priceFactor) <= 0 ||
      tier.priceFactor > MAX_PRICE_FACTOR
    ) {
      problems.factor = t('系数必须大于 0，且不超过 {p0}', { p0: MAX_PRICE_FACTOR });
    }
    if (problems.id || problems.name || problems.factor) result[tier.key] = problems;
  }
  return result;
}

/** 草稿里第一个会被后端拒绝的问题；没有则返回 null */
export function validateDraft(draft: VendorDraft): string | null {
  if (draft.tiers.length > MAX_TIERS) {
    return t('一个厂商最多 {p0} 个号池', { p0: MAX_TIERS });
  }
  const problems = tierProblems(draft.tiers);
  for (const [index, tier] of draft.tiers.entries()) {
    const found = problems[tier.key];
    const message = found?.id ?? found?.name ?? found?.factor;
    if (message) return t('第 {p0} 级：{p1}', { p0: index + 1, p1: message });
  }
  const claimed = new Map<AllocationCategory, string>();
  for (const [index, tier] of draft.tiers.entries()) {
    for (const category of tier.memberCategories) {
      const other = claimed.get(category);
      if (other !== undefined) {
        return t('同一个类别只能属于一个号池：「{p0}」和第 {p1} 级都选了它', {
          p0: other,
          p1: index + 1,
        });
      }
      claimed.set(category, tier.name.trim());
    }
  }
  if (
    !Number.isInteger(draft.refusalCap) ||
    draft.refusalCap < MIN_REFUSAL_CAP ||
    draft.refusalCap > MAX_REFUSAL_CAP
  ) {
    return t('每级拒绝上限必须是 {p0} 到 {p1} 之间的整数', {
      p0: MIN_REFUSAL_CAP,
      p1: MAX_REFUSAL_CAP,
    });
  }
  if (draft.enabled && !draft.tiers.some((tier) => tier.enabled)) {
    return t('启用分级至少需要一个已启用的号池');
  }
  return null;
}

/** 后端 409 SOURCE_TIER_RUNTIME_UNSUPPORTED 的 data：本版本跑不了的设置 */
export function unsupportedMessage(items: unknown): string | null {
  if (!Array.isArray(items) || !items.length) return null;
  const labels = items.map((item) => {
    switch (item) {
      case 'enabled':
        return t('启用分级');
      case 'unifyEndpoints':
        return t('全端点统一');
      case 'crossProtocolMembers':
        return t('跨协议成员');
      case 'pairGates':
        return t('跨协议门控');
      default:
        return String(item);
    }
  });
  return t('当前版本尚不支持：{p0}。关闭它们后可以保存其余设置。', { p0: labels.join('、') });
}

/**
 * 「启用」开关下方的提示。能启用的版本里，厂商还没启用时提醒先把所有节点升级到支持分级的版本：旧节点不认识这份配置，
 * 照旧按标准价计费，同一个请求在不同节点上价格就不一样。不能启用的版本里，说明为什么。没有要说的返回 null。
 */
export function enableNote(
  nativeTiers: boolean,
  loadedEnabled: boolean,
  draftEnabled: boolean,
): string | null {
  if (nativeTiers) {
    return loadedEnabled
      ? null
      : t(
          '所有节点都升级到支持号源分级的版本后再启用：旧版本的节点不按号池选号，会按标准价计费，同一个请求在不同节点上价格不同。',
        );
  }
  return draftEnabled
    ? t('当前版本尚不支持按号池选号，这个设置需要关闭后才能保存。')
    : t('当前版本尚不支持按号池选号，暂时不能启用；号池配置可以先保存。');
}

/**
 * 「全端点统一」开关下方的提示。不能运行它的版本里，说明为什么。能运行时：厂商没有启用分级，开关保存了也不起作用，要说；
 * 还没有保存过打开的状态时，提醒先把所有节点升级到支持它的版本（旧节点仍只用 Kiro 服务 Chat 和 Responses 上的 Claude 模型，
 * 同一个请求在不同节点上走法不同）。没有要说的返回 null。
 */
export function unifyNote(
  supported: boolean,
  loadedUnify: boolean,
  draftUnify: boolean,
  draftEnabled: boolean,
): string | null {
  if (!supported) {
    return draftUnify
      ? t('当前版本尚不支持全端点统一，这个设置需要关闭后才能保存。')
      : t('当前版本尚不支持全端点统一，暂时不能打开。');
  }
  if (draftUnify && !draftEnabled) {
    return t('这个厂商还没有启用分级，全端点统一要等「启用」打开后才会生效。');
  }
  return loadedUnify
    ? null
    : t(
        '所有节点都升级到支持全端点统一的版本后再打开：旧版本的节点仍只用 Kiro 服务 Chat 和 Responses 入口上的 Claude 模型，同一个请求在不同节点上的走法不同。',
      );
}

/* ---------- 展示用的小函数 ---------- */

/** 去掉多余的 0：0.600000 → 0.6 */
export function formatFactor(value: number): string {
  return String(roundFactor(value));
}

/** 「标准价 × 系数」的示例：标准价 $10 的模型在这个号池里卖多少 */
export function factorExample(factor: number): string {
  return String(Number((10 * roundFactor(factor)).toPrecision(6)));
}

const SAMPLE_MODELS: Record<string, string> = {
  anthropic: 'claude-sonnet-4.5',
  openai: 'gpt-5.5',
  google: 'gemini-2.5-pro',
  deepseek: 'deepseek-chat',
};

/** `模型@标识` 写法的示例（复制到客户端配置里再改成实际的模型） */
export function suffixExample(vendor: string, tierId: string): string {
  return `${SAMPLE_MODELS[vendor] ?? 'model'}@${tierId}`;
}

/* ---------- 预设 ---------- */

/** 预设只生成草稿：套用后要点保存才会写入，系数一律 1，不替管理员定价 */
export interface TierPreset {
  id: string;
  title: string;
  description: string;
  build: () => TierDraft[];
}

function presetTier(
  tierId: string,
  name: string,
  memberCategories: AllocationCategory[],
): TierDraft {
  return {
    key: newTierKey(),
    tierId,
    name,
    priceFactor: 1,
    memberCategories,
    userSelectable: true,
    enabled: true,
    description: null,
  };
}

export function tierPresets(vendor: string): TierPreset[] {
  switch (vendor) {
    case 'anthropic':
      return [
        {
          id: 'official-kiro',
          title: t('官方 Key + Kiro'),
          description: t('1 级官方 API Key，2 级 Kiro 与 Claude OAuth。没有列出的账号落入 2 级。'),
          build: () => [
            presetTier('official', t('官方 API Key'), ['OFFICIAL_API_KEY']),
            presetTier('kiro', 'Kiro', ['KIRO', 'CLAUDE_OAUTH']),
          ],
        },
        {
          id: 'relay-kiro',
          title: t('OpenAI 中转 + Kiro'),
          description: t(
            '1 级放 OpenAI 格式的中转（保存后在下面的「跨协议成员」里登记），2 级 Kiro 与 Claude OAuth。没有列出的账号，包括官方 API Key，落入 2 级。系数都是 1，Kiro 要便宜多少请自己定。',
          ),
          build: () => [
            presetTier('relay', t('OpenAI 中转'), []),
            presetTier('kiro', 'Kiro', ['KIRO', 'CLAUDE_OAUTH']),
          ],
        },
      ];
    case 'openai':
      return [
        {
          id: 'official-codex',
          title: t('官方 Key + Codex'),
          description: t(
            '1 级官方 API Key，2 级 Codex。Codex 默认与官方 Key 隔离（账号设置 isolateCodexPool），要让两者分级需要先关闭它。',
          ),
          build: () => [
            presetTier('official', t('官方 API Key'), ['OFFICIAL_API_KEY']),
            presetTier('codex', 'Codex', ['CODEX']),
          ],
        },
      ];
    default:
      return [];
  }
}
