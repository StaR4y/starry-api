import { t } from '@/i18n';
import type {
  SourceTierAccountLayers,
  SourceTierPlaced,
  SourceTierPlacements,
  SourceTierVendor,
  SourceTierVendorStatus,
  UpsertSourceTierMembers,
} from '@/api/sourceTiers';
import { nativeMember } from './sourceTiersMeta';

/**
 * 号源分级的运行状态与账号列表用到的纯函数：状态面板的账号分层、降级率，账号列表的「号源级别」列、筛选，
 * 以及「设为号池…」批量请求。与界面无关的计算都放在这里，便于单独测试。
 */

/* ---------- 状态面板 ---------- */

/**
 * 账号层的顺序：从「能服务」到「最不能服务」；号池收下的每个账号只计入第一个让它不能服务的层。
 * unopened 只有中转有：还没有任何入口的门控对它开放，或没有已验证的映射，请求不会派给它。
 */
export const LAYERS = ['ready', 'busy', 'resting', 'unavailable', 'unopened', 'disabled'] as const;
export type LayerKey = (typeof LAYERS)[number];

export function layerLabel(key: LayerKey): string {
  switch (key) {
    case 'ready':
      return t('就绪');
    case 'busy':
      return t('繁忙');
    case 'resting':
      return t('冷却');
    case 'unavailable':
      return t('不可用');
    case 'unopened':
      // Not the text of "unavailable" or of a payment that is not offered, which the English of "未开放" already stands for.
      return t('尚未开放');
    default:
      return t('已停用');
  }
}

/** 每层的含义，给悬停提示用 */
export function layerHint(key: LayerKey): string {
  switch (key) {
    case 'ready':
      return t('可以接收请求');
    case 'busy':
      return t(
        '并发或在途 token 已到上限。每分钟请求数、突发和会话数的限制，以及按模型的冷却，只在请求到来时才知道，这里不计',
      );
    case 'resting':
      return t('分配生命周期里冷却或隔离中，到期后自动恢复');
    case 'unavailable':
      return t('被挂起，或额度已耗尽');
    case 'unopened':
      return t(
        '中转还没有被请求用到：没有任何入口的门控对它开放（或门控是 OFF），或没有已验证的映射。在「跨协议成员」里验证映射，在「门控」里开放入口',
      );
    default:
      return t('账号已停用');
  }
}

/** 一层的账号数；只有中转有的层在没有中转的号池里缺省，算 0 */
export function layerCount(accounts: SourceTierAccountLayers, key: LayerKey): number {
  return accounts[key] ?? 0;
}

export function layerTotal(accounts: SourceTierAccountLayers): number {
  return LAYERS.reduce((sum, key) => sum + layerCount(accounts, key), 0);
}

export interface LayerSegment {
  key: LayerKey;
  count: number;
  /** 占号池账号总数的百分比（0 到 100） */
  percent: number;
}

/** 状态条的分段：只含账号数大于 0 的层，按 LAYERS 的顺序 */
export function layerSegments(accounts: SourceTierAccountLayers): LayerSegment[] {
  const total = layerTotal(accounts);
  if (total <= 0) return [];
  return LAYERS.filter((key) => layerCount(accounts, key) > 0).map((key) => ({
    key,
    count: layerCount(accounts, key),
    percent: (layerCount(accounts, key) / total) * 100,
  }));
}

/** 降级率：窗口里网关自己选号池的请求中，由低于最好一级服务的比例（0 到 100，保留一位小数）；窗口里没有这样的请求时为 null */
export function degradedPercent(
  vendor: Pick<SourceTierVendorStatus, 'autoServed' | 'degraded'>,
): number | null {
  if (!(vendor.autoServed > 0)) return null;
  return Math.round((vendor.degraded / vendor.autoServed) * 1000) / 10;
}

export function formatPercent(value: number | null): string {
  return value === null ? '—' : `${value}%`;
}

/** 降级率的提示色：一半以上的自动请求落到了低级 */
export function degradedTone(value: number | null): 'success' | 'warning' | 'danger' | 'info' {
  if (value === null) return 'info';
  if (value >= 50) return 'danger';
  return value > 0 ? 'warning' : 'success';
}

