import http, { unwrap } from '@/utils/http';
import type { ApiResponse } from '@/types/api';

/**
 * DIRECT_PROXY：普通 HTTP(S) / SOCKS5 节点，由后端直接连接，不依赖 Mihomo；
 * MIHOMO：其他协议（VLESS、Hysteria2 等），需要启用 Mihomo 组件。
 */
export type ProxyRoute = 'DIRECT_PROXY' | 'MIHOMO';
export type ProxyRuntimeStatus = 'PENDING' | 'ACTIVE' | 'ERROR' | 'DISABLED';

export interface ProxyChannel {
  channelId: string;
  subscriptionId?: string | null;
  subscriptionName?: string | null;
  name: string;
  protocol: string;
  route: ProxyRoute;
  enabled: boolean;
  runtimeStatus: ProxyRuntimeStatus | string;
  runtimeError?: string | null;
  boundAccounts: number;
  updatedAt?: string | null;
  /** 节点无法使用的原因（例如协议参数不受支持） */
  configIssue?: string | null;
  /** 为 false 表示订阅已不再包含该节点 */
  sourcePresent: boolean;
  lastTestedAt?: string | null;
  lastTestResult?: 'SUCCESS' | 'FAILED' | null;
  lastTestExitIp?: string | null;
  lastTestLatencyMs?: number | null;
  lastTestErrorCode?: string | null;
  lastTestError?: string | null;
}
export interface ProxySubscription {
  subscriptionId: string;
  name: string;
  status: string;
  lastSyncedAt?: string | null;
  lastError?: string | null;
  nodeCount: number;
  refreshable: boolean;
}
/** 本实例看到的 Mihomo 组件状态；controllerReachable 在首次轮询前为 null */
export interface ProxyRuntimeHealth {
  controllerReachable: boolean | null;
  lastPollAt?: string | null;
  lastActivatedAt?: string | null;
  activeNodes: number;
  lastFailureCode?: string | null;
  lastFailureMessage?: string | null;
  lastFailureAt?: string | null;
}
export interface ProxyCatalog {
  channels: ProxyChannel[];
  subscriptions: ProxySubscription[];
  mihomoEnabled: boolean;
  tlsEnabled: boolean;
  tlsProfiles: string[];
  runtime: ProxyRuntimeHealth;
}
/** 绑定到节点的账号；订阅下的账号由 channelId / channelName 指明具体节点 */
export interface ProxyBoundAccount {
  accountId: string;
  email?: string | null;
  provider: string;
  authMethod: string;
  enabled: boolean;
  channelId: string;
  channelName: string;
}
export interface ProxySyncResult {
  subscriptionId?: string;
  imported: number;
  unavailable: number;
  issues: string[];
}
export interface ProxyProbe {
  success: boolean;
  exitIp?: string;
  latencyMs: number;
  error?: string;
  errorCode?: string;
}
/** 删除仍被账号使用的节点或订阅时返回 409 及此错误码，ApiError.data 为 ProxyBoundAccount[] */
export const PROXY_NODE_IN_USE = 'PROXY_NODE_IN_USE';

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
  /** 绑定到该节点的账号 */
  boundAccounts: (id: string) =>
    unwrap<ProxyBoundAccount[]>(
      http.get<ApiResponse<ProxyBoundAccount[]>>(`/admin/proxy-channels/${id}/accounts`),
    ),
  test: (id: string) =>
    unwrap<ProxyProbe>(
      http.post<ApiResponse<ProxyProbe>>(`/admin/proxy-channels/${id}/test`, undefined, {
        // Runtime activation can take 20s, followed by a 15s exit probe.
        timeout: 45000,
      }),
    ),
  subscribe: (payload: { name: string; url?: string; yaml?: string }) =>
    unwrap<ProxySyncResult>(
      http.post<ApiResponse<ProxySyncResult>>('/admin/proxy-channels/subscriptions', payload, {
        timeout: 90000,
      }),
    ),
  renameSubscription: (id: string, name: string) =>
    unwrap<ProxySubscription>(
      http.patch<ApiResponse<ProxySubscription>>(`/admin/proxy-channels/subscriptions/${id}`, {
        name,
      }),
    ),
  removeSubscription: (id: string) =>
    unwrap<void>(http.delete<ApiResponse<void>>(`/admin/proxy-channels/subscriptions/${id}`)),
  sync: (id: string) =>
    unwrap<ProxySyncResult>(
      http.post<ApiResponse<ProxySyncResult>>(
        `/admin/proxy-channels/subscriptions/${id}/sync`,
        undefined,
        { timeout: 90000 },
      ),
    ),
};
