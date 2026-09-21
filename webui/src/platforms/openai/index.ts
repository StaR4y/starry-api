import type { ModelInfo } from '@/types/api';
import type { PlatformDefinition } from '../types';

export const openAiPlatform: PlatformDefinition = {
  provider: 'OpenAI',
  className: 'openai',
  color: '#10a37f',
  families: [
    'GPT-6',
    'GPT-5',
    'GPT-4',
    'GPT-3.5',
    'OpenAI Reasoning',
    'OpenAI OSS',
    'OpenAI Audio',
    'OpenAI Image',
    'OpenAI',
  ],
  matches: isOpenAiModel,
  familyOf: openAiModelFamily,
};

export function isOpenAiModel(model: ModelInfo) {
  const lowerId = model.id.toLowerCase();
  const localId = openAiLocalId(lowerId);
  return (
    lowerId.startsWith('openai/') ||
    localId.startsWith('gpt-') ||
    localId.startsWith('chatgpt-') ||
    /^o\d/.test(localId)
  );
}

export function openAiModelFamily(model: ModelInfo) {
  const lowerId = model.id.toLowerCase();
  const localId = openAiLocalId(lowerId);
  if (localId.startsWith('gpt-6')) return 'GPT-6';
  if (localId.startsWith('gpt-5')) return 'GPT-5';
  if (localId.startsWith('gpt-4') || localId.startsWith('chatgpt-4')) return 'GPT-4';
  if (localId.startsWith('gpt-3.5')) return 'GPT-3.5';
  if (/^o\d/.test(localId)) return 'OpenAI Reasoning';
  if (localId.startsWith('gpt-oss')) return 'OpenAI OSS';
  if (localId.includes('audio')) return 'OpenAI Audio';
  if (localId.includes('image')) return 'OpenAI Image';
  if (model.id.toLowerCase().startsWith('openai/')) return 'OpenAI';
  return null;
}

function openAiLocalId(id: string) {
  return id.startsWith('openai/') ? id.slice('openai/'.length) : id;
}
