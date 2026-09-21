import type { ModelInfo } from '@/types/api';
import { anthropicPlatform } from './anthropic';
import { deepSeekPlatform } from './deepseek';
import { geminiPlatform } from './gemini';
import { openAiPlatform } from './openai';
import { otherPlatform } from './other';

const providerPlatforms = [openAiPlatform, anthropicPlatform, geminiPlatform, deepSeekPlatform];

export const platformDefinitions = [...providerPlatforms, otherPlatform];

const providerOrder = platformDefinitions.map((item) => item.provider);
const familyOrder = platformDefinitions.flatMap((item) => item.families);

export function getProviderOrder() {
  return providerOrder;
}

export function getFamilyOrder() {
  return familyOrder;
}

export function canonicalCatalogProvider(provider: string | null | undefined) {
  const value = (provider ?? '').trim();
  if (!value) return value;
  if (value.toLowerCase() === 'gemini') return 'Google';
  return value;
}

export function withCanonicalCatalogProvider<T extends { provider?: string | null }>(model: T): T {
  const provider = canonicalCatalogProvider(model.provider);
  return provider && provider !== model.provider ? { ...model, provider } : model;
}

export function platformClass(provider: string) {
  const canonical = canonicalCatalogProvider(provider);
  return (
    platformDefinitions.find((item) => item.provider === canonical)?.className ??
    otherPlatform.className
  );
}

export function inferModelProvider(model: ModelInfo) {
  if (model.provider) {
    return canonicalCatalogProvider(model.provider);
  }
  return (
    providerPlatforms.find((platform) => platform.matches?.(model))?.provider ??
    otherPlatform.provider
  );
}

export function inferModelFamily(model: ModelInfo) {
  if (model.family) {
    return model.family;
  }

  const provider = inferModelProvider(model);
  return (
    platformDefinitions.find((platform) => platform.provider === provider)?.familyOf?.(model) ??
    otherPlatform.families[0]
  );
}

export function orderOf<T>(order: T[], value: T) {
  const index = order.indexOf(value);
  return index === -1 ? order.length : index;
}
