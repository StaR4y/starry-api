/**
 * 全局 HTTP 客户端。
 *
 * 职责：
 * 1. 统一注入 Authorization 头（adm-... 会话令牌）。
 * 2. 解包 `ApiResponse` 并把业务错误转换成 Error，避免 view 层重复判断 success。
 * 3. 401 时自动登出并跳转登录页。
 * 4. 403 + FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED 时引导跳转改密页。
 */

import axios, { type AxiosInstance, type InternalAxiosRequestConfig, AxiosError } from 'axios';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from '@/types/api';

const TOKEN_STORAGE_KEY = 'kp.adminToken';

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY);
}

export function setStoredToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_STORAGE_KEY, token);
  else localStorage.removeItem(TOKEN_STORAGE_KEY);
}

const http: AxiosInstance = axios.create({
  baseURL: '/',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
});

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getStoredToken();
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** 业务错误：携带后端返回的 error.code，便于上层做差异化处理 */
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status?: number,
  ) {
    super(message);
  }
}

/** 不需要走全局错误提示的接口，由调用方自行处理 */
const SILENT_PATHS = [
  '/auth/login',
  '/auth/me',
  '/auth/register',
  '/auth/send-verify-code',
  '/auth/password',
  '/auth/registration-settings',
];

http.interceptors.response.use(
  (response) => response,
  (err: AxiosError<ApiResponse<unknown>>) => {
    const status = err.response?.status ?? 0;
    const apiError = err.response?.data?.error;
    const code = apiError?.code ?? 'NETWORK_ERROR';
    const message = apiError?.message ?? err.message ?? '请求失败';
    const url = err.config?.url ?? '';

    if (status === 401) {
      setStoredToken(null);
      // 用 hash 跳转，避免与 router 强耦合
      if (!location.hash.startsWith('#/login')) {
        location.hash = '#/login';
      }
    }

    const silent = SILENT_PATHS.some((p) => url.endsWith(p));
    if (!silent) {
      ElMessage.error(message);
    }
    return Promise.reject(new ApiError(code, message, status));
  },
);

/**
 * 解包响应：返回 `data` 字段，失败时抛出 ApiError。
 * 业务代码应使用此函数而非直接访问 axios response。
 */
export async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const { data } = await promise;
  if (!data.success) {
    throw new ApiError(data.error?.code ?? 'UNKNOWN', data.error?.message ?? '请求失败');
  }
  return data.data;
}

export default http;
