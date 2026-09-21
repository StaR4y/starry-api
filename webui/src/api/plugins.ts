import http, { unwrap } from '@/utils/http';
import type { ApiResponse } from '@/types/api';

export interface PluginInfo {
  id: string;
  version: string;
  provider: string;
  description: string;
  sha256: string;
  size: number;
  autoStart: boolean;
  state: string;
  error: string;
  installedAt: string;
}
export interface PluginRestartReason {
  pluginId: string;
  operation: string;
  code: string;
  detail: string;
  at: string;
}
export interface PluginStatus {
  root: boolean;
  developerMode: boolean;
  runtimeDisabled: boolean;
  restartRequired: boolean;
  rootEmail: string;
  apiVersion: string;
  plugins: PluginInfo[];
  restartReasons: PluginRestartReason[];
}
export interface PluginLog {
  sequence: number;
  at: string;
  pluginId: string;
  level: string;
  message: string;
}
export interface PluginAudit {
  id: number;
  at: string;
  pluginId: string;
  actor: string;
  action: string;
  outcome: string;
  detail: string;
}
export interface PluginChallenge {
  id: string;
  email: string;
  expiresAt: string;
  resendAfterSeconds: number;
}
export interface PluginSettingPage {
  pluginId: string;
  title: string;
  group: string;
}
export interface PluginSettingField {
  key: string;
  type: string;
  label: string;
  min?: number;
  max?: number;
  step?: number;
  rows?: number;
  default?: unknown;
}
export interface PluginModuleSettings {
  pluginId: string;
  title: string;
  hint: string;
  fields: PluginSettingField[];
  config: Record<string, unknown>;
  models: Array<{ id: string; name: string; provider: string }>;
}

const base = '/admin/plugins';
export const pluginsApi = {
  status: () => unwrap<PluginStatus>(http.get(`${base}`)),
  sendCode: (password: string) =>
    unwrap<PluginChallenge>(http.post(`${base}/verification`, { password })),
  enable: (payload: { password: string; challengeId: string; code: string; acceptRisk: boolean }) =>
    unwrap<boolean>(http.post(`${base}/developer-mode`, payload)),
  disable: () => unwrap<boolean>(http.delete(`${base}/developer-mode`, { timeout: 90_000 })),
  upload(file: File) {
    const body = new FormData();
    body.append('file', file);
    return unwrap<boolean>(http.post(base, body, { timeout: 90_000 }));
  },
  action: (id: string, action: string) =>
    unwrap<boolean>(
      http.post(`${base}/${encodeURIComponent(id)}/actions/${action}`, {}, { timeout: 90_000 }),
    ),
  config: (id: string) => unwrap<string>(http.get(`${base}/${encodeURIComponent(id)}/config`)),
  configure: (id: string, json: string) =>
    unwrap<boolean>(http.put(`${base}/${encodeURIComponent(id)}/config`, { json })),
  debug: (id: string, command: string, json: string) =>
    unwrap<{ json: string; durationMs: number }>(
      http.post(`${base}/${encodeURIComponent(id)}/debug`, { command, json }, { timeout: 90_000 }),
    ),
  logs: (plugin = '', after = 0) =>
    unwrap<PluginLog[]>(
      http.get<ApiResponse<PluginLog[]>>(`${base}/logs`, { params: { plugin, after } }),
    ),
  audit: (plugin = '', before = 0) =>
    unwrap<PluginAudit[]>(http.get(`${base}/audit`, { params: { plugin, before } })),
  settingPages: () => unwrap<PluginSettingPage[]>(http.get('/system/plugin-settings')),
  moduleSettings: (id: string) =>
    unwrap<PluginModuleSettings>(http.get(`/admin/plugin-modules/${encodeURIComponent(id)}/settings`)),
  saveModuleSettings: (id: string, config: Record<string, unknown>) =>
    unwrap<PluginModuleSettings>(
      http.put(`/admin/plugin-modules/${encodeURIComponent(id)}/settings`, config),
    ),
};
