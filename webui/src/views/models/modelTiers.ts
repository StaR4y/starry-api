import { t } from '@/i18n';
import type { ModelSourceTier } from '@/types/api';
import { formatFactor } from '@/views/settings/components/source-tiers/sourceTiersMeta';
import { modelPriceChip } from './catalogDisplay';

/** 请求里指定号池的写法：`模型@号池标识`（见 docs/API.md「指定号池」），不加 + 时请求只由这个号池服务 */
export function tierSuffix(modelId: string, tierId: string): string {
  return `${modelId}@${tierId}`;
}

/** 模型卡片上的一个号池：名称、系数、乘过系数的参考价，可选的号池还带可复制的写法 */
export interface ModelTierChip {
  id: string;
  name: string;
  /** 价格系数，如 ×0.6 */
  factor: string;
  /** 乘过系数的参考总价（每百万 token）；号池没有价格时为 null */
  price: string | null;
  /** 复制出去的 `模型@标识`；请求不能指定的号池（用户可选已关闭）为 null */
  suffix: string | null;
  /** 悬停提示：怎么用这个写法，或为什么不能 */
  title: string;
}

/** 模型列表里一个模型的号池，从好到差；没有号池（厂商没有分级或没有公开号池名称）时为空 */
export function modelTierChips(
  modelId: string,
  tiers: ModelSourceTier[] | null | undefined,
): ModelTierChip[] {
  return [...(tiers ?? [])]
    .sort((left, right) => left.level - right.level)
    .map((tier) => {
      const suffix = tier.selectable ? tierSuffix(modelId, tier.id) : null;
      return {
        id: tier.id,
        name: tier.name,
        factor: `×${formatFactor(tier.priceFactor)}`,
        price: modelPriceChip(tier.pricing)?.text ?? null,
        suffix,
        title: suffix
          ? t('{p0} 级。复制 {p1}：请求只由这个号池服务', { p0: tier.level, p1: suffix })
          : t('{p0} 级。请求不能指定这个号池', { p0: tier.level }),
      };
    });
}
