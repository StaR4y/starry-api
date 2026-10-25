import { t } from '@/i18n';
import type { SourceTierChoice } from '@/api/sourceTiers';
import type { ModelPublicPricing } from '@/types/api';
import { formatTokenPrice } from '@/views/models/catalogDisplay';
import { formatFactor } from '@/views/settings/components/source-tiers/sourceTiersMeta';

/**
 * API Key 的号源偏好（设计 6.1）：没有偏好时的值，与后端 api_keys.tier_preference 的默认值一致。
 * 其余取值写法同 `模型@标识` 的后缀：号池标识、L2 或 2，后面可以加 + 表示「它和它以下」。
 */
export const AUTO_PREFERENCE = 'auto';

/** 表单里的值：缺省、空白和任何大小写的 auto 都是 auto；其余转小写（后端也是这样保存的） */
export function preferenceValue(stored: string | null | undefined): string {
  const text = (stored ?? '').trim().toLowerCase();
  return text === '' ? AUTO_PREFERENCE : text;
}

export interface PreferenceChoice {
  /** 提交给后端的值 */
  value: string;
  /** 下拉框收起时显示的文字 */
  label: string;
  /** 展开时的第二行：价格或含义 */
  hint: string;
}

/**
 * 参考模型的价格，没有价格时为 null。数字是余额单位，不是美元：部署的计费倍率不是 1 时，它和官方美元价不相等
 * （docs/API.md「计费」），所以不带货币符号，写法和模型页上同一个号池的价格一样（formatTokenPrice）。
 */
export function priceText(pricing: ModelPublicPricing | null | undefined): string | null {
  if (!pricing) return null;
  const { inputPricePerMillionTokens: input, outputPricePerMillionTokens: output } = pricing;
  if (input != null && output != null) {
    return t('输入 {p0}、输出 {p1}（每百万 token）', { p0: formatTokenPrice(input), p1: formatTokenPrice(output) });
  }
  if (pricing.totalPricePerMillionTokens != null) {
    return t('{p0}（每百万 token）', { p0: formatTokenPrice(pricing.totalPricePerMillionTokens) });
  }
  return null;
}

function tierHint(tier: SourceTierChoice, referenceModel: string | null | undefined): string {
  const factor = t('标准价 × {p0}', { p0: formatFactor(tier.priceFactor) });
  const price = priceText(tier.pricing);
  return price && referenceModel
    ? t('{p0}；以 {p1} 计：{p2}', { p0: factor, p1: referenceModel, p2: price })
    : factor;
}

/**
 * 表单里「号源偏好」的选项：自动，然后每个可选的号池两项（只用它；它和它以下）。偏好已经是别的值（号池改名、
 * 停用，或管理员没有公开号池名称）时，把当前值作为一项保留，不让保存悄悄把它改成别的。这一项只说它没有列出，
 * 不说请求会怎样：没有列出可能只是名称没有公开，号池还在时请求仍按它服务。
 */
export function preferenceChoices(
  tiers: SourceTierChoice[],
  referenceModel: string | null | undefined,
  current: string,
): PreferenceChoice[] {
  const choices: PreferenceChoice[] = [
    { value: AUTO_PREFERENCE, label: t('自动'), hint: t('按号池顺序，从 1 级开始') },
  ];
  for (const tier of [...tiers].sort((a, b) => a.level - b.level)) {
    choices.push({
      value: tier.id,
      label: t('{p0}（{p1} 级，系数 ×{p2}）', {
        p0: tier.name,
        p1: tier.level,
        p2: formatFactor(tier.priceFactor),
      }),
      hint: tierHint(tier, referenceModel),
    });
    choices.push({
      value: `${tier.id}+`,
      label: t('{p0} 及以下', { p0: tier.name }),
      hint: t('由它和它以下的号池服务，按实际服务的号池计费'),
    });
  }
  const held = preferenceValue(current);
  if (!choices.some((choice) => choice.value === held)) {
    choices.push(heldChoice(held, tiers));
  }
  return choices;
}

/** 按级别写的偏好：`l2`、`2`、`2+`（后端的写法，API 也接受），级别 1 到 9 */
const LEVEL_PREFERENCE = /^l?([1-9])(\+?)$/;

/**
 * 偏好已经是列表以外的值时保留的那一项。按级别写的偏好（`l2`、`2+`），在列出的号池里有这一级时按那个号池
 * 命名并说明它是按级别选的，不当作「没有列出」；其余的才说没有列出。
 */
function heldChoice(held: string, tiers: SourceTierChoice[]): PreferenceChoice {
  const match = LEVEL_PREFERENCE.exec(held);
  const listed = match ? tiers.find((tier) => tier.level === Number(match[1])) : undefined;
  if (listed) {
    return match![2] === '+'
      ? {
          value: held,
          label: t('{p0} 及以下（{p1} 级起）', { p0: listed.name, p1: listed.level }),
          hint: t('当前设置，按级别选择：由 {p0} 级的号池和它以下的号池服务；号池的顺序调整后，选中的号池跟着变', { p0: listed.level }),
        }
      : {
          value: held,
          label: t('{p0}（{p1} 级）', { p0: listed.name, p1: listed.level }),
          hint: t('当前设置，按级别选择：由 {p0} 级的号池服务；号池的顺序调整后，选中的号池跟着变', { p0: listed.level }),
        };
  }
  return {
    value: held,
    label: held,
    hint: t('当前设置。这里没有列出它：号池名称可能没有公开，或号池已不存在、不可选；号池还在且可选时，请求仍按它服务'),
  };
}
