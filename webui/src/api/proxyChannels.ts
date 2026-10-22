import http, { unwrap } from '@/utils/http';
import type { ApiResponse } from '@/types/api';

export interface ProxyChannel {
  channelId: string;
  subscriptionId?: string | null;
  name: string;
  protocol: string;
  enabled: boolean;
  runtimeStatus: string;
  runtimeError?: string | null;
  boundAccounts: number;
}
export interface ProxySubscription {
  subscriptionId: string;
  name: string;
  status: string;
  lastSyncedAt?: string;
  lastError?: string;
  nodeCount: number;
  refreshable: boolean;
}
export interface ProxyCatalog {
  channels: ProxyChannel[];
  subscriptions: ProxySubscription[];
  mihomoEnabled: boolean;
  tlsEnabled: boolean;
  tlsProfiles: string[];
}
export interface ProxySyncResult {
  imported: number;
  unavailable: number;
  issues: string[];
}
export interface ProxyProbe {
  success: boolean;
  exitIp?: string;
  latencyMs: number;
  error?: string;
}
export const proxyChannelsApi = {
  catalog: () => unwrap<ProxyCatalog>(http.get<ApiResponse<ProxyCatalog>>('/admin/proxy-channels')),
  create: (payload: { name: string; proxyUrl?: string; clashYaml?: string }) =>
    unwrap<ProxyChannel>(http.post<ApiResponse<ProxyChannel>>('/admin/proxy-channels', payload)),
  update: (id: string, payload: { name?: string; enabled?: boolean }) =>
    unwrap<ProxyChannel>(
      http.patch<ApiResponse<ProxyChannel>>(`/admin/proxy-channels/${id}`, payload),
    ),
  remove: (id: string) =>
    unwrap<void>(http.delete<ApiResponse<void>>(`/admin/proxy-channels/${id}`)),
  test: (id: string) =>
    unwrap<ProxyProbe>(http.post<ApiResponse<ProxyProbe>>(`/admin/proxy-channels/${id}/test`)),
  subscribe: (payload: { name: string; url?: string; yaml?: string }) =>
    unwrap<ProxySyncResult>(
      http.post<ApiResponse<ProxySyncResult>>('/admin/proxy-channels/subscriptions', payload, {
        timeout: 90000,
      }),
    ),
  sync: (id: string) =>
    unwrap<ProxySyncResult>(
      http.post<ApiResponse<ProxySyncResult>>(
        `/admin/proxy-channels/subscriptions/${id}/sync`,
        undefined,
        { timeout: 90000 },
      ),
    ),
};
