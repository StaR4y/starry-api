import type { ModelInfo } from '@/types/api';
import { anthropicPlatform } from './anthropic';
import { geminiPlatform } from './gemini';
import { openAiPlatform } from './openai';
import { otherPlatform } from './other';

const providerPlatforms = [openAiPlatform, anthropicPlatform, geminiPlatform];

export const platformDefinitions = [...providerPlatforms, otherPlatform];

const providerOrder = platformDefinitions.map((item) => item.provider);
const familyOrder = platformDefinitions.flatMap((item) => item.families);

export function getProviderOrder() {
  return providerOrder;
}

export function getFamilyOrder() {
  return familyOrder;
}

export function platformClass(provider: string) {
  return platformDefinitions.find((item) => item.provider === provider)?.className ?? otherPlatform.className;
}

export function inferModelProvider(model: ModelInfo) {
  if (model.provider) {
    return model.provider;
  }
  return providerPlatforms.find((platform) => platform.matches?.(model))?.provider ?? otherPlatform.provider;
}

export function inferModelFamily(model: ModelInfo) {
  if (model.family) {
    return model.family;
  }

  const provider = inferModelProvider(model);
  return platformDefinitions.find((platform) => platform.provider === provider)?.familyOf?.(model) ?? otherPlatform.families[0];
}

export function orderOf<T>(order: T[], value: T) {
  const index = order.indexOf(value);
  return index === -1 ? order.length : index;
}
