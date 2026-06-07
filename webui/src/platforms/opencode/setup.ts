import type { SetupContext } from '../types';
import { normalizedApiKey, psQuote, shQuote } from '../setupShared';

export function openCodeConnectionLine(context: SetupContext) {
  return `OpenCode Base URL: ${context.apiBaseUrl}`;
}

export function openCodeUnixEnvLines(context: SetupContext) {
  return [
    `export OPENCODE_STARRY_API_BASE_URL=${shQuote(context.apiBaseUrl)}`,
    `export OPENCODE_STARRY_API_KEY=${shQuote(normalizedApiKey(context.apiKey))}`,
    `export OPENCODE_MODEL='starry/gpt-5.5'`,
    `export OPENCODE_SMALL_MODEL='starry/gpt-5.4-mini'`,
  ];
}

export function openCodePowerShellEnvLines(context: SetupContext) {
  return [
    `$env:OPENCODE_STARRY_API_BASE_URL = ${psQuote(context.apiBaseUrl)}`,
    `$env:OPENCODE_STARRY_API_KEY = ${psQuote(normalizedApiKey(context.apiKey))}`,
    `$env:OPENCODE_MODEL = 'starry/gpt-5.5'`,
    `$env:OPENCODE_SMALL_MODEL = 'starry/gpt-5.4-mini'`,
  ];
}

export function openCodeUnixConfigLines(context: SetupContext) {
  return [
    `cat > ./opencode.json <<'EOF'`,
    openCodeJson(context),
    `EOF`,
    `echo "saved: $(pwd)/opencode.json"`,
  ];
}

export function openCodePowerShellConfigLines(context: SetupContext) {
  return [
    `$opencodeConfig = Join-Path (Get-Location) 'opencode.json'`,
    `@'`,
    openCodeJson(context),
    `'@ | Set-Content -Path $opencodeConfig -Encoding UTF8`,
    `Write-Host "saved: $opencodeConfig"`,
  ];
}

function openCodeJson(context: SetupContext) {
  return [
    `{`,
    `  "$schema": "https://opencode.ai/config.json",`,
    `  "enabled_providers": ["starry"],`,
    `  "model": "{env:OPENCODE_MODEL}",`,
    `  "small_model": "{env:OPENCODE_SMALL_MODEL}",`,
    `  "provider": {`,
    `    "starry": {`,
    `      "npm": "@ai-sdk/openai-compatible",`,
    `      "name": "Starry API",`,
    `      "options": {`,
    `        "baseURL": ${JSON.stringify(context.apiBaseUrl)},`,
    `        "apiKey": "{env:OPENCODE_STARRY_API_KEY}"`,
    `      },`,
    `      "models": {`,
    `        "gpt-5.5": {`,
    `          "name": "GPT-5.5",`,
    `          "limit": { "context": 200000, "output": 64000 }`,
    `        },`,
    `        "gpt-5.5-pro": {`,
    `          "name": "GPT-5.5 Pro",`,
    `          "limit": { "context": 200000, "output": 64000 }`,
    `        },`,
    `        "gpt-5.4": {`,
    `          "name": "GPT-5.4",`,
    `          "limit": { "context": 200000, "output": 64000 }`,
    `        },`,
    `        "gpt-5.4-mini": {`,
    `          "name": "GPT-5.4 Mini",`,
    `          "limit": { "context": 200000, "output": 32000 }`,
    `        },`,
    `        "gpt-5.2": {`,
    `          "name": "GPT-5.2",`,
    `          "limit": { "context": 200000, "output": 64000 }`,
    `        },`,
    `        "gpt-5.1": {`,
    `          "name": "GPT-5.1",`,
    `          "limit": { "context": 200000, "output": 64000 }`,
    `        },`,
    `        "gpt-5": {`,
    `          "name": "GPT-5",`,
    `          "limit": { "context": 200000, "output": 64000 }`,
    `        },`,
    `        "gpt-5-mini": {`,
    `          "name": "GPT-5 Mini",`,
    `          "limit": { "context": 200000, "output": 32000 }`,
    `        },`,
    `        "claude-sonnet-4.5": {`,
    `          "name": "Claude Sonnet 4.5",`,
    `          "limit": { "context": 200000, "output": 64000 }`,
    `        },`,
    `        "claude-haiku-4.5": {`,
    `          "name": "Claude Haiku 4.5",`,
    `          "limit": { "context": 200000, "output": 32000 }`,
    `        }`,
    `      }`,
    `    }`,
    `  }`,
    `}`,
  ].join('\n');
}
