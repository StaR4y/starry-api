/**
 * Started plugin widgets and the current user's show/hide preference.
 * Preference is per browser account (localStorage), default visible.
 */
import { defineStore } from 'pinia';
import http, { unwrap } from '@/utils/http';
import type { ApiResponse } from '@/types/api';
import { useAuthStore } from '@/stores/auth';

export interface PluginWidget {
  pluginId: string;
  title: string;
  imageUrl: string;
  scriptUrl?: string | null;
}

const PREFS_KEY = 'starry-plugin-widget-prefs';

function readAllPrefs(): Record<string, Record<string, boolean>> {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    return raw ? (JSON.parse(raw) as Record<string, Record<string, boolean>>) : {};
  } catch {
    return {};
  }
}

export const usePluginWidgetStore = defineStore('pluginWidgets', {
  state: () => ({
    available: [] as PluginWidget[],
    prefs: readAllPrefs(),
  }),

  getters: {
    ownerId(): string {
      return useAuthStore().user?.userId || '_';
    },
    visible(): PluginWidget[] {
      const mine = this.prefs[this.ownerId] ?? {};
      return this.available.filter((widget) => mine[widget.pluginId] !== false);
    },
  },

  actions: {
    isEnabled(pluginId: string) {
      return this.prefs[this.ownerId]?.[pluginId] !== false;
    },

    setEnabled(pluginId: string, enabled: boolean) {
      const owner = this.ownerId;
      this.prefs = {
        ...this.prefs,
        [owner]: {
          ...(this.prefs[owner] ?? {}),
          [pluginId]: enabled,
        },
      };
      localStorage.setItem(PREFS_KEY, JSON.stringify(this.prefs));
      window.dispatchEvent(new CustomEvent('starry-plugin-widget-prefs'));
    },

    reloadPrefs() {
      this.prefs = readAllPrefs();
    },

    async refresh() {
      try {
        this.available = await unwrap<PluginWidget[]>(
          http.get<ApiResponse<PluginWidget[]>>('/system/plugin-widgets', { timeout: 5_000 }),
        );
      } catch {
        this.available = [];
      }
    },
  },
});
