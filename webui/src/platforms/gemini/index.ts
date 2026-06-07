import type { ModelInfo } from '@/types/api';
import type { PlatformDefinition } from '../types';

export const geminiPlatform: PlatformDefinition = {
  provider: 'Gemini',
  className: 'gemini',
  color: '#4285f4',
  families: ['Gemini 3.5', 'Gemini 3.1', 'Gemini 3', 'Gemini 2.5', 'Gemini 2.0', 'Gemini 1.5', 'Gemma 4', 'Gemma 3', 'Gemma 2', 'Lyria', 'Google'],
  matches: isGeminiModel,
  familyOf: geminiModelFamily,
};

export function isGeminiModel(model: ModelInfo) {
  const lowerId = model.id.toLowerCase();
  const localId = googleLocalId(lowerId);
  return lowerId.startsWith('google/') || localId.startsWith('gemini') || localId.startsWith('gemma') || localId.startsWith('lyria');
}

export function geminiModelFamily(model: ModelInfo) {
  const lowerId = model.id.toLowerCase();
  const localId = googleLocalId(lowerId);
  if (localId.startsWith('gemini-3.5')) return 'Gemini 3.5';
  if (localId.startsWith('gemini-3.1')) return 'Gemini 3.1';
  if (localId.startsWith('gemini-3')) return 'Gemini 3';
  if (localId.startsWith('gemini-2.5')) return 'Gemini 2.5';
  if (localId.startsWith('gemini-2.0')) return 'Gemini 2.0';
  if (localId.startsWith('gemini-1.5')) return 'Gemini 1.5';
  if (localId.startsWith('gemma-4')) return 'Gemma 4';
  if (localId.startsWith('gemma-3')) return 'Gemma 3';
  if (localId.startsWith('gemma-2')) return 'Gemma 2';
  if (localId.startsWith('lyria-')) return 'Lyria';
  if (lowerId.startsWith('google/')) return 'Google';
  return null;
}

function googleLocalId(id: string) {
  return id.startsWith('google/') ? id.slice('google/'.length) : id;
}
