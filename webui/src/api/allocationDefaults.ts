import http, { unwrap } from '@/utils/http';
import type { ApiResponse } from '@/types/api';

/** 新账号初始路由限制的账号类别，与后端 AccountAllocationCategory 一致 */
export type AllocationCategory =
  | 'OFFICIAL_API_KEY'
  | 'COMPATIBLE_API_KEY'
  | 'CODEX'
  | 'CLAUDE_OAUTH'
  | 'KIRO';

export const ALLOCATION_CATEGORIES: AllocationCategory[] = [
  'OFFICIAL_API_KEY',
  'COMPATIBLE_API_KEY',
  'CODEX',
  'CLAUDE_OAUTH',
  'KIRO',
];

/**
 * 与单账号策略相同的取值范围：safeConcurrency / maxSessions 为 0 表示不限，safeRpm 为 0 表示不限速，
 * cooldownSeconds 为 0 表示自动；burstCapacity 当且仅当 safeRpm 为 0 时为 0。
 * maxSessions 与 tlsProfile 只对 Anthropic 账号生效。
 */
export interface AllocationPolicy {
  safeConcurrency: number; // 0..10000
  safeRpm: number; // 0..1000000
  burstCapacity: number; // 0..100000
  maxSessions: number; // 0..100000
  sessionIdleSeconds: number; // 60..86400
  cooldownSeconds: number; // 0..86400
  maxInflightTokens: number; // 1..1000000000
  allocationPriority: number; // 1..10000
  tlsProfile: '' | 'chrome136' | 'safari18_4' | string;
}

export interface AllocationCategoryDefaults {
  category: AllocationCategory;
  /** maxSessions 与 tlsProfile 是否可能对该类别生效 */
  anthropicSettings: boolean;
  builtIn: AllocationPolicy;
  /** 管理员配置的值；未配置时为 null */
  configured: AllocationPolicy | null;
  /** 新建该类别账号时实际使用的值 */
  effective: AllocationPolicy;
}

export interface AllocationDefaults {
  categories: AllocationCategoryDefaults[];
}

export interface AllocationResolveResult {
  category: AllocationCategory;
  policy: AllocationPolicy;
}

export const allocationDefaultsApi = {
  get: () =>
    unwrap<AllocationDefaults>(
      http.get<ApiResponse<AllocationDefaults>>('/admin/accounts/allocation-defaults'),
    ),
  /** 整体替换：缺省或为 null 的类别恢复内置值 */
  update: (categories: Partial<Record<AllocationCategory, AllocationPolicy | null>>) =>
    unwrap<AllocationDefaults>(
      http.put<ApiResponse<AllocationDefaults>>('/admin/accounts/allocation-defaults', {
        categories,
      }),
    ),
  /** 新账号（按 provider / authMethod / baseUrl）会落入的类别及其初始策略 */
  resolve: (payload: { provider?: string; authMethod?: string; baseUrl?: string }) =>
    unwrap<AllocationResolveResult>(
      http.post<ApiResponse<AllocationResolveResult>>(
        '/admin/accounts/allocation-defaults/resolve',
        payload,
      ),
    ),
};
