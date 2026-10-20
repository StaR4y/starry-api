/**
 * 站点品牌与主题。公开接口即可读取，登录页和维护页也能用自定义 Logo / 配色。
 */
import { defineStore } from 'pinia';
import { appearanceApi } from '@/api';
import type { Appearance, AppearanceChrome } from '@/types/api';

export const DEFAULT_APPEARANCE: Appearance = {
  siteName: 'Starry API',
  logoUrl: null,
  faviconUrl: null,
  backgroundImageUrl: null,
  preset: 'default',
  primary: '#5b8def',
  primaryMid: '#7b7cff',
  accent: '#b387ff',
  success: '#36c08a',
  warning: '#f0a84d',
  danger: '#ef5b6b',
  header: '#1a1f36',
  text: '#1f2a44',
  textSecondary: '#54607a',
  textMuted: '#8a93a8',
  sidebarOpacity: 0.62,
  surfaceOpacity: 0.62,
  blurPx: 18,
  backgroundBlurPx: 0,
  chrome: 'classic',
};

export function normalizeChrome(value: string | null | undefined): AppearanceChrome {
  return value === 'editorial' ? 'editorial' : 'classic';
}

function parseHex(hex: string): [number, number, number] | null {
  const match = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!match) return null;
  const value = Number.parseInt(match[1], 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function mixWithWhite(hex: string, amount: number): string {
  const rgb = parseHex(hex);
  if (!rgb) return hex;
  const mix = (channel: number) => Math.round(channel + (255 - channel) * amount);
  return `#${[mix(rgb[0]), mix(rgb[1]), mix(rgb[2])]
    .map((channel) => channel.toString(16).padStart(2, '0'))
    .join('')}`;
}

function shade(hex: string, amount: number): string {
  const rgb = parseHex(hex);
  if (!rgb) return hex;
  const mix = (channel: number) => Math.round(channel * (1 - amount));
  return `#${[mix(rgb[0]), mix(rgb[1]), mix(rgb[2])]
    .map((channel) => channel.toString(16).padStart(2, '0'))
    .join('')}`;
}

function rgba(hex: string, alpha: number): string {
  const rgb = parseHex(hex);
  if (!rgb) return hex;
  return `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${alpha})`;
}

function setFavicon(url: string) {
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  link.type = url.endsWith('.svg') ? 'image/svg+xml' : 'image/png';
  link.href = url;
}

export function applyAppearance(appearance: Appearance) {
  const root = document.documentElement;
  root.style.setProperty('--kp-primary', appearance.primary);
  root.style.setProperty('--kp-primary-mid', appearance.primaryMid);
  root.style.setProperty('--kp-accent', appearance.accent);
  root.style.setProperty('--kp-success', appearance.success);
  root.style.setProperty('--kp-warning', appearance.warning);
  root.style.setProperty('--kp-danger', appearance.danger);
  root.style.setProperty('--kp-header', appearance.header);
  root.style.setProperty('--kp-text', appearance.text);
  root.style.setProperty('--kp-text-secondary', appearance.textSecondary);
  root.style.setProperty('--kp-text-muted', appearance.textMuted);
  root.style.setProperty('--kp-primary-soft', rgba(appearance.primary, 0.16));
  root.style.setProperty('--kp-blur', `${appearance.blurPx}px`);
  root.style.setProperty('--kp-sidebar-opacity', String(appearance.sidebarOpacity));
  root.style.setProperty('--kp-surface', `rgba(255, 255, 255, ${appearance.surfaceOpacity})`);
  root.style.setProperty(
    '--kp-surface-strong',
    `rgba(255, 255, 255, ${Math.min(0.95, appearance.surfaceOpacity + 0.16)})`,
  );
  root.style.setProperty('--el-color-primary', appearance.primary);
  root.style.setProperty('--el-color-primary-light-3', mixWithWhite(appearance.primary, 0.3));
  root.style.setProperty('--el-color-primary-light-5', mixWithWhite(appearance.primary, 0.5));
  root.style.setProperty('--el-color-primary-light-7', mixWithWhite(appearance.primary, 0.7));
  root.style.setProperty('--el-color-primary-light-9', mixWithWhite(appearance.primary, 0.9));
  root.style.setProperty('--el-color-primary-dark-2', shade(appearance.primary, 0.18));
  root.style.setProperty('--el-color-success', appearance.success);
  root.style.setProperty('--el-color-warning', appearance.warning);
  root.style.setProperty('--el-color-danger', appearance.danger);
  const backgroundUrl = appearance.backgroundImageUrl;
  if (backgroundUrl) {
    root.style.setProperty('--kp-bg-image', `url("${backgroundUrl}")`);
    root.dataset.hasBg = 'true';
  } else {
    root.style.setProperty('--kp-bg-image', 'none');
    root.dataset.hasBg = 'false';
  }
  root.style.setProperty('--kp-bg-blur', `${appearance.backgroundBlurPx}px`);
  root.dataset.uiChrome = normalizeChrome(appearance.chrome);
  delete root.dataset.appearancePreset;
  setFavicon(appearance.faviconUrl || appearance.logoUrl || '/favicon.svg');
  const themeColor = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (themeColor) themeColor.content = appearance.primary;
}

export const useAppearanceStore = defineStore('appearance', {
  state: (): { current: Appearance; draft: Appearance | null; loaded: boolean } => ({
    current: { ...DEFAULT_APPEARANCE },
    draft: null,
    loaded: false,
  }),

  getters: {
    displayed: (state): Appearance => state.draft ?? state.current,
    siteName(): string {
      return this.displayed.siteName?.trim() || DEFAULT_APPEARANCE.siteName;
    },
    logoSrc(): string {
      return this.displayed.logoUrl || '/logo.svg';
    },
    chrome(): AppearanceChrome {
      return normalizeChrome(this.displayed.chrome);
    },
    isEditorial(): boolean {
      return normalizeChrome(this.displayed.chrome) === 'editorial';
    },
  },

  actions: {
    replace(appearance: Appearance) {
      this.current = {
        ...DEFAULT_APPEARANCE,
        ...appearance,
        chrome: normalizeChrome(appearance.chrome),
      };
      this.draft = null;
      this.loaded = true;
      applyAppearance(this.current);
    },
    preview(appearance: Appearance) {
      this.draft = {
        ...DEFAULT_APPEARANCE,
        ...appearance,
        chrome: normalizeChrome(appearance.chrome),
      };
      applyAppearance(this.draft);
    },
    restorePreview() {
      this.draft = null;
      applyAppearance(this.current);
    },
    async load() {
      try {
        this.replace(await appearanceApi.public());
      } catch {
        this.replace({ ...DEFAULT_APPEARANCE });
      }
    },
  },
});
