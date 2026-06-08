import { normalizedApiKey, psQuote, shQuote } from './setupShared';
import type {
  CcSwitchLink,
  SetupClient,
  SetupContext,
  SetupFileBlock,
  SetupOption,
  SetupPlatform,
  SetupScriptBlock,
} from './types';

export type {
  CcSwitchLink,
  SetupClient,
  SetupContext,
  SetupFileBlock,
  SetupOption,
  SetupPlatform,
  SetupScriptBlock,
} from './types';

const CODEX_MODEL = 'gpt-5.5';
const CODEX_REVIEW_MODEL = 'gpt-5.5';
const CLAUDE_MODEL = 'claude-sonnet-4.5';
const CLAUDE_FAST_MODEL = 'claude-haiku-4.5';
const OPENCODE_MODEL = 'starry/gpt-5.5';
const OPENCODE_SMALL_MODEL = 'starry/gpt-5.4-mini';

export const setupClients: Array<SetupOption<SetupClient>> = [
  { value: 'codex', label: 'Codex CLI', description: '使用 API Key 的 Codex HTTP Responses 配置' },
  { value: 'claude', label: 'Claude Code', description: '使用 Anthropic 兼容环境变量连接 Claude Code' },
  { value: 'opencode', label: 'OpenCode', description: '生成项目级 opencode.json 配置' },
];

export const setupPlatforms: Array<SetupOption<SetupPlatform>> = [
  { value: 'unix', label: 'macOS / Linux', description: '适用于 bash、zsh、fish 等类 Unix 终端' },
  { value: 'cmd', label: 'Windows CMD', description: '适用于 Windows Command Prompt' },
  { value: 'powershell', label: 'PowerShell', description: '适用于 Windows PowerShell 或 PowerShell Core' },
];

