import { readonly, ref } from 'vue';
import english from './en.json';

export type Locale = 'zh-CN' | 'en-US';
export const LOCALE_STORAGE_KEY = 'starry.locale';
const messages: Record<string, string> = english;

export function resolveLocale(saved: string | null, languages: readonly string[]): Locale {
  if (saved === 'zh-CN' || saved === 'en-US') return saved;
  return languages[0]?.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US';
}

function initialLocale(): Locale {
  let saved: string | null = null;
  try {
    saved = localStorage.getItem(LOCALE_STORAGE_KEY);
  } catch {
    /* Storage may be disabled. */
  }
  return resolveLocale(
    saved,
    typeof navigator === 'undefined'
      ? ['zh-CN']
      : navigator.languages.length
        ? navigator.languages
        : [navigator.language],
  );
}

const current = ref<Locale>(initialLocale());
export const locale = readonly(current);

export function setLocale(value: Locale) {
  if (value !== 'zh-CN' && value !== 'en-US') return;
  current.value = value;
  if (typeof document !== 'undefined') document.documentElement.lang = value;
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, value);
  } catch {
    /* Language still works for this session. */
  }
}

// Source text is the Chinese fallback. Only explicit calls translate UI text, never user data.
export function t(source: string, params: Record<string, unknown> = {}): string {
  const key = source.trim();
  const translated = Object.hasOwn(messages, key) ? messages[key] : undefined;
  const message =
    current.value === 'en-US' && translated !== undefined
      ? source.slice(0, source.indexOf(key)) +
        translated +
        source.slice(source.indexOf(key) + key.length)
      : source;
  return message.replace(/\{(p\d+)\}/g, (match, key: string) =>
    Object.hasOwn(params, key) ? String(params[key] ?? '') : match,
  );
}

if (typeof document !== 'undefined') document.documentElement.lang = current.value;
if (typeof window !== 'undefined')
  window.addEventListener('storage', (event) => {
    if (event.key !== LOCALE_STORAGE_KEY && event.key !== null) return;
    current.value = resolveLocale(event.newValue, navigator.languages);
    document.documentElement.lang = current.value;
  });
