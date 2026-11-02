/**
 * AGPL-3.0, section 13: the console tells every user where the source of the running version is. The address is the
 * operator's runtime setting STARRY_SOURCE_URL, delivered with the public appearance (GET /system/appearance); the
 * default below is only what shows while that call has not answered (or has failed).
 */
import { t } from '@/i18n';

export const LICENSE_NAME = 'AGPL-3.0-only';
export const LICENSE_URL = 'https://www.gnu.org/licenses/agpl-3.0.html';
export const DEFAULT_SOURCE_URL = 'https://github.com/StaR4y/starry-api';
/** Written next to the console by the production build (see thirdPartyNotices in vite.config.ts). */
export const THIRD_PARTY_NOTICES_HREF = './THIRD-PARTY-NOTICES.txt';

/** The address to link to: an absolute http(s) URL without credentials, otherwise the upstream project. */
export function resolveSourceUrl(value: string | null | undefined): string {
  const candidate = (value ?? '').trim();
  if (!candidate || /[\s"<>\\]/.test(candidate)) return DEFAULT_SOURCE_URL;
  try {
    const url = new URL(candidate);
    const web = url.protocol === 'https:' || url.protocol === 'http:';
    if (!web || !url.hostname || url.username || url.password) return DEFAULT_SOURCE_URL;
    return candidate;
  } catch {
    return DEFAULT_SOURCE_URL;
  }
}

/** The non-affiliation and terms-of-service notice, the same text as the Disclaimer in README.md. */
export function disclaimerText(): string {
  return t(
    'Starry API 是独立的开源项目，与 Anthropic、OpenAI、Google、Amazon Web Services、DeepSeek 等任何厂商均无隶属、合作或背书关系。Claude、Claude Code、ChatGPT、Codex、OpenAI、Gemini、Kiro、Amazon Q、CodeWhisperer、DeepSeek 等名称和标识归各自权利人所有，本项目仅用于说明可连接的服务。你需要自行确保接入的每个厂商和账号都符合其条款，包括账号共享、转售、自动化访问和支持地区的规定。本软件能实现的某些配置可能被这些条款禁止。软件按现状提供，厂商可能限制或停用使用它的账号。',
  );
}
