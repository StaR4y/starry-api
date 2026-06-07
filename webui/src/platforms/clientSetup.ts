import {
  anthropicConnectionLine,
  anthropicPowerShellEnvLines,
  anthropicUnixEnvLines,
} from './anthropic/setup';
import {
  geminiConnectionLine,
  geminiPowerShellEnvLines,
  geminiUnixEnvLines,
} from './gemini/setup';
import {
  openAiConnectionLine,
  openAiPowerShellEnvLines,
  openAiUnixEnvLines,
} from './openai/setup';
import {
  openCodeConnectionLine,
  openCodePowerShellConfigLines,
  openCodePowerShellEnvLines,
  openCodeUnixConfigLines,
  openCodeUnixEnvLines,
} from './opencode/setup';
import { starryPowerShellEnvLines, starryUnixEnvLines } from './setupShared';
import type {
  SetupClient,
  SetupContext,
  SetupOption,
  SetupPlatform,
  SetupScriptBlock,
} from './types';

export type {
  SetupClient,
  SetupContext,
  SetupOption,
  SetupPlatform,
  SetupScriptBlock,
} from './types';

export const setupClients: Array<SetupOption<SetupClient>> = [
  { value: 'all', label: '通用', description: '同时写入 OpenAI、Anthropic、Gemini 与 OpenCode 配置' },
  { value: 'openai', label: 'OpenAI', description: '适用于 OpenAI 兼容客户端' },
  { value: 'anthropic', label: 'Claude Code', description: '适用于 Anthropic/Claude Code 兼容客户端' },
  { value: 'gemini', label: 'Gemini', description: '适用于 Gemini 兼容客户端' },
  { value: 'opencode', label: 'OpenCode', description: '生成项目级 opencode.json，并写入所需环境变量' },
];

export const setupPlatforms: Array<SetupOption<SetupPlatform>> = [
  { value: 'macos', label: 'macOS', description: '写入当前用户 zsh profile' },
  { value: 'linux', label: 'Linux', description: '自动适配 bash / zsh profile' },
  { value: 'windows', label: 'Windows PowerShell', description: '写入当前用户 PowerShell profile' },
];

type UnixSetupPlatform = 'macos' | 'linux';

export function createConnectionText(context: SetupContext) {
  return [
    openAiConnectionLine(context),
    anthropicConnectionLine(context),
    geminiConnectionLine(context),
    openCodeConnectionLine(context),
    `API Key: ${context.apiKey}`,
    `Port: ${context.apiPort}`,
  ].join('\n');
}

export function createSetupScriptBlock(
  client: SetupClient,
  platform: SetupPlatform,
  context: SetupContext,
): SetupScriptBlock {
  const clientMeta = clientOption(client);
  const platformMeta = platformOption(platform);
  return {
    client,
    platform,
    clientLabel: clientMeta.label,
    platformLabel: platformMeta.label,
    clientDescription: clientMeta.description,
    platformDescription: platformMeta.description,
    script: buildSetupScript(client, platform, context),
  };
}

function clientOption(client: SetupClient) {
  return setupClients.find((item) => item.value === client) ?? setupClients[0];
}

function platformOption(platform: SetupPlatform) {
  return setupPlatforms.find((item) => item.value === platform) ?? setupPlatforms[0];
}

function includeOpenAiEnv(client: SetupClient) {
  return client === 'all' || client === 'openai';
}

function includeClaudeEnv(client: SetupClient) {
  return client === 'all' || client === 'anthropic';
}

function includeGeminiEnv(client: SetupClient) {
  return client === 'all' || client === 'gemini';
}

function includeOpenCodeConfig(client: SetupClient) {
  return client === 'all' || client === 'opencode';
}

function buildSetupScript(client: SetupClient, platform: SetupPlatform, context: SetupContext) {
  if (platform === 'windows') {
    return buildWindowsSetupScript(client, context);
  }
  return buildUnixSetupScript(client, platform, context);
}

function unixEnvLines(client: SetupClient, context: SetupContext) {
  const lines: string[] = [];
  if (includeOpenAiEnv(client)) lines.push(...openAiUnixEnvLines(context));
  if (includeClaudeEnv(client)) lines.push(...anthropicUnixEnvLines(context));
  if (includeGeminiEnv(client)) lines.push(...geminiUnixEnvLines(context));
  if (includeOpenCodeConfig(client)) lines.push(...openCodeUnixEnvLines(context));
  lines.push(...starryUnixEnvLines(context));
  return lines;
}

function powershellEnvLines(client: SetupClient, context: SetupContext) {
  const lines: string[] = [];
  if (includeOpenAiEnv(client)) lines.push(...openAiPowerShellEnvLines(context));
  if (includeClaudeEnv(client)) lines.push(...anthropicPowerShellEnvLines(context));
  if (includeGeminiEnv(client)) lines.push(...geminiPowerShellEnvLines(context));
  if (includeOpenCodeConfig(client)) lines.push(...openCodePowerShellEnvLines(context));
  lines.push(...starryPowerShellEnvLines(context));
  return lines;
}

function buildUnixSetupScript(client: SetupClient, platform: UnixSetupPlatform, context: SetupContext) {
  const envLines = unixEnvLines(client, context);
  const profileLine =
    platform === 'macos'
      ? `PROFILE="\${ZDOTDIR:-$HOME}/.zshrc"`
      : `PROFILE="$HOME/.bashrc"; [ -n "$ZSH_VERSION" ] && PROFILE="\${ZDOTDIR:-$HOME}/.zshrc"`;
  const lines = [
    profileLine,
    `BEGIN="# >>> starryapi env >>>"`,
    `END="# <<< starryapi env <<<"`,
    `touch "$PROFILE"`,
    `sed -i.bak "/$BEGIN/,/$END/d" "$PROFILE"`,
    `cat >> "$PROFILE" <<'EOF'`,
    `# >>> starryapi env >>>`,
    ...envLines,
    `# <<< starryapi env <<<`,
    `EOF`,
    ...envLines,
  ];

  if (includeOpenCodeConfig(client)) {
    lines.push(...openCodeUnixConfigLines(context));
  }
  lines.push(`echo "saved: $PROFILE"`);
  return lines.join('\n');
}

function buildWindowsSetupScript(client: SetupClient, context: SetupContext) {
  const envLines = powershellEnvLines(client, context);
  const lines = [
    `New-Item -ItemType Directory -Force (Split-Path $PROFILE) | Out-Null`,
    `$begin = '# >>> starryapi env >>>'`,
    `$end = '# <<< starryapi env <<<'`,
    `$content = if (Test-Path $PROFILE) { Get-Content $PROFILE -Raw } else { '' }`,
    `$pattern = '(?s)\\r?\\n?# >>> starryapi env >>>.*?# <<< starryapi env <<<\\r?\\n?'`,
    `[regex]::Replace($content, $pattern, '') | Set-Content -Path $PROFILE -Encoding UTF8`,
    `@'`,
    `# >>> starryapi env >>>`,
    ...envLines,
    `# <<< starryapi env <<<`,
    `'@ | Add-Content -Path $PROFILE -Encoding UTF8`,
    ...envLines,
  ];

  if (includeOpenCodeConfig(client)) {
    lines.push(...openCodePowerShellConfigLines(context));
  }
  lines.push(`Write-Host "saved: $PROFILE"`);
  return lines.join('\n');
}
