import type { ModelInfo } from '@/types/api';
import type { PlatformDefinition } from '../types';

export const deepSeekPlatform: PlatformDefinition = {
  provider: 'DeepSeek',
  className: 'deepseek',
  color: '#4d6bfe',
  families: ['DeepSeek V4'],
  matches: isDeepSeekModel,
  familyOf: deepSeekModelFamily,
};

export function isDeepSeekModel(model: ModelInfo) {
  const lowerId = model.id.toLowerCase();
  return lowerId.startsWith('deepseek-') || lowerId.startsWith('deepseek/');
}

export function deepSeekModelFamily(model: ModelInfo) {
  return isDeepSeekModel(model) ? 'DeepSeek V4' : null;
}
