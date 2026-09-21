/** 管理员统计 API。对应后端 /admin/logs 与 /admin/performance。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  PerformanceMetric,
  RequestLog,
  RequestLogPage,
  UsageSummary,
  UsageTracker,
} from '@/types/api';

export const logsApi = {
  list() {
    return unwrap<RequestLog[]>(http.get<ApiResponse<RequestLog[]>>('/admin/logs'));
  },
  performance() {
    return unwrap<PerformanceMetric[]>(
      http.get<ApiResponse<PerformanceMetric[]>>('/admin/performance'),
    );
  },
  usageSummary() {
    return unwrap<UsageSummary>(http.get<ApiResponse<UsageSummary>>('/admin/usage-summary'));
  },
  myUsageSummary() {
    return unwrap<UsageSummary>(http.get<ApiResponse<UsageSummary>>('/admin/usage-summary/me'));
  },
  myTracker(params: { from?: string; to?: string } = {}) {
    return unwrap<UsageTracker>(
      http.get<ApiResponse<UsageTracker>>('/admin/usage-tracker/me', {
        params: compactParams(params),
      }),
    );
  },
  myLogs(params: { from?: string; to?: string; before?: string; limit?: number } = {}) {
    return unwrap<RequestLogPage>(
      http.get<ApiResponse<RequestLogPage>>('/admin/logs/me', {
        params: compactParams(params),
      }),
    );
  },
};

function compactParams<T extends Record<string, string | number | undefined>>(params: T) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== ''),
  );
}
