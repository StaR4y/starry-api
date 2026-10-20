import type { ModelInfo } from '@/types/api';
import type { PlatformDefinition } from '../types';

export const anthropicPlatform: PlatformDefinition = {
  provider: 'Anthropic',
  className: 'anthropic',
  color: '#d97757',
  families: ['Claude 5', 'Claude 4', 'Claude 3', 'Routing Preset', 'CodeWhisperer Internal'],
  matches: isAnthropicModel,
  familyOf: anthropicModelFamily,
};

export function isAnthropicModel(model: ModelInfo) {
  const lowerId = model.id.toLowerCase();
  const localId = anthropicLocalId(lowerId);
  return (
    model.owned_by === 'anthropic' ||
    lowerId.startsWith('anthropic/') ||
    localId.startsWith('claude') ||
    model.id.startsWith('CLAUDE_') ||
    lowerId === 'auto' ||
    lowerId === 'simple-task'
  );
}

export function anthropicModelFamily(model: ModelInfo) {
  const lowerId = model.id.toLowerCase();
  const localId = anthropicLocalId(lowerId);
  if (model.id.startsWith('CLAUDE_')) return 'CodeWhisperer Internal';
  if (lowerId === 'auto' || lowerId === 'simple-task') return 'Routing Preset';
  if (localId.includes('3.') || localId.includes('claude-3')) return 'Claude 3';
  if (
    localId.includes('claude-5') ||
    localId.includes('fable-5') ||
    localId.includes('mythos-5') ||
    (localId.endsWith('-5') && !localId.includes('4-5') && !localId.includes('4.5'))
  ) {
    return 'Claude 5';
  }
  if (localId.startsWith('claude')) return 'Claude 4';
  return null;
}

function anthropicLocalId(id: string) {
  return id.startsWith('anthropic/') ? id.slice('anthropic/'.length) : id;
}
