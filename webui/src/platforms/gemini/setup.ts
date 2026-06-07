import type { SetupContext } from '../types';
import { normalizedApiKey, psQuote, shQuote } from '../setupShared';

export function geminiConnectionLine(context: SetupContext) {
  return `Gemini Base URL: ${context.apiOrigin}/v1beta`;
}

export function geminiUnixEnvLines(context: SetupContext) {
  const apiKey = shQuote(normalizedApiKey(context.apiKey));
  return [
    `export GEMINI_BASE_URL=${shQuote(`${context.apiOrigin}/v1beta`)}`,
    `export GEMINI_API_KEY=${apiKey}`,
    `export GOOGLE_API_KEY=${apiKey}`,
    `export GEMINI_MODEL='gemini-2.5-pro'`,
  ];
}

export function geminiPowerShellEnvLines(context: SetupContext) {
  const apiKey = psQuote(normalizedApiKey(context.apiKey));
  return [
    `$env:GEMINI_BASE_URL = ${psQuote(`${context.apiOrigin}/v1beta`)}`,
    `$env:GEMINI_API_KEY = ${apiKey}`,
    `$env:GOOGLE_API_KEY = ${apiKey}`,
    `$env:GEMINI_MODEL = 'gemini-2.5-pro'`,
  ];
}
