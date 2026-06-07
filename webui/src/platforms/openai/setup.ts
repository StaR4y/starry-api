import type { SetupContext } from '../types';
import { normalizedApiKey, psQuote, shQuote } from '../setupShared';

export function openAiConnectionLine(context: SetupContext) {
  return `OpenAI Base URL: ${context.apiBaseUrl}`;
}

export function openAiUnixEnvLines(context: SetupContext) {
  return [
    `export OPENAI_BASE_URL=${shQuote(context.apiBaseUrl)}`,
    `export OPENAI_API_KEY=${shQuote(normalizedApiKey(context.apiKey))}`,
  ];
}

export function openAiPowerShellEnvLines(context: SetupContext) {
  return [
    `$env:OPENAI_BASE_URL = ${psQuote(context.apiBaseUrl)}`,
    `$env:OPENAI_API_KEY = ${psQuote(normalizedApiKey(context.apiKey))}`,
  ];
}
