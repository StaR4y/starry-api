import type { SetupContext } from './types';

export function normalizedApiKey(apiKey?: string | null) {
  return apiKey?.trim() || 'sk-REPLACE_ME';
}

/**
 * 指定号池的请求头（见 docs/API.md「指定号池」）。客户端配置用它：请求头是提示，号池用不上（别的厂商，或这个请求的路由
 * 在号池里没有账号）时请求照常按自动服务，只在响应里说明已忽略；模型后缀是要求，用不上就被拒绝。
 */
export const TIER_HEADER = 'X-Starry-Tier';

/**
 * 能写进配置的号池写法：号池标识（`kiro`、`l2`、`2` 都是），后面可以加 +；转小写。缺省、auto 和后端不会接受的写法
 * 都是 null，配置里就不写号池。
 */
export function tierReference(tier?: string | null): string | null {
  const reference = (tier ?? '').trim().toLowerCase();
  return /^[a-z0-9][a-z0-9-]{0,31}\+?$/.test(reference) && !/^auto\+?$/.test(reference)
    ? reference
    : null;
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
