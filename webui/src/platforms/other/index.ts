import type { PlatformDefinition } from '../types';

export const otherPlatform: PlatformDefinition = {
  provider: 'Other',
  className: 'other',
  color: 'var(--kp-primary)',
  families: ['Other'],
  familyOf: () => 'Other',
};
