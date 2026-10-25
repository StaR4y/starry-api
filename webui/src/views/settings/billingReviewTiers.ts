import { t } from '@/i18n';
import type { BillingReview, BillingReviewTier } from '@/api/codingPlans';

/**
 * 费用核对里号源分级的部分。按号源分级服务的请求，预留是按它可能被服务的最贵号池定额的，所以核对要按该号池的报价
 * 估算，而不是预留金额：不然用户会为没有服务过他的号池付费。
 */

/** 核对该看哪个号池：已结算过的取实际服务的，否则取最后派发到的；不是按号池服务的请求为 null */
export function reviewTier(review: BillingReview): BillingReviewTier | null {
  return review.served ?? review.last_dispatched ?? null;
}

/** 「L2 kiro」；缺名称时只有号池引用 */
export function reviewTierLabel(tier: BillingReviewTier): string {
  const name = tier.name || tier.tierRef;
  return tier.level != null ? `L${tier.level} ${name}` : name;
}

/** 核对对话框里金额的默认值：已知实际金额的用实际金额；按号池服务的用该号池的估算，而不是最大预留；其余用预留 */
export function suggestedCredits(review: BillingReview): string {
  if (review.actual_credits != null) return String(review.actual_credits);
  const tier = reviewTier(review);
  const estimate = tier?.estimate;
  return estimate != null && estimate !== '' ? estimate : String(review.reserved_credits);
}

/** 每 token 的报价换成每百万 token 的美元数，去掉末尾的 0；不是数字时原样返回 */
export function perMillion(rate: string | null | undefined): string {
  if (rate == null || rate === '') return '—';
  const value = Number(rate);
  if (!Number.isFinite(value)) return String(rate);
  return String(Number((value * 1_000_000).toPrecision(8)));
}

/** 这个号池的预留估算比预留金额少多少；没有估算或不更少时为 null */
export function reservedMinusEstimate(review: BillingReview, tier: BillingReviewTier): number | null {
  const estimate = Number(tier.estimate);
  const reserved = Number(review.reserved_credits);
  if (!Number.isFinite(estimate) || !Number.isFinite(reserved) || estimate >= reserved) return null;
  return Math.round((reserved - estimate) * 1_000_000) / 1_000_000;
}

/** 这个核对行里的号池是实际服务的，还是只是最后派发到的 */
export function reviewTierKind(review: BillingReview): string {
  return review.served ? t('实际服务') : t('最后派发');
}