/** 一个厂商此刻为什么没有按号池服务，给状态面板的说明用；在服务时为 null */
export function notServingReason(
  vendor: SourceTierVendorStatus,
  nativeTiers: boolean,
): string | null {
  if (vendor.serving) return null;
  if (!nativeTiers) return t('当前版本尚不支持按号池选号，请求仍按原有方式选号和计费');
  if (!vendor.enabled) return t('这个厂商还没有启用分级，请求仍按原有方式选号和计费');
  return t('这个厂商没有已启用的号池，请求仍按原有方式选号和计费');
}

/* ---------- 账号列表：号源级别列 ---------- */

/** 每个账号（按账号 ID）所在的号池；没有数据时为空表 */
export function placementsByAccount(
  placements: SourceTierPlacements | null | undefined,
): Map<string, SourceTierPlaced[]> {
  const result = new Map<string, SourceTierPlaced[]>();
  for (const account of placements?.accounts ?? []) result.set(account.accountId, account.tiers);
  return result;
}

/** 「L2 kiro」；账号同时在多个厂商的号池里时前面加厂商名 */
export function placedLabel(placed: SourceTierPlaced, withVendor: boolean): string {
  const tier = `L${placed.level} ${placed.name}`;
  return withVendor ? `${placed.vendorLabel} · ${tier}` : tier;
}

/** 悬停提示：账号为什么在这个号池，以及这个号池现在有没有在服务 */
export function placedHint(placed: SourceTierPlaced): string {
  const name = placed.name;
  let why: string;
  if (placed.relay) why = t('登记为跨协议中转，放在「{p0}」', { p0: name });
  else if (placed.rule === 'MEMBER_ROW') why = t('手动指定到「{p0}」', { p0: name });
  else if (placed.rule === 'CATEGORY') why = t('按账号类别归入「{p0}」', { p0: name });
  else why = t('没有号池列出它的类别，归入最低一级「{p0}」', { p0: name });
  return placed.active
    ? why
    : why + t('。现在没有按这个号池服务：厂商未启用、号池已关闭，或当前版本尚不支持。');
}

/** 账号同时在几个厂商的号池里；超过一个时，号源级别要带上厂商名 */
export function placementVendorCount(placements: SourceTierPlacements | null | undefined): number {
  return new Set(
    (placements?.accounts ?? []).flatMap((account) => account.tiers.map((placed) => placed.vendor)),
  ).size;
}

/** 「不在任何号池」的筛选值；其余筛选值是 厂商:号池标识 */
export const NO_TIER_FILTER = '__none__';

export function placedKey(placed: Pick<SourceTierPlaced, 'vendor' | 'tierId'>): string {
  return `${placed.vendor}:${placed.tierId}`;
}

export interface TierFilterOption {
  value: string;
  label: string;
}

/** 筛选下拉的选项：数据里出现过的每个号池一项，按厂商出现的顺序、级别从好到坏；多个厂商时带厂商名 */
export function tierFilterOptions(
  placements: SourceTierPlacements | null | undefined,
): TierFilterOption[] {
  const seen = new Map<string, SourceTierPlaced>();
  for (const account of placements?.accounts ?? []) {
    for (const placed of account.tiers) {
      if (!seen.has(placedKey(placed))) seen.set(placedKey(placed), placed);
    }
  }
  const vendorOrder: string[] = [];
  for (const placed of seen.values()) {
    if (!vendorOrder.includes(placed.vendor)) vendorOrder.push(placed.vendor);
  }
  const withVendor = vendorOrder.length > 1;
  return [...seen.values()]
    .sort(
      (a, b) => vendorOrder.indexOf(a.vendor) - vendorOrder.indexOf(b.vendor) || a.level - b.level,
    )
    .map((placed) => ({ value: placedKey(placed), label: placedLabel(placed, withVendor) }));
}

/** 账号是否通过号池筛选：空值不筛选；NO_TIER_FILTER 要求不在任何号池 */
export function matchesTierFilter(placed: SourceTierPlaced[] | undefined, filter: string): boolean {
  if (!filter) return true;
  if (filter === NO_TIER_FILTER) return !placed?.length;
  return !!placed?.some((entry) => placedKey(entry) === filter);
}

/* ---------- 账号列表：批量「设为号池…」 ---------- */

/** 取消指定（让账号回到按类别自动归级）在目标选择里的取值；号池标识不会是它 */
export const UNPIN = '__unpin__';

