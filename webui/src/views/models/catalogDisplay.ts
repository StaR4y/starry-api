import { locale, t } from '@/i18n';
import type {
  ModelCatalogCardStyle,
  ModelCatalogDensity,
  ModelCatalogDisplaySettings,
  ModelCatalogGroupBy,
  ModelCatalogLayout,
  ModelInfo,
  ModelPublicPricing,
} from '@/types/api';

export const DEFAULT_MODEL_CATALOG_DISPLAY: ModelCatalogDisplaySettings = {
  layout: 'cards',
  density: 'comfortable',
  columns: 0,
  showIcon: true,
  showFamily: true,
  showContext: true,
  showOrigin: true,
  showDescription: false,
  groupBy: 'provider',
  cardStyle: 'default',
};

const LAYOUTS: ModelCatalogLayout[] = ['cards', 'list', 'compact'];
const DENSITIES: ModelCatalogDensity[] = ['comfortable', 'compact'];
const GROUP_BY: ModelCatalogGroupBy[] = ['provider', 'none'];
const CARD_STYLES: ModelCatalogCardStyle[] = ['default', 'outline', 'flat'];

function pick<T extends string>(value: string | undefined, allowed: T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback;
}

export function formatContextWindow(value: number) {
  if (value >= 1_000_000) return `${Number((value / 1_000_000).toFixed(1))}M`;
  if (value >= 1000) return `${Number((value / 1000).toFixed(1))}K`;
  return value.toLocaleString(locale.value);
}

const TOKENS_PER_MILLION = 1_000_000;

function numberOrNull(value: number | null | undefined) {
  if (value === null || value === undefined) return null;
  const normalized = Number(value);
  return Number.isFinite(normalized) ? normalized : null;
}

function positiveNumber(value: number | null | undefined) {
  const normalized = numberOrNull(value);
  return normalized !== null && normalized > 0 ? normalized : null;
}

function priceFromCapacity(capacity: number | null | undefined) {
  const normalized = positiveNumber(capacity);
  return normalized === null ? null : (100 * TOKENS_PER_MILLION) / normalized;
}

function effectivePrice(exact: number | null | undefined, capacity: number | null | undefined) {
  const exactPrice = numberOrNull(exact);
  return exactPrice === null ? priceFromCapacity(capacity) : exactPrice;
}

function formatTokenPrice(value: number) {
  return value > 0 && value < 0.000001
    ? value.toExponential(2)
    : value.toLocaleString(locale.value, { maximumFractionDigits: 6 });
}

export function pricingFromPublicModel(model: ModelInfo): ModelPublicPricing | null {
  if (
    model.billingMode == null &&
    model.totalPricePerMillionTokens == null &&
    model.inputPricePerMillionTokens == null &&
    model.outputPricePerMillionTokens == null &&
    model.tokensPer100Balance == null &&
    model.inputTokensPer100Balance == null
  ) {
    return null;
  }
  return {
    billingMode: model.billingMode || 'total',
    tokensPer100Balance: model.tokensPer100Balance,
    inputTokensPer100Balance: model.inputTokensPer100Balance,
    outputTokensPer100Balance: model.outputTokensPer100Balance,
    cacheWriteTokensPer100Balance: model.cacheWriteTokensPer100Balance,
    cacheReadTokensPer100Balance: model.cacheReadTokensPer100Balance,
    totalPricePerMillionTokens: model.totalPricePerMillionTokens,
    inputPricePerMillionTokens: model.inputPricePerMillionTokens,
    outputPricePerMillionTokens: model.outputPricePerMillionTokens,
    cacheWritePricePerMillionTokens: model.cacheWritePricePerMillionTokens,
    cacheReadPricePerMillionTokens: model.cacheReadPricePerMillionTokens,
  };
}

export function modelPriceChip(pricing: ModelPublicPricing | null | undefined) {
  if (!pricing) return null;
  if (pricing.billingMode === 'unavailable')
    return {
      get text() {
        return t('待定价');
      },
      get title() {
        return t('价格未配置，暂不可调用');
      },
    };
  const input = effectivePrice(
    pricing.inputPricePerMillionTokens,
    pricing.inputTokensPer100Balance,
  );
  const output = effectivePrice(
    pricing.outputPricePerMillionTokens,
    pricing.outputTokensPer100Balance,
  );
  const total =
    effectivePrice(pricing.totalPricePerMillionTokens, pricing.tokensPer100Balance) ??
    (input !== null && output !== null ? (input + output) / 2 : (input ?? output));
  if (total === null) return null;
  return {
    text: `${formatTokenPrice(total)} / MTok`,
    title:
      input !== null && output !== null
        ? t('参考总价（输入 / 输出各占 50%）；实际按输入 {p0}、输出 {p1} / MTok 及缓存用量计费', {
            p0: formatTokenPrice(input),
            p1: formatTokenPrice(output),
          })
        : t('每百万 token 固定总量余额价'),
  };
}

export function normalizeModelCatalogDisplay(
  value?: Partial<ModelCatalogDisplaySettings> | null,
): ModelCatalogDisplaySettings {
  const source = value ?? {};
  const columns = Number(source.columns);
  return {
    layout: pick(source.layout, LAYOUTS, DEFAULT_MODEL_CATALOG_DISPLAY.layout),
    density: pick(source.density, DENSITIES, DEFAULT_MODEL_CATALOG_DISPLAY.density),
    columns: [0, 1, 2, 3, 4].includes(columns) ? columns : DEFAULT_MODEL_CATALOG_DISPLAY.columns,
    showIcon: source.showIcon ?? DEFAULT_MODEL_CATALOG_DISPLAY.showIcon,
    showFamily: source.showFamily ?? DEFAULT_MODEL_CATALOG_DISPLAY.showFamily,
    showContext: source.showContext ?? DEFAULT_MODEL_CATALOG_DISPLAY.showContext,
    showOrigin: source.showOrigin ?? DEFAULT_MODEL_CATALOG_DISPLAY.showOrigin,
    showDescription: source.showDescription ?? DEFAULT_MODEL_CATALOG_DISPLAY.showDescription,
    groupBy: pick(source.groupBy, GROUP_BY, DEFAULT_MODEL_CATALOG_DISPLAY.groupBy),
    cardStyle: pick(source.cardStyle, CARD_STYLES, DEFAULT_MODEL_CATALOG_DISPLAY.cardStyle),
  };
}
