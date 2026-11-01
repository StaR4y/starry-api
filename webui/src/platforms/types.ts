import type { ModelInfo } from '@/types/api';

export interface PlatformDefinition {
  provider: string;
  className: string;
  color: string;
  families: string[];
  matches?: (model: ModelInfo) => boolean;
  familyOf?: (model: ModelInfo) => string | null;
}

export type SetupClient = 'codex' | 'claude' | 'opencode';
export type SetupPlatform = 'unix' | 'cmd' | 'powershell';

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
  /** 配置里写入的号池：号池标识，可带 +（`kiro`、`kiro+`）；缺省或 auto 时不写，请求按 Key 的号源偏好 */
  tier?: string | null;
};

export type SetupScriptBlock = {
  client: SetupClient;
  platform: SetupPlatform;
  clientLabel: string;
  platformLabel: string;
  clientDescription: string;
  platformDescription: string;
  files: SetupFileBlock[];
  script: string;
};

export type SetupFileBlock = {
  path: string;
  content: string;
  hint?: string;
};

export type CcSwitchLink = {
  client: 'claude' | 'codex' | 'opencode';
  label: string;
  description: string;
  url: string;
};
