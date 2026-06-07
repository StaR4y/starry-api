/** 管理员统计 API。对应后端 /admin/logs 与 /admin/performance。 */
import http, { unwrap } from '@/utils/http';
import type { ApiResponse, PerformanceMetric, RequestLog } from '@/types/api';

export const logsApi = {
  list() {
    return unwrap<RequestLog[]>(http.get<ApiResponse<RequestLog[]>>('/admin/logs'));
  },
  performance() {
    return unwrap<PerformanceMetric[]>(
      http.get<ApiResponse<PerformanceMetric[]>>('/admin/performance'),
    );
  },
};
