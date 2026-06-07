import type { SetupContext } from './types';

export function normalizedApiKey(apiKey?: string | null) {
  return apiKey?.trim() || 'sk-REPLACE_ME';
}

export function shQuote(value: string) {
  return `'${value.replace(/'/g, `'"'"'`)}'`;
}

export function psQuote(value: string) {
  return `'${value.replace(/'/g, `''`)}'`;
}

export function starryUnixEnvLines(context: SetupContext) {
  return [
    `export STARRY_API_BASE_URL=${shQuote(context.apiBaseUrl)}`,
    `export STARRY_API_KEY=${shQuote(normalizedApiKey(context.apiKey))}`,
    `export STARRY_API_PORT=${shQuote(context.apiPort)}`,
  ];
}

export function starryPowerShellEnvLines(context: SetupContext) {
  return [
    `$env:STARRY_API_BASE_URL = ${psQuote(context.apiBaseUrl)}`,
    `$env:STARRY_API_KEY = ${psQuote(normalizedApiKey(context.apiKey))}`,
    `$env:STARRY_API_PORT = ${psQuote(context.apiPort)}`,
  ];
}
