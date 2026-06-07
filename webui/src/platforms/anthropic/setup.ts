import type { SetupContext } from '../types';
import { normalizedApiKey, psQuote, shQuote } from '../setupShared';

export function anthropicConnectionLine(context: SetupContext) {
  return `Anthropic Base URL: ${context.apiOrigin}`;
}

export function anthropicUnixEnvLines(context: SetupContext) {
  const apiKey = shQuote(normalizedApiKey(context.apiKey));
  return [
    `export ANTHROPIC_BASE_URL=${shQuote(context.apiOrigin)}`,
    `export ANTHROPIC_API_KEY=${apiKey}`,
    `export ANTHROPIC_AUTH_TOKEN=${apiKey}`,
    `export ANTHROPIC_MODEL='auto'`,
    `export ANTHROPIC_SMALL_FAST_MODEL='auto'`,
    `export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1'`,
  ];
}

export function anthropicPowerShellEnvLines(context: SetupContext) {
  const apiKey = psQuote(normalizedApiKey(context.apiKey));
  return [
    `$env:ANTHROPIC_BASE_URL = ${psQuote(context.apiOrigin)}`,
    `$env:ANTHROPIC_API_KEY = ${apiKey}`,
    `$env:ANTHROPIC_AUTH_TOKEN = ${apiKey}`,
    `$env:ANTHROPIC_MODEL = 'auto'`,
    `$env:ANTHROPIC_SMALL_FAST_MODEL = 'auto'`,
    `$env:CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC = '1'`,
  ];
}
