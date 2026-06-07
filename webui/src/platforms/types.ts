import type { ModelInfo } from '@/types/api';

export interface PlatformDefinition {
  provider: string;
  className: string;
  color: string;
  families: string[];
  matches?: (model: ModelInfo) => boolean;
  familyOf?: (model: ModelInfo) => string | null;
}

export type SetupClient = 'all' | 'openai' | 'anthropic' | 'gemini' | 'opencode';
export type SetupPlatform = 'macos' | 'linux' | 'windows';

export type SetupOption<T extends string> = {
  value: T;
  label: string;
  description: string;
};

export type SetupContext = {
  apiOrigin: string;
  apiBaseUrl: string;
  apiKey: string;
  apiPort: string;
};

export type SetupScriptBlock = {
  client: SetupClient;
  platform: SetupPlatform;
  clientLabel: string;
  platformLabel: string;
  clientDescription: string;
  platformDescription: string;
  script: string;
};