/** 一条请求里最多增改、移除的成员行数，与后端 UpsertMembers 的上限一致 */
export const MAX_BATCH_MEMBERS = 200;

export interface BatchPlan {
  /** 要发的请求，按顺序一条一条发（每条的 expectedVersion 是前一条保存后的版本）；没有可做的为空 */
  requests: UpsertSourceTierMembers[];
  /** 会被指定到目标号池的账号 */
  pin: string[];
  /** 会被取消指定的账号 */
  unpin: string[];
  /** 已经在目标号池里（或本来就没有指定）的账号，不用改 */
  unchanged: string[];
  /** 在这个厂商里登记为别的协议的中转的账号，不能当原生账号指定，已跳过 */
  skippedRelay: string[];
  /** 在别的厂商登记为专用中转的账号：它们不是任何厂商的原生成员，指定了也不会生效，已跳过（取消指定不受影响） */
  skippedElsewhere: string[];
}

/**
 * 把一批账号设为某个号池，或取消它们的手动指定。一个账号在一个厂商里只有一行，所以中转账号被跳过；
 * 在别的厂商登记为专用中转的账号（exclusiveElsewhere）设为号池时也被跳过，取消指定时照常处理，好让
 * 管理员清掉已经无效的旧指定。已经在目标号池里的不重复写。超过 200 行时拆成几条，版本依次加一。
 */
export function batchPlan(
  vendor: SourceTierVendor,
  accountIds: string[],
  target: string,
  exclusiveElsewhere: ReadonlySet<string> = new Set(),
): BatchPlan {
  const relays = new Set(
    vendor.members.filter((member) => member.protocol).map((member) => member.accountId),
  );
  const pinned = new Map(
    vendor.members.filter((member) => !member.protocol).map((member) => [member.accountId, member.tierId]),
  );
  const plan: BatchPlan = { requests: [], pin: [], unpin: [], unchanged: [], skippedRelay: [], skippedElsewhere: [] };
  for (const accountId of new Set(accountIds)) {
    if (relays.has(accountId)) plan.skippedRelay.push(accountId);
    else if (target !== UNPIN && exclusiveElsewhere.has(accountId)) plan.skippedElsewhere.push(accountId);
    else if (target === UNPIN) (pinned.has(accountId) ? plan.unpin : plan.unchanged).push(accountId);
    else (pinned.get(accountId) === target ? plan.unchanged : plan.pin).push(accountId);
  }
  let version = vendor.version;
  for (let from = 0; from < plan.pin.length; from += MAX_BATCH_MEMBERS) {
    plan.requests.push({
      expectedVersion: version,
      members: plan.pin
        .slice(from, from + MAX_BATCH_MEMBERS)
        .map((accountId) => nativeMember(accountId, target)),
    });
    version += 1;
  }
  for (let from = 0; from < plan.unpin.length; from += MAX_BATCH_MEMBERS) {
    plan.requests.push({
      expectedVersion: version,
      removeAccountIds: plan.unpin.slice(from, from + MAX_BATCH_MEMBERS),
    });
    version += 1;
  }
  return plan;
}

/* ---------- 价格预览 ---------- */

/** 目录厂商名对应的号源分级厂商标识，与后端 ModelPoolKeyResolver 的规则一致 */
export function vendorOfProvider(provider: string | null | undefined): string {
  const name = (provider ?? '').trim().toLowerCase().replace(/_/g, '-');
  if (name === 'claude') return 'anthropic';
  if (name === 'gemini') return 'google';
  return name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9.]+/g, '-')
    .replace(/^[-.]+|[-.]+$/g, '');
}

export interface PreviewTier {
  tierId: string;
  level: number;
  name: string;
  factor: number;
}

/** 价格预览要一并算的号池：这个厂商已启用的号池，按级别从好到坏；厂商没有启用分级时为空 */
export function previewTiers(
  vendor: SourceTierVendor | undefined,
  nativeTiers: boolean,
): PreviewTier[] {
  if (!vendor || !vendor.enabled || !nativeTiers) return [];
  return vendor.tiers
    .filter((tier) => tier.enabled)
    .sort((a, b) => a.level - b.level)
    .map((tier) => ({
      tierId: tier.tierId,
      level: tier.level,
      name: tier.name,
      factor: tier.priceFactor,
    }));
}