export function createConnectionText(context: SetupContext) {
  return [
    `Codex / OpenAI Base URL: ${context.apiBaseUrl}`,
    `Claude Code Base URL: ${context.apiOrigin}`,
    `OpenCode Base URL: ${context.apiBaseUrl}`,
    `API Key: ${normalizedApiKey(context.apiKey)}`,
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
  const files = buildSetupFiles(client, platform, context);
  return {
    client,
    platform,
    clientLabel: clientMeta.label,
    platformLabel: platformMeta.label,
    clientDescription: clientMeta.description,
    platformDescription: platformMeta.description,
    files,
    script: copyableFilesText(files),
  };
}

export function createCcSwitchLinks(context: SetupContext): CcSwitchLink[] {
  const apiKey = normalizedApiKey(context.apiKey);
  return [
    {
      client: 'claude',
      label: 'Claude Code',
      description: '导入 Claude Code 的 Anthropic 兼容供应商',
      url: ccSwitchProviderUrl({
        app: 'claude',
        name: 'Starry API Claude',
        homepage: context.apiOrigin,
        endpoint: context.apiOrigin,
        apiKey,
        model: CLAUDE_MODEL,
      }),
    },
    {
      client: 'codex',
      label: 'Codex CLI',
      description: '导入 Codex CLI 的 OpenAI Responses 供应商',
      url: ccSwitchProviderUrl({
        app: 'codex',
        name: 'Starry API Codex',
        homepage: context.apiOrigin,
        endpoint: context.apiBaseUrl,
        apiKey,
        model: CODEX_MODEL,
      }),
    },
  ];
}

function clientOption(client: SetupClient) {
  return setupClients.find((item) => item.value === client) ?? setupClients[0];
}

function platformOption(platform: SetupPlatform) {
  return setupPlatforms.find((item) => item.value === platform) ?? setupPlatforms[0];
}

function buildSetupFiles(client: SetupClient, platform: SetupPlatform, context: SetupContext): SetupFileBlock[] {
  switch (client) {
    case 'codex':
      return codexFiles(platform, context);
    case 'claude':
      return claudeFiles(platform, context);
    case 'opencode':
      return openCodeFiles(context);
  }
}

function codexFiles(platform: SetupPlatform, context: SetupContext): SetupFileBlock[] {
  const dir = codexConfigDir(platform);
  return [
    {
      path: joinConfigPath(dir, 'config.toml', platform),
      hint: '保存到 Codex 用户级配置；不要放在项目 .codex/config.toml 中。',
      content: codexConfigToml(context),
    },
    {
      path: joinConfigPath(dir, 'auth.json', platform),
      hint: 'API Key 会写入 Codex 本地鉴权文件，请妥善保管。',
      content: JSON.stringify({ OPENAI_API_KEY: normalizedApiKey(context.apiKey) }, null, 2),
    },
  ];
}

function claudeFiles(platform: SetupPlatform, context: SetupContext): SetupFileBlock[] {
  const vars = [
    ['ANTHROPIC_BASE_URL', context.apiOrigin],
    ['ANTHROPIC_API_KEY', normalizedApiKey(context.apiKey)],
    ['ANTHROPIC_AUTH_TOKEN', normalizedApiKey(context.apiKey)],
    ['ANTHROPIC_MODEL', CLAUDE_MODEL],
    ['ANTHROPIC_SMALL_FAST_MODEL', CLAUDE_FAST_MODEL],
    ['CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC', '1'],
    ['CLAUDE_CODE_ATTRIBUTION_HEADER', '0'],
  ] satisfies Array<[string, string]>;

  return [
    {
      path: platformLabelForCode(platform),
      content: envLines(platform, vars).join('\n'),
    },
  ];
}

function openCodeFiles(context: SetupContext): SetupFileBlock[] {
  return [
    {
      path: 'opencode.json',
      hint: '放在当前项目根目录；文件内包含 API Key。',
      content: openCodeJson(context),
    },
  ];
}

function codexConfigDir(platform: SetupPlatform) {
  switch (platform) {
    case 'cmd':
      return '%USERPROFILE%\\.codex';
    case 'powershell':
      return '$HOME\\.codex';
    case 'unix':
      return '~/.codex';
  }
}

function joinConfigPath(dir: string, file: string, platform: SetupPlatform) {
  return platform === 'unix' ? `${dir}/${file}` : `${dir}\\${file}`;
}

function codexConfigToml(context: SetupContext) {
  return [
    `model = "${CODEX_MODEL}"`,
    `review_model = "${CODEX_REVIEW_MODEL}"`,
    `model_provider = "starry"`,
    `model_reasoning_effort = "xhigh"`,
    `model_verbosity = "medium"`,
    `disable_response_storage = true`,
    `windows_wsl_setup_acknowledged = true`,
    ``,
    `[model_providers.starry]`,
    `name = "Starry API"`,
    `base_url = ${JSON.stringify(context.apiBaseUrl)}`,
    `wire_api = "responses"`,
    `requires_openai_auth = true`,
    `supports_websockets = false`,
  ].join('\n');
}

function platformLabelForCode(platform: SetupPlatform) {
  switch (platform) {
    case 'cmd':
      return 'Windows CMD';
    case 'powershell':
      return 'PowerShell';
    case 'unix':
      return 'macOS / Linux';
  }
}

function envLines(platform: SetupPlatform, vars: Array<[string, string]>) {
  return vars.map(([key, value]) => {
    if (platform === 'cmd') {
      return `set "${key}=${cmdValue(value)}"`;
    }
    if (platform === 'powershell') {
      return `$env:${key} = ${psQuote(value)}`;
    }
    return `export ${key}=${shQuote(value)}`;
  });
}

function cmdValue(value: string) {
  return value.replace(/"/g, '\\"');
}

function openCodeJson(context: SetupContext) {
  return JSON.stringify(
    {
      $schema: 'https://opencode.ai/config.json',
      model: OPENCODE_MODEL,
      small_model: OPENCODE_SMALL_MODEL,
      provider: {
        starry: {
          npm: '@ai-sdk/openai-compatible',
          name: 'Starry API',
          options: {
            baseURL: context.apiBaseUrl,
            apiKey: normalizedApiKey(context.apiKey),
          },
          models: {
            'gpt-5.5': openCodeModel('GPT-5.5', 200000, 64000),
            'gpt-5.5-pro': openCodeModel('GPT-5.5 Pro', 200000, 64000),
            'gpt-5.4': openCodeModel('GPT-5.4', 200000, 64000),
            'gpt-5.4-mini': openCodeModel('GPT-5.4 Mini', 200000, 32000),
            'claude-sonnet-4.5': openCodeModel('Claude Sonnet 4.5', 200000, 64000),
            'claude-haiku-4.5': openCodeModel('Claude Haiku 4.5', 200000, 32000),
          },
        },
      },
    },
    null,
    2,
  );
}

function openCodeModel(name: string, context: number, output: number) {
  return {
    name,
    limit: { context, output },
  };
}

function copyableFilesText(files: SetupFileBlock[]) {
  if (files.length === 1) {
    return files[0].content;
  }
  return files
    .map((file) => [`# ${file.path}`, file.content].join('\n'))
    .join('\n\n');
}

function ccSwitchProviderUrl(input: {
  app: 'claude' | 'codex';
  name: string;
  homepage: string;
  endpoint: string;
  apiKey: string;
  model: string;
}) {
  return `ccswitch://v1/import?${new URLSearchParams([
    ['resource', 'provider'],
    ['app', input.app],
    ['name', input.name],
    ['homepage', input.homepage],
    ['endpoint', input.endpoint],
    ['apiKey', input.apiKey],
    ['model', input.model],
    ['configFormat', 'json'],
    ['enabled', 'true'],
  ]).toString()}`;
}
