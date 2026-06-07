/** 请求日志 API。对应后端 /admin/logs。 */
import http, { unwrap } from '@/utils/http';
import type { ApiResponse, RequestLog } from '@/types/api';

export const logsApi = {
  list() {
    return unwrap<RequestLog[]>(http.get<ApiResponse<RequestLog[]>>('/admin/logs'));
  },
};
